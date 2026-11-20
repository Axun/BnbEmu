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
    /// 可选：HTTPS 代理，形如 http://127.0.0.1:7890。留空则自动检测
    /// （环境变量 HTTPS_PROXY → 系统代理 → git 全局配置 http.proxy）。
    /// libgit2 只支持 http/https 代理，SOCKS 会被忽略并退化为直连。
    /// </summary>
    [JsonPropertyName("proxy")] public string Proxy { get; set; } = "";

    /// <summary>
    /// GitHub 访问通道：direct（默认，直连 github.com）| ghproxy（经 gh-proxy.org 反代）。
    /// 同时作用于源码 clone/更新、子模块、更新清单和启动器 exe 下载；
    /// 和上面的 proxy 不冲突（先换成镜像地址，再按代理规则出网）。
    /// </summary>
    [JsonPropertyName("mirror")] public string Mirror { get; set; } = GitMirror.DirectId;

    /// <summary>给浏览器用的仓库主页：始终直连 github.com，镜像只代理文件下载，不适合看网页。</summary>
    [JsonIgnore] public string RepoUrl => $"https://github.com/{Owner}/{Repo}";

    /// <summary>libgit2 的远端地址：跟着镜像设置走，换通道后 EnsureRemote 会自动改写 origin。</summary>
    [JsonIgnore] public string CloneUrl => GitMirror.Rewrite($"https://github.com/{Owner}/{Repo}.git", Mirror);

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
