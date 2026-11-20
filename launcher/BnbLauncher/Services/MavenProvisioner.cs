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

    /// <summary>
    /// 简体中文系统的优先源。dlcdn 对国内出口常年只有几十 KB/s（实测 22 KB/s，10MB 要七八分钟），
    /// 而这两个都是 Apache dist 的完整镜像，目录索引和 binaries 布局跟 dlcdn 逐字一致，
    /// 所以换源只是换前缀。顺序按实测吞吐排：腾讯 8.8 MB/s 远快于阿里 0.25 MB/s。
    /// 非简体中文系统不走这条——海外访问 dlcdn 本来就快，没必要多绕一跳。
    /// </summary>
    private static readonly string[] ChinaMirrors =
    [
        "https://mirrors.cloud.tencent.com/apache/maven/maven-3/",
        "https://mirrors.aliyun.com/apache/maven/maven-3/",
    ];

    private static readonly string[] PinnedFallback = ["3.9.11", "3.9.9", "3.9.6"];

    /// <summary>
    /// 索引 / HEAD 探测的超时。Downloader 的 HttpClient 是无限超时（大文件续传需要），
    /// 探测必须自己掐表，否则镜像被墙时会一直挂着，永远轮不到后面的降级源。
    /// </summary>
    private static readonly TimeSpan ProbeTimeout = TimeSpan.FromSeconds(12);

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
            // 把源写进进度文案：国内镜像 / dlcdn 的差别以后出现在用户截图里就能直接定位
            var host = new Uri(baseUrl).Host;
            progress?.Report(new SetupProgress($"Downloading Maven {version} from {host}", 0));
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

    /// <summary>
    /// 挑一个「能用的最新 3.9.x」，按「简体中文系统先国内镜像 → 官方 CDN → 写死的旧版本走 archive」
    /// 的顺序降级。每一档失败都只是换下一档，不会抛出去。
    /// </summary>
    private static async Task<(string Version, string BaseUrl)> ResolveLatestAsync(CancellationToken ct)
    {
        if (SystemLocale.IsSimplifiedChinese)
        {
            foreach (var index in ChinaMirrors)
            {
                // 镜像这一档要 HEAD 验证：索引里列着版本、binaries 目录却没同步完
                //（或者旧版本被镜像清掉了）是真实存在的情况，别把 404 交给下载器。
                if (await TryResolveFromIndexAsync(index, verifyZip: true, ProbeTimeout, ct) is { } mirror)
                    return mirror;
            }
        }

        // 官方 CDN 不掐表：它是原来唯一的路，索引慢但文件快的情况真实存在（实测索引 0.3s~11s 抖动），
        // 超时反而会把它误判成不可用、退到 archive 那个 11 KB/s 的坑里。
        if (await TryResolveFromIndexAsync(DlcdnIndex, verifyZip: false, probeTimeout: null, ct) is { } official)
            return official;

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

    /// <summary>
    /// 从某个 dist 源（dlcdn / 腾讯 / 阿里，布局完全一样）的目录索引里取最新 3.9.x。
    /// 拿到版本后可选地用 HEAD 确认 zip 真的在，任何失败都返回 null 表示「这个源不能用」。
    /// probeTimeout = null 表示不额外掐表（沿用下载器自己的无限超时）。
    /// </summary>
    private static async Task<(string Version, string BaseUrl)?> TryResolveFromIndexAsync(
        string indexUrl, bool verifyZip, TimeSpan? probeTimeout, CancellationToken ct)
    {
        CancellationTokenSource? timeout = null;
        if (probeTimeout is { } limit)
        {
            timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(limit);
        }

        var token = timeout?.Token ?? ct;

        try
        {
            var html = await Downloader.Http.GetStringAsync(indexUrl, token);
            var versions = IndexRegex().Matches(html)
                .Select(m => m.Groups[1].Value)
                .Distinct()
                .Select(v => (Text: v, Parsed: Version.TryParse(v, out var pv) ? pv : null))
                .Where(x => x.Parsed is not null)
                .OrderByDescending(x => x.Parsed)
                .Select(x => x.Text)
                .ToList();

            if (versions.Count == 0) return null;

            var version = versions[0];
            var baseUrl = $"{indexUrl}{version}/binaries";

            if (verifyZip)
            {
                var zipUrl = $"{baseUrl}/apache-maven-{version}-bin.zip";
                using var head = await Downloader.Http.SendAsync(new HttpRequestMessage(HttpMethod.Head, zipUrl), token);
                // 405 = 这个 CDN 不支持 HEAD，但不代表文件不存在，不能因此把好源丢掉
                if (!head.IsSuccessStatusCode && head.StatusCode != System.Net.HttpStatusCode.MethodNotAllowed)
                    return null;
            }

            return (version, baseUrl);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw; // 用户取消：别当成「这个源不可用」继续降级
        }
        catch (OperationCanceledException)
        {
            return null; // 探测超时：当这个源不可用，继续降级
        }
        catch
        {
            return null;
        }
        finally
        {
            timeout?.Dispose();
        }
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
