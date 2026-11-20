using System.IO;
using System.Text;

namespace BnbLauncher.Services;

/// <summary>
/// 依赖下载走国内镜像时用的「内置 settings.xml」。
///
/// 为什么需要它：launcher 调 mvn 时只钉了 -Dmaven.repo.local，没有 -s，所以依赖解析完全走
/// 零配置默认行为（Maven 内置的 central）。国内直连它解析几百个小文件要 20 秒级；
/// 换成阿里云 public 组（同布局的中央仓库反代）实测 1.5 秒级。
///
/// 三条边界：
///   1. 只在简体中文系统启用（SystemLocale.IsSimplifiedChinese），其他语言行为与以前逐字一致；
///   2. 用户已经有 %USERPROFILE%\.m2\settings.xml 时一律不碰——里面可能有公司私服或 &lt;proxies&gt;，
///      用 -s 顶掉它比慢更糟；这种情况只在日志里说一声（Describe）；
///   3. 生成的文件落在 runtime 里，不写用户目录，跟启动器其他组件的规则一致。
/// </summary>
public static class MavenSettings
{
    private const string MirrorUrl = "https://maven.aliyun.com/repository/public";

    /// <summary>
    /// 要镜像的仓库。只认 Maven 内置的 central —— pom.xml 里那条多余的 mavenCentral 已经删掉了，
    /// 现在整个工程只有一个仓库，依赖和插件都挂在它上面。
    ///
    /// id 必须跟仓库 id **一模一样**，不能让 Maven 自己起名：Maven 把「这个 jar 是从哪个源下的」
    /// 写进本地仓库的 _remote.repositories，用的是镜像的 id。id 一变（比如叫 aliyun-public），
    /// 本地已有的 jar 全被判成「没从这个源下过」：离线构建直接失败，联网则会白重下整份仓库。
    /// 实测同一份 ~/.m2，用 id=central 时 `mvn -o compile` 13 秒通过，用自定义 id 则立刻报
    /// "Cannot access ... in offline mode and the artifact ... has not been downloaded from it before"。
    /// </summary>
    private static readonly string[] MirroredRepositories = ["central"];

    /// <summary>
    /// 进程内只判定一次：同一次编译里 compile 与 dependency:build-classpath 必须用同一份 settings，
    /// 否则后半段可能换源重下依赖（AppPaths 的仓库判定也是同样的理由）。
    /// </summary>
    private static readonly Lazy<string?> Decision = new(Resolve);

    /// <summary>这次 mvn 要用的 settings 路径；null 表示不加 -s，沿用 Maven 默认行为。</summary>
    public static string? ActivePath => Decision.Value;

    /// <summary>
    /// 编译前写进日志的一行说明。非简体中文系统返回 null —— 什么也没做，不必解释。
    /// </summary>
    public static string? Describe()
    {
        if (!SystemLocale.IsSimplifiedChinese) return null;

        if (ActivePath is { } path)
            return $"Maven mirror: aliyun public as {string.Join("+", MirroredRepositories)} (built-in settings: {path})";

        return File.Exists(UserSettingsPath)
            ? $"Maven mirror: none (custom {UserSettingsPath} detected, left untouched)"
            : "Maven mirror: none (built-in settings could not be written)";
    }

    /// <summary>用户自己的 settings：Maven 的默认位置，存在就说明这台机器有既有配置。</summary>
    private static string UserSettingsPath => Path.Combine(AppPaths.UserM2Dir, "settings.xml");

    private static string? Resolve()
    {
        if (!SystemLocale.IsSimplifiedChinese) return null;
        if (File.Exists(UserSettingsPath)) return null; // 用户的配置优先，绝不覆盖

        try
        {
            Directory.CreateDirectory(AppPaths.RuntimeDir);

            var path = AppPaths.MavenSettingsFile;
            var xml = Build();

            // 内容一致就不重写：多一次落盘没意义，mtime 变化还会让人以为配置被改过
            if (!File.Exists(path) || File.ReadAllText(path) != xml)
            {
                // 必须无 BOM：Maven 的 XML 解析器遇到 BOM 会直接报错
                File.WriteAllText(path, xml, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            }

            return path;
        }
        catch
        {
            // 写不出来就退回默认行为：镜像只是优化，不能因为它让编译起不来
            return null;
        }
    }

    private static string Build()
    {
        // 逐行拼而不是用多行原始字符串：需要把「每个仓库一块」重复出来，
        // 原始字符串的缩进规则在这种情况下会跟生成的缩进打架，拼出来反而更难读。
        var sb = new StringBuilder();
        sb.AppendLine("""<?xml version="1.0" encoding="UTF-8"?>""");
        sb.AppendLine("<!-- 由 BnbLauncher 生成，请勿手工编辑（每次编译会按需重写）。");
        sb.AppendLine("     只在简体中文系统、且 %USERPROFILE%\\.m2\\settings.xml 不存在时使用；");
        sb.AppendLine("     用户自己的 settings.xml 永远不会被覆盖。 -->");
        sb.AppendLine("""<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">""");
        sb.AppendLine("  <mirrors>");

        foreach (var repo in MirroredRepositories)
        {
            sb.AppendLine("    <mirror>");
            sb.AppendLine($"      <id>{repo}</id>");
            sb.AppendLine($"      <name>Aliyun public (as {repo})</name>");
            sb.AppendLine($"      <url>{MirrorUrl}</url>");
            sb.AppendLine($"      <mirrorOf>{repo}</mirrorOf>");
            sb.AppendLine("    </mirror>");
        }

        sb.AppendLine("  </mirrors>");
        sb.AppendLine("</settings>");
        return sb.ToString();
    }
}
