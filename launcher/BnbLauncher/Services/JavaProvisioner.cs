using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Text.RegularExpressions;
using BnbLauncher.Models;

namespace BnbLauncher.Services;

public sealed record JdkInfo(string Home, string Version, string Vendor, bool IsLocal)
{
    public string Display => $"{Version} · {Vendor}";
}

public static partial class JavaProvisioner
{
    private const string CorrettoUrl = "https://corretto.aws/downloads/latest/amazon-corretto-21-x64-windows-jdk.zip";
    private const string ZuluApi =
        "https://api.azul.com/metadata/v1/zulu/packages?java_version=21&os=windows&arch=x64" +
        "&java_package_type=jdk&archive_type=zip&latest=true&release_status=ga&availability_types=CA" +
        "&crac_supported=false&page_size=1";

    [GeneratedRegex(@"version ""(\d+)(?:\.([0-9._]+))?""")]
    private static partial Regex JavaVersionRegex();

    /// <summary>启动器目录内的 JDK（runtime\jdk）。</summary>
    public static JdkInfo? GetLocal()
    {
        var java = AppPaths.JavaExe;
        if (!File.Exists(java)) return null;
        if (!File.Exists(Path.Combine(AppPaths.JdkDir, "bin", "javac.exe"))) return null;

        var (version, vendor) = ReadReleaseFile(AppPaths.JdkDir);
        version ??= RunJavaVersion(java) ?? "21";
        vendor ??= "Bundled JDK";
        return new JdkInfo(AppPaths.JdkDir, version, vendor, true);
    }

    /// <summary>
    /// 系统已有的 JDK（JAVA_HOME 或 PATH）：必须是完整 JDK（有 javac）且 21+，
    /// 否则宁可自己下载一份，避免 JRE / javapath 之类的假目录导致 Maven 编译失败。
    /// </summary>
    public static JdkInfo? GetSystem()
    {
        var candidates = new List<string>();

        var javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrWhiteSpace(javaHome)) candidates.Add(javaHome);

        var pathVar = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (var dir in pathVar.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            candidates.Add(dir);
        }

        foreach (var dir in candidates)
        {
            var root = dir.Trim('"', ' ');
            var exe = Path.Combine(root, "bin", "java.exe");
            if (!File.Exists(exe)) exe = Path.Combine(root, "java.exe");
            if (!File.Exists(exe)) continue;

            var binDir = Path.GetDirectoryName(exe)!;
            if (!File.Exists(Path.Combine(binDir, "javac.exe"))) continue;

            var version = RunJavaVersion(exe);
            if (version is null) continue;
            if (!MajorAtLeast(version, 21)) continue;

            var home = Path.GetDirectoryName(binDir) ?? root;
            return new JdkInfo(home, version, "System JDK", false);
        }

