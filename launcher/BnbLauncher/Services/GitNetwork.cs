using System.IO;
using System.Net;
using BnbLauncher.Models;
using LibGit2Sharp;
using LibGit2Sharp.Handlers;

namespace BnbLauncher.Services;

/// <summary>
/// libgit2 的网络细节：代理与凭据。
///
/// 代理必须**显式**下发给 libgit2（ProxyType.Specified / None），原因有两个：
///   1. libgit2 默认自己读 git 全局配置的 http.proxy，而国内常见的
///      socks5h://127.0.0.1:1080 它并不支持，会直接抛
///      "unknown http scheme 'socks5h'"，clone/fetch 全部失败；
///   2. .NET 的 HttpClient 走 WinINET / 环境变量代理，libgit2 完全不认，
///      两套下载逻辑必须在这里对齐。
///
/// 解析顺序：设置里的 proxy → 环境变量 → 系统代理 → git 全局配置 → 直连。
/// 只接受 http/https（libgit2 这个构建不支持 SOCKS），遇到 socks 就明确降级为直连并给出提示。
/// </summary>
internal static class GitNetwork
{
    private static readonly string[] EnvNames =
    [
        "HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy",
    ];

    private static readonly object SystemProxyGate = new();
    private static bool _systemProxyProbed;
    private static string? _systemProxy;

    /// <summary>给日志/自检用的一句话网络说明。</summary>
    public static string Note(AppConfig cfg)
    {
        var (url, note) = Resolve(cfg);
        return url is null ? note : $"{note} {url}";
    }

    /// <summary>解析出来的 http/https 代理；直连时为 null。Downloader 用它给 HttpClient 设代理。</summary>
    public static string? ProxyUrl(AppConfig cfg) => Resolve(cfg).Url;

    public static ProxyOptions ProxyOptions(AppConfig cfg)
    {
        var (url, _) = Resolve(cfg);
        return url is null
            ? new ProxyOptions { ProxyType = ProxyType.None }
            : new ProxyOptions { ProxyType = ProxyType.Specified, Url = url };
    }

    /// <summary>FetchOptions.ProxyOptions 是只读属性（实例可变），只能逐字段拷进去。</summary>
    public static void ApplyProxy(FetchOptions options, AppConfig cfg)
    {
        var proxy = ProxyOptions(cfg);
        options.ProxyOptions.ProxyType = proxy.ProxyType;
        options.ProxyOptions.Url = proxy.Url;
    }

    public static CredentialsHandler Credentials(AppConfig cfg) => (_, _, _) =>
    {
        var token = cfg.GithubToken?.Trim();
        if (!string.IsNullOrEmpty(token))
        {
            // GitHub 的 HTTPS 令牌：用户名可任意，密码用 token
            return new UsernamePasswordCredentials { Username = "x-access-token", Password = token };
        }

        return new DefaultCredentials();
    };

    // ── 代理解析 ────────────────────────────────────────────────────────

    private static (string? Url, string Note) Resolve(AppConfig cfg)
    {
        if (!string.IsNullOrWhiteSpace(cfg.Proxy))
            return Normalize(cfg.Proxy, "proxy");

        foreach (var name in EnvNames)
        {
            var value = Environment.GetEnvironmentVariable(name);
            if (!string.IsNullOrWhiteSpace(value)) return Normalize(value, "proxy (" + name + ")");
        }

        var system = SystemProxy();
        if (!string.IsNullOrEmpty(system)) return Normalize(system, "system proxy");

        var git = GitConfigProxy();
        if (!string.IsNullOrEmpty(git)) return Normalize(git, "git config http.proxy");

        return (null, "Network: direct");
    }

    private static (string? Url, string Note) Normalize(string raw, string source)
    {
        var value = raw.Trim().Trim('"').Trim();
        if (value.Length == 0) return (null, "Network: direct");

        if (!value.Contains("://", StringComparison.Ordinal)) value = "http://" + value;

        if (value.StartsWith("socks5h://", StringComparison.OrdinalIgnoreCase)
            || value.StartsWith("socks5://", StringComparison.OrdinalIgnoreCase)
            || value.StartsWith("socks4", StringComparison.OrdinalIgnoreCase))
        {
            return (null, $"Network: direct ({source} is a SOCKS proxy, which libgit2 does not support; ignored)");
        }

        if (!value.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
            && !value.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            return (null, $"Network: direct ({source} scheme is unsupported; ignored)");
        }

        return (value.TrimEnd('/'), "Network: " + source);
    }

    private static string? SystemProxy()
    {
        lock (SystemProxyGate)
        {
            if (_systemProxyProbed) return _systemProxy;
            _systemProxyProbed = true;
            try
            {
                var proxy = WebRequest.DefaultWebProxy;
                if (proxy is null) return null;

                var probe = new Uri("https://github.com");
                if (proxy.IsBypassed(probe)) return null;

                var uri = proxy.GetProxy(probe);
                if (uri is null || uri == probe) return null;
                if (uri.Host is "127.0.0.1" or "localhost" && uri.Port == 0) return null;

                _systemProxy = uri.ToString();
            }
            catch
            {
                _systemProxy = null;
            }

            return _systemProxy;
        }
    }

    /// <summary>极简 .gitconfig 读取：只找 [http] 段的 proxy，够用且不用起临时仓库。</summary>
    private static string? GitConfigProxy()
    {
        try
        {
            var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            if (string.IsNullOrEmpty(home)) return null;

            var path = Path.Combine(home, ".gitconfig");
            if (!File.Exists(path)) return null;

            var section = "";
            foreach (var rawLine in File.ReadLines(path))
            {
                var line = rawLine.Trim();
                if (line.Length == 0 || line[0] is '#' or ';') continue;

                if (line[0] == '[')
                {
                    var end = line.IndexOf(']');
                    section = (end > 1 ? line[1..end] : "").Split(' ', '"')[0].Trim();
                    continue;
                }

                if (!section.Equals("http", StringComparison.OrdinalIgnoreCase)) continue;

                var eq = line.IndexOf('=');
                if (eq <= 0) continue;
                if (!line[..eq].Trim().Equals("proxy", StringComparison.OrdinalIgnoreCase)) continue;

                var value = line[(eq + 1)..].Trim();
                return value.Length == 0 ? null : value;
            }
        }
        catch
        {
            // 读配置失败就当没配，不影响直连
        }

        return null;
    }
}
