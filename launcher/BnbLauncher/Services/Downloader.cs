using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using BnbLauncher.Models;

namespace BnbLauncher.Services;

public readonly record struct DownloadProgress(long Bytes, long Total)
{
    public double Percent => Total > 0 ? Math.Clamp(Bytes * 100d / Total, 0, 100) : 0;
}

public static class Downloader
{
    public const string UserAgent = "BnbLauncher/1.0 (+https://github.com/krrr/BnbEmu)";

    private static readonly object Gate = new();
    private static HttpClient? _http;
    private static string? _proxyUrl;

    public static HttpClient Http
    {
        get
        {
            lock (Gate) return _http ??= Build(_proxyUrl);
        }
    }

    /// <summary>
    /// 把设置里的代理同步给 HttpClient。
    ///
    /// 为什么必须显式做：HttpClientHandler 默认只认系统代理 / WinINET，**不读**环境变量，
    /// 也不认 launcher.config.json 里的 proxy。不然就会出现「git 走代理能拉源码、下载却全超时」
    /// 这种自相矛盾的现象。这里复用 GitNetwork 的解析结果（设置 → 环境变量 → 系统代理 → git 配置），
    /// 保证两条网络路径用的是同一份配置。
    ///
    /// 参数没变就直接返回；变了换一个新实例，旧实例故意不 Dispose——
    /// 设置页保存的那一刻可能正有下载在飞，Dispose 会把它掐断。
    /// </summary>
    public static void Configure(AppConfig cfg)
    {
        var url = GitNetwork.ProxyUrl(cfg);
        lock (Gate)
        {
            if (url == _proxyUrl && _http is not null) return;
            _proxyUrl = url;
            _http = Build(url);
        }
    }

    private static HttpClient Build(string? proxyUrl)
    {
        var handler = new HttpClientHandler
        {
            AllowAutoRedirect = true,
            MaxAutomaticRedirections = 10,
            AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate,
        };

        if (!string.IsNullOrEmpty(proxyUrl))
        {
            handler.Proxy = new WebProxy(proxyUrl);
            handler.UseProxy = true;
        }

        var client = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
        client.DefaultRequestHeaders.UserAgent.ParseAdd(UserAgent);
        return client;
    }

    /// <summary>
    /// 下载到 destPath（先写 .part 再改名，避免半截文件）。
    /// resume = true 时，如果 .part 还在就发 Range 续传：启动器那个 59MB 的 exe 掉线重下太疼。
    /// </summary>
    public static async Task DownloadAsync(string url, string destPath, IProgress<DownloadProgress>? progress,
        CancellationToken ct = default, bool resume = false)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(destPath)!);
        var partPath = destPath + ".part";

        long already = 0;
        if (resume && File.Exists(partPath))
        {
            already = new FileInfo(partPath).Length;
        }

        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        if (already > 0) request.Headers.Range = new RangeHeaderValue(already, null);

        using var resp = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);

        // 断点已经超过远端文件长度（换版本了 / 文件被替换）：丢掉重来一次，别卡死在这儿
        if (resp.StatusCode == HttpStatusCode.RequestedRangeNotSatisfiable && already > 0)
        {
            try { File.Delete(partPath); } catch { }
            await DownloadAsync(url, destPath, progress, ct, resume: false);
            return;
        }

        if (!resp.IsSuccessStatusCode)
        {
            throw new InvalidOperationException($"Download failed: HTTP {(int)resp.StatusCode} — {url}");
        }

        // 服务端不认 Range（回了 200 全文）时只能从头写，否则会拼出一个坏文件
        if (already > 0 && resp.StatusCode != HttpStatusCode.PartialContent) already = 0;

        var contentLength = resp.Content.Headers.ContentLength ?? -1;
        var total = contentLength > 0 ? contentLength + already : -1;

        await using (var src = await resp.Content.ReadAsStreamAsync(ct))
        await using (var dst = new FileStream(partPath, already > 0 ? FileMode.Append : FileMode.Create,
                         FileAccess.Write, FileShare.None, 1 << 16, true))
        {
            var buffer = new byte[1 << 16];
            long read = 0;
            int n;
            var lastReport = 0L;
            while ((n = await src.ReadAsync(buffer, ct)) > 0)
            {
                await dst.WriteAsync(buffer.AsMemory(0, n), ct);
                read += n;
                if (read - lastReport >= 256 * 1024 || (total > 0 && already + read >= total))
                {
                    lastReport = read;
                    progress?.Report(new DownloadProgress(already + read, total));
                }
            }
            progress?.Report(new DownloadProgress(already + read, total > 0 ? total : already + read));
        }

        if (File.Exists(destPath)) File.Delete(destPath);
        File.Move(partPath, destPath);
    }
}