        return null;
    }

    public static async Task<JdkInfo> InstallAsync(string vendor, IProgress<SetupProgress>? progress,
        CancellationToken ct = default)
    {
        var isZulu = string.Equals(vendor, "Zulu", StringComparison.OrdinalIgnoreCase);
        progress?.Report(SetupProgress.Indeterminate("Resolving JDK download URL"));

        var url = isZulu ? await ResolveZuluUrlAsync(ct) : CorrettoUrl;
        var zipPath = Path.Combine(AppPaths.CacheDir, isZulu ? "jdk-zulu21.zip" : "jdk-corretto21.zip");

        // 命中缓存只可能是上次装到一半失败了（成功路径会把包删掉），否则一律重新下载
        if (!File.Exists(zipPath) || new FileInfo(zipPath).Length < 20L * 1024 * 1024)
        {
            progress?.Report(new SetupProgress("Downloading JDK 21", 0));
            var inner = new Progress<DownloadProgress>(p =>
            {
                var pct = p.Total > 0 ? p.Percent : -1;
                progress?.Report(new SetupProgress($"Downloading JDK 21 ({TimeText.Size(p.Bytes)} / {TimeText.Size(p.Total)})", pct));
            });
            await Downloader.DownloadAsync(url, zipPath, inner, ct);
        }

        var temp = Path.Combine(AppPaths.CacheDir, "jdk-extract-" + Guid.NewGuid().ToString("N")[..6]);
        try
        {
            progress?.Report(SetupProgress.Indeterminate("Extracting JDK"));
            var inner = new Progress<double>(p => progress?.Report(new SetupProgress("Extracting JDK", p)));
            await ZipHelper.ExtractAsync(zipPath, temp, stripSingleRoot: true, inner, ct);

            if (!File.Exists(Path.Combine(temp, "bin", "java.exe")))
                throw new InvalidOperationException("Unexpected JDK archive contents: bin\\java.exe is missing");

            progress?.Report(SetupProgress.Indeterminate("Installing JDK"));
            await Task.Run(() => ReplaceDirectory(temp, AppPaths.JdkDir), ct);
        }
        finally
        {
            ZipHelper.TryDeleteDirectory(temp);
        }

        var info = GetLocal() ?? throw new InvalidOperationException("JDK verification failed after installation");

        // 解压且校验通过，安装包（~194MB）已经没用了，立即丢弃
        ZipHelper.DiscardArchive(zipPath);

        progress?.Report(new SetupProgress($"JDK {info.Version} ready", 100));
        return info;
    }

    private static async Task<string> ResolveZuluUrlAsync(CancellationToken ct)
    {
        try
        {
            var json = await Downloader.Http.GetStringAsync(ZuluApi, ct);
            var packages = JsonSerializer.Deserialize(json, LauncherJsonContext.Default.AzulPackageDtoArray);
            var url = packages?.FirstOrDefault()?.DownloadUrl;
            if (!string.IsNullOrWhiteSpace(url)) return url;
        }
        catch
        {
            // 落到固定版本兜底
        }

        throw new InvalidOperationException("Could not get the Zulu JDK download URL from Azul. Choose Amazon Corretto instead.");
    }

    /// <summary>把 src 目录替换到 dest（dest 已存在时先删）。</summary>
    public static void ReplaceDirectory(string src, string dest)
    {
        if (Directory.Exists(dest))
        {
            Directory.Delete(dest, recursive: true);
        }
        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
        Directory.Move(src, dest);
    }

    public static bool MajorAtLeast(string version, int major)
    {
        var head = version.Split('.')[0];
        return int.TryParse(head, out var v) && v >= major;
    }

    private static string? RunJavaVersion(string javaExe)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = javaExe,
                Arguments = "-version",
                RedirectStandardError = true,
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                StandardErrorEncoding = System.Text.Encoding.UTF8,
            };
            using var proc = Process.Start(psi);
            if (proc is null) return null;
            var text = proc.StandardError.ReadToEnd();
            proc.WaitForExit(8000);
            var m = JavaVersionRegex().Match(text);
            if (!m.Success) return null;
            var minor = m.Groups[2].Success ? "." + m.Groups[2].Value : "";
            return m.Groups[1].Value + minor;
        }
        catch
        {
            return null;
        }
    }

    private static (string? Version, string? Vendor) ReadReleaseFile(string home)
    {
        try
        {
            var release = Path.Combine(home, "release");
            if (!File.Exists(release)) return (null, null);

            string? version = null, implementor = null;
            foreach (var line in File.ReadAllLines(release))
            {
                if (line.StartsWith("JAVA_VERSION=", StringComparison.Ordinal))
                    version = line.Split('=', 2)[1].Trim().Trim('"');
                else if (line.StartsWith("IMPLEMENTOR=", StringComparison.Ordinal))
                    implementor = line.Split('=', 2)[1].Trim().Trim('"');
            }

            var vendor = implementor switch
            {
                null => null,
                var s when s.Contains("Amazon", StringComparison.OrdinalIgnoreCase) => "Amazon Corretto",
                var s when s.Contains("Azul", StringComparison.OrdinalIgnoreCase) => "Azul Zulu",
                var s when s.Contains("Eclipse", StringComparison.OrdinalIgnoreCase) => "Eclipse Temurin",
                _ => implementor,
            };
            return (version, vendor);
        }
        catch
        {
            return (null, null);
        }
    }
}
