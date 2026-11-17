using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using BnbLauncher.Models;

namespace BnbLauncher.Services;

/// <summary>一次构建判定的结果：这次到底编没编、为什么、花了多久。</summary>
public sealed record BuildOutcome(bool Compiled, string Reason, TimeSpan Elapsed);

/// <summary>
/// 服务端编译：只在必要时调用 Maven，编译成功后在 runtime\build 里留下凭证，
/// 之后启动直接走 java -cp（见 ServerRunner），不再让 Maven 参与运行时。
///
/// 判定原则是「宁可多编一次，绝不跑旧代码」：任何一项对不上就重编，
/// 而编译失败、被中途杀掉时不会留下凭证，所以下一次启动一定会重来。
/// </summary>
public static class ServerBuild
{
    private static readonly TimeSpan BuildTimeout = TimeSpan.FromMinutes(10);

    // ── 判定 ────────────────────────────────────────────────────────────

    /// <summary>编译产物是否可以直接用；不能则给出人能看懂的原因。</summary>
    public static bool NeedsBuild(JdkInfo jdk, out string reason)
    {
        // 便宜的检查放前面：常见路径（产物齐全）要走完全部检查才能跳过编译，
        // 所以顺序按「一次文件系统调用」到「git 状态扫描」排列。
        if (!File.Exists(AppPaths.ServerClasspathFile))
        {
            reason = "no classpath snapshot";
            return true;
        }

        if (!Directory.Exists(AppPaths.ServerClassesDir))
        {
            reason = "compiled classes are missing";
            return true;
        }

        if (!File.Exists(AppPaths.ServerMainClassFile))
        {
            reason = "main class was not compiled";
            return true;
        }

        var stamp = ReadStamp();
        if (stamp is null)
        {
            reason = "no build record";
            return true;
        }

        if (!string.Equals(stamp.PomHash, HashFile(AppPaths.PomFile), StringComparison.OrdinalIgnoreCase))
        {
            reason = "pom.xml changed";
            return true;
        }

        if (!string.Equals(stamp.JdkVersion, jdk.Version, StringComparison.OrdinalIgnoreCase))
        {
            reason = $"JDK changed ({stamp.JdkVersion} -> {jdk.Version})";
            return true;
        }

        var classCount = CountClasses();
        if (classCount != stamp.ClassCount)
        {
            reason = $"compiled output incomplete ({classCount}/{stamp.ClassCount} classes)";
            return true;
        }

        var head = GitSource.TryReadHead();
        if (head is null)
        {
            reason = "git HEAD is unreadable";
            return true;
        }

        if (!string.Equals(stamp.Sha, head.Sha, StringComparison.OrdinalIgnoreCase))
        {
            reason = $"source updated ({Short(stamp.Sha)} -> {head.ShortSha})";
            return true;
        }

        var work = GitSource.ReadWorkTree();
        if (!string.Equals(stamp.WorkTreeHash, work.Fingerprint, StringComparison.OrdinalIgnoreCase))
        {
            reason = work.Dirty ? "local source modifications" : "working tree changed";
            return true;
        }

        var missing = FirstMissingDependency();
        if (missing is not null)
        {
            reason = "dependency missing: " + Path.GetFileName(missing);
            return true;
        }

        reason = $"sources unchanged ({head.ShortSha}, {(work.Dirty ? "local edits built" : "clean")})";
        return false;
    }

    /// <summary>判定 + 按需编译。force 用于界面上的「重新编译」。</summary>
    public static async Task<BuildOutcome> EnsureAsync(JdkInfo jdk, MavenInfo maven, Action<string> log,
        bool force, CancellationToken ct)
    {
        string reason;
        if (force)
        {
            reason = "rebuild requested";
        }
        else if (!NeedsBuild(jdk, out reason))
        {
            return new BuildOutcome(false, reason, TimeSpan.Zero);
        }

        return await BuildAsync(jdk, maven, reason, log, ct);
    }

    // ── 编译 ────────────────────────────────────────────────────────────

    public static async Task<BuildOutcome> BuildAsync(JdkInfo jdk, MavenInfo maven, string reason,
        Action<string> log, CancellationToken ct)
    {
        var sw = Stopwatch.StartNew();
        ResetArtifacts(log);

        await RunMavenAsync(jdk, maven, "compile", log, "compile", ct);

        var classpathGoals = "dependency:build-classpath -DincludeScope=runtime" +
                             " -Dmdep.outputFile=\"" + AppPaths.ServerClasspathFile + "\"";
        await RunMavenAsync(jdk, maven, classpathGoals, log, "resolve dependencies", ct);

        Validate();
        WriteStamp(jdk);
        return new BuildOutcome(true, reason, sw.Elapsed);
    }

