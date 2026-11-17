using System.Text.Json.Serialization;

namespace BnbLauncher.Models;

/// <summary>
/// 「服务端编译产物可用」的凭证，只有 mvn compile 成功后才会写出。
///
/// 不能靠比较源码和 class 的修改时间来判断是否要重编：源码目录由 git reset --hard 更新，
/// 被删除的文件不会留下任何「更新」痕迹，旧 class 却还留在 target\classes 里；
/// pom.xml、资源文件、JDK 版本变化同样不会让 Kotlin 源文件的时间戳变化。
/// 所以这里把「编译输入」的指纹和「编译输出」的规模一起记下来，判定时逐项比对，
/// 任何一项对不上或文件缺失都退回重新编译（宁可多编，绝不跑旧代码）。
/// </summary>
public sealed class BuildStamp
{
    /// <summary>编译时的源码 HEAD commit。工作区脏时连同 Dirty 一起记，避免掩盖手改。</summary>
    [JsonPropertyName("sha")] public string? Sha { get; set; }

    [JsonPropertyName("dirty")] public bool Dirty { get; set; }

    /// <summary>
    /// 编译时工作区里「与编译相关」的改动指纹（见 GitSource.ReadWorkTree）。
    /// 干净工作区是 "clean"；用户在 runtime\source 里手改源码后也能正确识别
    /// 「改过了没重编」和「这批改动已经编译过」。
    /// </summary>
    [JsonPropertyName("workTreeHash")] public string? WorkTreeHash { get; set; }

    /// <summary>pom.xml 的 SHA-256：依赖版本、Kotlin 插件参数变化都要触发重编。</summary>
    [JsonPropertyName("pomHash")] public string? PomHash { get; set; }

    /// <summary>编译所用 JDK，换 JDK 后 bytecode 目标可能不同。</summary>
    [JsonPropertyName("jdkVersion")] public string? JdkVersion { get; set; }

    [JsonPropertyName("kotlinVersion")] public string? KotlinVersion { get; set; }

    /// <summary>classes 目录里的 .class 数量：产物被删/被截断时能立刻发现。</summary>
    [JsonPropertyName("classCount")] public int ClassCount { get; set; }

    [JsonPropertyName("builtAt")] public string? BuiltAt { get; set; }
}
