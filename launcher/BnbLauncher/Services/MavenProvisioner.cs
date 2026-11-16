using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text.RegularExpressions;
using BnbLauncher.Models;

namespace BnbLauncher.Services;

public sealed record MavenInfo(string Home, string Version, bool IsLocal)
{
    public string Display => Version;
    public string MvnCmd => Path.Combine(Home, "bin", "mvn.cmd");
}

public static partial class MavenProvisioner
{
    private const string DlcdnIndex = "https://dlcdn.apache.org/maven/maven-3/";
    private static readonly string[] PinnedFallback = ["3.9.11", "3.9.9", "3.9.6"];

    [GeneratedRegex(@"href=""(3\.9\.\d+)/""")]
    private static partial Regex IndexRegex();

    [GeneratedRegex(@"Apache Maven (\d+\.\d+\.\d+)")]
    private static partial Regex VersionRegex();

    public static MavenInfo? GetLocal()
    {
        var cmd = AppPaths.MvnCmd;
        if (!File.Exists(cmd)) return null;
        var version = ReadVersionFromBanner(Path.Combine(AppPaths.MavenDir, "lib")) ?? "3.9.x";
        return new MavenInfo(AppPaths.MavenDir, version, true);
    }

    public static async Task<MavenInfo?> GetSystemAsync(string? javaHome = null, CancellationToken ct = default)
    {
        var cmd = FindOnPath("mvn.cmd") ?? FindOnPath("mvn.bat");
        if (cmd is null) return null;

        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = Environment.GetEnvironmentVariable("COMSPEC") ?? "cmd.exe",
                Arguments = "/d /s /c \"\"" + cmd + "\" -v\"",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                StandardOutputEncoding = System.Text.Encoding.UTF8,
            };

            // mvn 需要一个可用的 JAVA_HOME；这里用我们已确定的 JDK 探测，避免被系统的失效 JRE 干扰
            if (!string.IsNullOrWhiteSpace(javaHome))
            {
                var path = psi.Environment.TryGetValue("PATH", out var existing) ? existing : "";
                psi.Environment["JAVA_HOME"] = javaHome;
                psi.Environment["PATH"] = javaHome + "\\bin;" + path;
            }

            using var proc = Process.Start(psi);
            if (proc is null) return null;
            var text = await proc.StandardOutput.ReadToEndAsync(ct) + await proc.StandardError.ReadToEndAsync(ct);
            if (!proc.WaitForExit(15000))
            {
                try { proc.Kill(true); } catch { }
                return null;
            }

            var m = VersionRegex().Match(text);
            if (!m.Success) return null;
            var version = m.Groups[1].Value;
            if (Version.TryParse(version, out var v) && v < new Version(3, 6, 9)) return null;

            var home = Path.GetFullPath(Path.Combine(Path.GetDirectoryName(cmd)!, ".."));
            return new MavenInfo(home, version, false);
        }
        catch
        {
            return null;
        }
    }

    public static async Task<MavenInfo> InstallAsync(IProgress<SetupProgress>? progress,
        CancellationToken ct = default)
    {
        progress?.Report(SetupProgress.Indeterminate("Resolving Maven version"));

        var (version, baseUrl) = await ResolveLatestAsync(ct);
        var zipPath = Path.Combine(AppPaths.CacheDir, $"apache-maven-{version}-bin.zip");

        // 命中缓存只可能是上次装到一半失败了（成功路径会把包删掉），否则一律重新下载
        if (!File.Exists(zipPath) || new FileInfo(zipPath).Length < 1024 * 1024)
        {
            progress?.Report(new SetupProgress($"Downloading Maven {version}", 0));
            var inner = new Progress<DownloadProgress>(p => progress?.Report(new SetupProgress(
                $"Downloading Maven {version} ({TimeText.Size(p.Bytes)} / {TimeText.Size(p.Total)})",
                p.Total > 0 ? p.Percent : -1)));
            await Downloader.DownloadAsync($"{baseUrl}/apache-maven-{version}-bin.zip", zipPath, inner, ct);
        }

        var temp = Path.Combine(AppPaths.CacheDir, "mvn-extract-" + Guid.NewGuid().ToString("N")[..6]);
        try
        {
            progress?.Report(SetupProgress.Indeterminate("Extracting Maven"));
            var inner = new Progress<double>(p => progress?.Report(new SetupProgress("Extracting Maven", p)));
            await ZipHelper.ExtractAsync(zipPath, temp, stripSingleRoot: true, inner, ct);

            if (!File.Exists(Path.Combine(temp, "bin", "mvn.cmd")))
                throw new InvalidOperationException("Unexpected Maven archive contents: bin\\mvn.cmd is missing");

            progress?.Report(SetupProgress.Indeterminate("Installing Maven"));
            await Task.Run(() => JavaProvisioner.ReplaceDirectory(temp, AppPaths.MavenDir), ct);
        }
        finally
        {
            ZipHelper.TryDeleteDirectory(temp);
        }

        var info = GetLocal() ?? throw new InvalidOperationException("Maven verification failed after installation");

        // 解压且校验通过，安装包已经没用了，立即丢弃
        ZipHelper.DiscardArchive(zipPath);

        progress?.Report(new SetupProgress($"Maven {info.Version} ready", 100));
        return info;
    }

    private static async Task<(string Version, string BaseUrl)> ResolveLatestAsync(CancellationToken ct)
    {
        try
        {
            var html = await Downloader.Http.GetStringAsync(DlcdnIndex, ct);
            var versions = IndexRegex().Matches(html)
                .Select(m => m.Groups[1].Value)
                .Distinct()
                .Select(v => (Text: v, Parsed: Version.TryParse(v, out var pv) ? pv : null))
                .Where(x => x.Parsed is not null)
                .OrderByDescending(x => x.Parsed)
                .Select(x => x.Text)
                .ToList();

            if (versions.Count > 0)
            {
                var v = versions[0];
                return (v, $"{DlcdnIndex}{v}/binaries");
            }
        }
        catch
        {
            // 走固定版本兜底
        }

        foreach (var v in PinnedFallback)
        {
            var url = $"https://archive.apache.org/dist/maven/maven-3/{v}/binaries/apache-maven-{v}-bin.zip";
            try
            {
                using var head = await Downloader.Http.SendAsync(new HttpRequestMessage(HttpMethod.Head, url), ct);
                if (head.IsSuccessStatusCode) return (v, $"https://archive.apache.org/dist/maven/maven-3/{v}/binaries");
            }
            catch
            {
                // next
            }
        }

        throw new InvalidOperationException("Could not resolve the Maven download URL. Check your network.");
    }

    /// <summary>从 lib/maven-core-x.y.z.jar 文件名里读版本。</summary>
    private static string? ReadVersionFromBanner(string libDir)
    {
        try
        {
            if (!Directory.Exists(libDir)) return null;
            foreach (var file in Directory.EnumerateFiles(libDir, "maven-core-*.jar"))
            {
                var name = Path.GetFileNameWithoutExtension(file); // maven-core-3.9.16
                var idx = name.LastIndexOf('-');
                if (idx > 0) return name[(idx + 1)..];
            }
        }
        catch
        {
            // ignore
        }
        return null;
    }

    private static string? FindOnPath(string fileName)
    {
        var pathVar = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (var dir in pathVar.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var candidate = Path.Combine(dir.Trim('"'), fileName);
                if (File.Exists(candidate)) return candidate;
            }
            catch
            {
                // 非法路径跳过
            }
        }
        return null;
    }
}
