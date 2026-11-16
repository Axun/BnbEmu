using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace BnbLauncher.Models;

/// <summary>用户可编辑的设置，存放在启动器目录下。</summary>
public sealed class AppConfig
{
    [JsonPropertyName("owner")] public string Owner { get; set; } = "krrr";
    [JsonPropertyName("repo")] public string Repo { get; set; } = "BnbEmu";
    [JsonPropertyName("branch")] public string Branch { get; set; } = "main";

    /// <summary>Corretto | Zulu</summary>
    [JsonPropertyName("jdkVendor")] public string JdkVendor { get; set; } = "Corretto";

    [JsonPropertyName("autoStartServer")] public bool AutoStartServer { get; set; } = true;
    [JsonPropertyName("autoCheckUpdates")] public bool AutoCheckUpdates { get; set; } = true;

    /// <summary>启动时检查启动器自身有没有新版本（发现了只在顶部提示，不会偷偷替换）。</summary>
    [JsonPropertyName("autoCheckLauncher")] public bool AutoCheckLauncher { get; set; } = true;

    /// <summary>
    /// 可选：启动器更新源（latest.json）。留空则按「Release 滚动 tag → 仓库 raw」依次尝试。
    /// 国内网络可以填镜像/反代地址；填了就只认这一个源。
    /// </summary>
    [JsonPropertyName("updateFeedUrl")] public string UpdateFeedUrl { get; set; } = "";

    /// <summary>
    /// 可选：GitHub 个人令牌。公开仓库不用填；私有仓库必须填，
    /// 填了还能避免匿名 HTTPS 拉取偶发被限流。作为 git 的 HTTPS 密码使用（用户名 x-access-token）。
    /// </summary>
    [JsonPropertyName("githubToken")] public string GithubToken { get; set; } = "";

    /// <summary>
    /// 可选：HTTPS 代理，形如 http://127.0.0.1:7890。留空则自动检测
    /// （环境变量 HTTPS_PROXY → 系统代理 → git 全局配置 http.proxy）。
    /// libgit2 只支持 http/https 代理，SOCKS 会被忽略并退化为直连。
    /// </summary>
    [JsonPropertyName("proxy")] public string Proxy { get; set; } = "";

    [JsonIgnore] public string RepoUrl => $"https://github.com/{Owner}/{Repo}";
    [JsonIgnore] public string CloneUrl => $"https://github.com/{Owner}/{Repo}.git";
    [JsonIgnore] public string DisplayName => $"{Owner}/{Repo}";
}

/// <summary>运行时状态（安装版本记录），由启动器维护。</summary>
public sealed class AppState
{
    [JsonPropertyName("installedSha")] public string? InstalledSha { get; set; }
    [JsonPropertyName("installedShortSha")] public string? InstalledShortSha { get; set; }
    [JsonPropertyName("installedMessage")] public string? InstalledMessage { get; set; }
    [JsonPropertyName("installedCommitDate")] public string? InstalledCommitDate { get; set; }
    [JsonPropertyName("installedAt")] public string? InstalledAt { get; set; }
    [JsonPropertyName("jdkVersion")] public string? JdkVersion { get; set; }
    [JsonPropertyName("mavenVersion")] public string? MavenVersion { get; set; }
    [JsonPropertyName("lastCheckUtc")] public string? LastCheckUtc { get; set; }
}

public static class JsonStore
{
    private static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = true,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        TypeInfoResolver = LauncherJsonContext.Default,
    };

    public static T Load<T>(string path, Func<T> fallback) where T : class
    {
        try
        {
            if (!File.Exists(path)) return fallback();
            var json = File.ReadAllText(path);
            if (string.IsNullOrWhiteSpace(json)) return fallback();
            return JsonSerializer.Deserialize(json, typeof(T), LauncherJsonContext.Default) as T ?? fallback();
        }
        catch
        {
            // 配置损坏时不要阻塞启动，直接回退到默认值
            return fallback();
        }
    }

    public static void Save<T>(string path, T value)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var json = JsonSerializer.Serialize(value, typeof(T), LauncherJsonContext.Default);
            File.WriteAllText(path, json);
        }
        catch
        {
            // ignore
        }
    }
}

[JsonSerializable(typeof(AppConfig))]
[JsonSerializable(typeof(AppState))]
[JsonSerializable(typeof(BuildStamp))]
[JsonSerializable(typeof(UpdateFeed))]
[JsonSerializable(typeof(PendingUpdate))]
[JsonSerializable(typeof(AzulPackageDto[]))]
internal partial class LauncherJsonContext : JsonSerializerContext
{
}