    /// <summary>
    /// 清空产物：先删凭证再删 class，顺序不能反。
    /// 中途崩溃时留在磁盘上的组合必须是「没有凭证 + 不完整产物」（下次必然重编），
    /// 而不是「有凭证 + 半成品」。
    ///
    /// 为什么整个目录删掉而不做增量：Kotlin 的 maven 插件本来就不做增量
    /// （日志里 compilerMode=NON_INCREMENTAL_COMPILER），每次全量重写；
    /// 而 OpcodeManager 用 ClassGraph 扫描 bnbemu.handler / bnbemu.packet 自动注册，
    /// 源文件被删/改名后残留的旧 class 会变成幽灵 handler，甚至直接抛 opcode 冲突。
    /// 347 个文件的删除只要几十毫秒，换干净的产物完全值得。
    /// </summary>
    private static void ResetArtifacts(Action<string> log)
    {
        try { File.Delete(AppPaths.BuildStampFile); } catch { }

        var dir = AppPaths.ServerClassesDir;
        if (!Directory.Exists(dir)) return;

        log($"Clearing {dir}");
        try
        {
            Directory.Delete(dir, recursive: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            throw new InvalidOperationException(
                $"Cannot clear {dir} — stop the running server (or the IDE run) and try again.", ex);
        }
    }

    /// <summary>
    /// 跑一次 mvn。默认先试离线（省掉仓库元数据检查），只有失败原因确实是「离线」时才联网重试，
    /// 否则编译错误会被白跑两遍。
    /// </summary>
    private static async Task RunMavenAsync(JdkInfo jdk, MavenInfo maven, string goals,
        Action<string> log, string phase, CancellationToken ct)
    {
        var comspec = Environment.GetEnvironmentVariable("COMSPEC") ?? "cmd.exe";
        var env = BuildMvnEnvironment(jdk, maven);
        var lines = new List<string>();

        void OnLine(string line)
        {
            lock (lines)
            {
                lines.Add(line);
                if (lines.Count > 500) lines.RemoveAt(0);
            }

            log(line);
        }

        log($"{phase}: mvn -o {goals}");
        var code = await ProcessUtil.RunStreamingAsync(comspec, BuildMvnArguments(maven, "-o " + goals),
            AppPaths.SourceDir, env, OnLine, BuildTimeout, ct);

        if (code != 0 && LooksOffline(lines))
        {
            log("offline build failed (missing artifacts), retrying with network access…");
            lock (lines) lines.Clear();
            code = await ProcessUtil.RunStreamingAsync(comspec, BuildMvnArguments(maven, goals),
                AppPaths.SourceDir, env, OnLine, BuildTimeout, ct);
        }

        if (code != 0)
        {
            string tail;
            lock (lines) tail = string.Join(Environment.NewLine, lines.TakeLast(12));
            throw new InvalidOperationException($"Maven {phase} failed (exit {code}).{Environment.NewLine}{tail}");
        }
    }

    private static bool LooksOffline(List<string> lines)
    {
        lock (lines)
        {
            return lines.Any(l =>
                l.Contains("offline", StringComparison.OrdinalIgnoreCase)
                || l.Contains("Cannot access", StringComparison.OrdinalIgnoreCase)
                || l.Contains("has not been downloaded", StringComparison.OrdinalIgnoreCase));
        }
    }

    /// <summary>编译完的最后一道关：产物真的能拿来启动。</summary>
    private static void Validate()
    {
        if (!File.Exists(AppPaths.ServerMainClassFile))
            throw new InvalidOperationException(
                $"Build finished but {AppPaths.ServerMainClass} is missing — check the Maven output above.");

        if (!File.Exists(AppPaths.ServerClasspathFile))
            throw new InvalidOperationException("Build finished but the classpath snapshot is missing.");

        var classpath = File.ReadAllText(AppPaths.ServerClasspathFile).Trim();
        if (classpath.Length == 0)
            throw new InvalidOperationException("Build finished but the classpath snapshot is empty.");

        var missing = FirstMissingDependency();
        if (missing is not null)
            throw new InvalidOperationException($"Resolved dependency is not on disk: {missing}");
    }

    // ── 凭证 ────────────────────────────────────────────────────────────

    public static BuildStamp? ReadStamp()
    {
        if (!File.Exists(AppPaths.BuildStampFile)) return null;

        var stamp = JsonStore.Load(AppPaths.BuildStampFile, () => new BuildStamp());
        return string.IsNullOrEmpty(stamp.Sha) ? null : stamp;
    }

    private static void WriteStamp(JdkInfo jdk)
    {
        var work = GitSource.ReadWorkTree();
        var stamp = new BuildStamp
        {
            Sha = GitSource.TryReadHead()?.Sha,
            Dirty = work.Dirty,
            WorkTreeHash = work.Fingerprint,
            PomHash = HashFile(AppPaths.PomFile),
            JdkVersion = jdk.Version,
            KotlinVersion = ReadKotlinVersion(),
            ClassCount = CountClasses(),
            BuiltAt = DateTime.Now.ToString("O"),
        };

        Directory.CreateDirectory(AppPaths.BuildDir);

        // 先写临时文件再改名：避免中途被杀留下半截 JSON（坏凭证比没有凭证更危险）
        var temp = AppPaths.BuildStampFile + ".tmp";
        var json = JsonSerializer.Serialize(stamp, LauncherJsonContext.Default.BuildStamp);
        File.WriteAllText(temp, json);
        File.Move(temp, AppPaths.BuildStampFile, overwrite: true);
    }

    // ── 运行时用的 classpath ────────────────────────────────────────────

    /// <summary>target\classes + 依赖 jar，直接喂给 java -cp。</summary>
    public static string ClasspathArgument()
    {
        var deps = File.Exists(AppPaths.ServerClasspathFile)
            ? File.ReadAllText(AppPaths.ServerClasspathFile).Trim()
            : "";
        return deps.Length == 0 ? AppPaths.ServerClassesDir : AppPaths.ServerClassesDir + ";" + deps;
    }

    // ── mvn 的命令行（SelfTest 的探针也用这套，保证自检和真实编译一致）────────

    /// <summary>
    /// cmd 的 /s /c 引号规则：整条命令再包一层引号，mvn 路径单独包引号
    /// （路径里可能有空格）。最终形如：/d /s /c ""C:\...\mvn.cmd" -B …"
    /// </summary>
    public static string BuildMvnArguments(MavenInfo maven, string goals)
        => "/d /s /c \"" + "\"" + maven.MvnCmd + "\" " + goals + "\"";

    public static Dictionary<string, string> BuildMvnEnvironment(JdkInfo jdk, MavenInfo maven)
    {
        var path = Environment.GetEnvironmentVariable("PATH") ?? "";
        return new Dictionary<string, string>
        {
            ["PATH"] = jdk.Home + "\\bin;" + maven.Home + "\\bin;" + path,
            ["JAVA_HOME"] = jdk.Home,
            // 只影响编译进程；服务端的堆由 ServerRunner 自己的 -Xmx 决定
            ["MAVEN_OPTS"] = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Xmx1024m",
            ["MAVEN_SKIP_RC"] = "true",
        };
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    private static int CountClasses()
    {
        try
        {
            return Directory.Exists(AppPaths.ServerClassesDir)
                ? Directory.EnumerateFiles(AppPaths.ServerClassesDir, "*.class", SearchOption.AllDirectories).Count()
                : 0;
        }
        catch
        {
            return 0;
        }
    }

    private static string? FirstMissingDependency()
    {
        try
        {
            if (!File.Exists(AppPaths.ServerClasspathFile)) return "missing";
            var raw = File.ReadAllText(AppPaths.ServerClasspathFile).Trim();
            if (raw.Length == 0) return "empty";

            foreach (var entry in raw.Split(';', StringSplitOptions.RemoveEmptyEntries))
            {
                var path = entry.Trim().Trim('"');
                if (path.Length == 0) continue;
                if (!File.Exists(path)) return path;
            }

            return null;
        }
        catch
        {
            return null; // 读不出来就当没缺失，后面启动失败会有更明确的报错
        }
    }

    private static string HashFile(string path)
    {
        try
        {
            if (!File.Exists(path)) return "";
            using var stream = File.OpenRead(path);
            var hash = SHA256.HashData(stream);
            return Convert.ToHexString(hash)[..16].ToLowerInvariant();
        }
        catch
        {
            return "";
        }
    }

    private static string? ReadKotlinVersion()
    {
        try
        {
            var text = File.ReadAllText(AppPaths.PomFile);
            var match = Regex.Match(text, @"<kotlin\.version>([^<]+)</kotlin\.version>");
            return match.Success ? match.Groups[1].Value.Trim() : null;
        }
        catch
        {
            return null;
        }
    }

    private static string Short(string? sha)
        => string.IsNullOrEmpty(sha) ? "?" : sha.Length > 7 ? sha[..7] : sha;
}
