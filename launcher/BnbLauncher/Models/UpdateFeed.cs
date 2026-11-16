using System.Text.Json.Serialization;

namespace BnbLauncher.Models;

/// <summary>
/// 启动器自身的更新清单（发布时生成的 latest.json）。
///
/// 刻意只留「版本 / 从哪下 / 下下来该是什么哈希」三件事：
/// 启动器是单文件 exe，更新就是换一个文件，清单里多一个字段就多一处能出错的地方。
/// 上传位置见 launcher/README.md：版本 release 里一份，滚动 tag `launcher-latest` 里再放一份。
/// </summary>
public sealed class UpdateFeed
{
    /// <summary>最新版本，SemVer 三段（1.2.0），可带 -beta.1 这类预发布后缀。</summary>
    [JsonPropertyName("version")] public string Version { get; set; } = "";

    /// <summary>
    /// 允许的最低版本。当前版本低于它时强制更新（旧版的引导逻辑可能已经跟不上服务端）。
    /// 留空或 0.0.0 表示不强制。
    /// </summary>
    [JsonPropertyName("minVersion")] public string MinVersion { get; set; } = "";

    /// <summary>更新说明，横幅上显示第一行。</summary>
    [JsonPropertyName("notes")] public string Notes { get; set; } = "";

    /// <summary>Release 页面地址，给「查看详情」用。</summary>
    [JsonPropertyName("page")] public string Page { get; set; } = "";

    [JsonPropertyName("exe")] public UpdateAsset? Exe { get; set; }
}

public sealed class UpdateAsset
{
    [JsonPropertyName("url")] public string Url { get; set; } = "";

    /// <summary>字节数，用于下载前一目了然 + 下载后核对长度。</summary>
    [JsonPropertyName("size")] public long Size { get; set; }

    /// <summary>SHA-256（大小写皆可，也接受 `sha256:` 前缀）。为空则拒绝更新。</summary>
    [JsonPropertyName("sha256")] public string Sha256 { get; set; } = "";
}

/// <summary>
/// 替换排期，写在 runtime\update\pending.json。
/// 替换发生在启动器进程退出之后（helper 脚本里），所以「有没有生效」只能等下一次启动来确认。
/// </summary>
public sealed class PendingUpdate
{
    [JsonPropertyName("targetVersion")] public string TargetVersion { get; set; } = "";

    [JsonPropertyName("scheduledAt")] public string ScheduledAt { get; set; } = "";

    /// <summary>
    /// 新版本成功跑起来的次数。第一次成功时保留 .prev（留一个回退窗口），
    /// 第二次成功才把它删掉，避免那个 59MB 的旧 exe 一直躺在目录里。
    /// </summary>
    [JsonPropertyName("confirmedRuns")] public int ConfirmedRuns { get; set; }
}
