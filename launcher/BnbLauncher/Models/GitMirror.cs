using System;
using System.Collections.Generic;
using System.Linq;

namespace BnbLauncher.Models;

/// <summary>一条 GitHub 下载通道。Prefix 为空表示直连；否则把完整地址拼在它后面。</summary>
public sealed record MirrorOption(string Id, string Label, string Prefix, string Host);

/// <summary>
/// GitHub 访问通道：直连，或经 gh-proxy 反代。
///
/// 为什么要单独抽一层：启动器有两条完全不同的网络路径 ——
///   · libgit2（clone / fetch / ls-remote）走 <see cref="AppConfig.CloneUrl"/>；
///   · HttpClient 下载更新清单和启动器 exe，走 <see cref="Services.LauncherUpdater"/> 里的地址。
/// 两条都必须一起换，否则会出现「源码能拉、更新下载全超时」这种半瘫状态。
///
/// gh-proxy 的用法是把目标地址整段拼在路径里（和 `git clone` 手敲的一样）：
///   https://gh-proxy.org/https://github.com/krrr/BnbEmu.git
///   https://gh-proxy.org/https://github.com/krrr/BnbEmu/releases/download/launcher-v1.1.0/BnbLauncher.exe
/// 所以这里只需要一个前缀 + 一个白名单主机判断，改写是纯字符串操作、不联网。
///
/// 镜像只影响「从哪取字节」，不影响校验：启动器 exe 仍然要过 SHA-256，
/// 源码仍然是 git 对象（commit sha 自带完整性），镜像篡改会被直接拒绝。
/// </summary>
public static class GitMirror
{
    public const string DirectId = "direct";
    public const string GhProxyId = "ghproxy";

    private const string GhProxyHost = "gh-proxy.org";

    /// <summary>可以走反代的 GitHub 主机。只认这几个，避免把任意地址拼到镜像后面。</summary>
    private static readonly string[] GitHubHosts =
    [
        "github.com",
        "raw.githubusercontent.com",
        "codeload.github.com",
    ];

    /// <summary>设置界面下拉框的选项顺序即此处顺序，默认第一项（直连）。</summary>
    public static IReadOnlyList<MirrorOption> Options { get; } =
    [
        new(DirectId, "Direct", "", "github.com"),
        new(GhProxyId, "gh-proxy", $"https://{GhProxyHost}/", GhProxyHost),
    ];

    /// <summary>配置里手写的值可能是大小写混写 / 已被删掉的旧选项，一律收敛到已知 id。</summary>
    public static string Normalize(string? id)
    {
        var value = (id ?? "").Trim();
        return Options.Any(o => string.Equals(o.Id, value, StringComparison.OrdinalIgnoreCase))
            ? value.ToLowerInvariant()
            : DirectId;
    }

    public static MirrorOption Current(string? id)
        => Options.First(o => o.Id == Normalize(id));

    public static bool IsMirror(string? id) => Normalize(id) != DirectId;

    /// <summary>实际会去解析的主机名，错误提示里用它，别让「镜像挂了」报成「github.com 解析失败」。</summary>
    public static string Host(string? id) => Current(id).Host;

    /// <summary>给日志/自检用的一句话说明。</summary>
    public static string Note(string? id)
    {
        var option = Current(id);
        return $"GitHub access: {option.Label} ({option.Host})";
    }

    /// <summary>
    /// 按通道改写一个地址：直连原样返回；镜像则加前缀。
    /// 幂等 —— 已经带前缀的地址（子模块按镜像父仓库拼出来的绝对地址）不会被套第二层。
    /// 非 GitHub 地址（Apache / Azul / Corretto 等）不碰。
    /// </summary>
    public static string Rewrite(string url, string? mirror)
    {
        var option = Current(mirror);
        if (option.Prefix.Length == 0 || string.IsNullOrWhiteSpace(url)) return url;

        var trimmed = url.Trim();
        if (trimmed.StartsWith(option.Prefix, StringComparison.OrdinalIgnoreCase)) return trimmed;
        if (!IsGitHubHosted(trimmed)) return trimmed;

        return option.Prefix + trimmed;
    }

    private static bool IsGitHubHosted(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return false;

        return GitHubHosts.Any(h => string.Equals(uri.Host, h, StringComparison.OrdinalIgnoreCase));
    }
}
