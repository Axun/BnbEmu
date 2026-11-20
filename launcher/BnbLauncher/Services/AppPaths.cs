using System.IO;

namespace BnbLauncher.Services;

/// <summary>
/// 所有运行期文件都落在启动器 exe 所在目录，绝不写系统目录 / 用户目录。
///
/// 目录结构：
///   &lt;启动器目录&gt;\
///     BnbLauncher.exe
///     launcher.config.json     用户设置
///     runtime\
///       state.json             当前安装的提交 / 组件版本
///       jdk\                   JDK 21（Amazon Corretto 或 Azul Zulu）
///       maven\                 Apache Maven 3.9.x
///       m2repo\                Maven 本地仓库（仅当 %USERPROFILE%\.m2 不存在时才用它）
///       build\                 服务端构建产物（依赖 classpath 快照 + 编译凭证）
///       source\                BnbEmu 源码
///       client\                客户端运行目录（exe/dll 从 resource\other 复制，idd/idx 现打包）
///       update\                启动器自身更新的暂存区（new.exe / pending.json）
///       cache\                 下载缓存（安装成功后安装包即被丢弃，只剩失败重试的残包）
///     logs\
///       launcher-20261111.log
/// </summary>
public static class AppPaths
{
    public static string BaseDir { get; } = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);

    public static string RuntimeDir => Path.Combine(BaseDir, "runtime");
    public static string SourceDir => Path.Combine(RuntimeDir, "source");
    public static string JdkDir => Path.Combine(RuntimeDir, "jdk");
    public static string MavenDir => Path.Combine(RuntimeDir, "maven");

    // ── Maven 本地仓库 ──────────────────────────────────────────────────
    // 规则只有一条：用户目录下存在 .m2 就复用用户的仓库，否则用 runtime\m2repo。
    // 之所以要显式决定并写到命令行上（-Dmaven.repo.local），而不是「不传让 mvn 自己挑」：
    // 不传时 mvn 永远用 %USERPROFILE%\.m2\repository，runtime\m2repo 就成了一个空摆设，
    // 没装过 Maven 的机器还会被悄悄写一份用户目录。

    /// <summary>%USERPROFILE%：Maven 的默认 settings / 本地仓库都以它为家。</summary>
    public static string UserHomeDir { get; } = ResolveUserHome();

    /// <summary>
    /// %USERPROFILE%\.m2。它存在就说明这台机器上已经有现成的 Maven 仓库
    /// （装了 Maven、IDE 导入过依赖、或别的 Maven 项目跑过），直接复用能省掉整份依赖的重新下载。
    /// </summary>
    public static string UserM2Dir => Path.Combine(UserHomeDir, ".m2");

    /// <summary>runtime\m2repo：用户没有 .m2 时的隔离仓库，保持「不污染用户目录」这条。</summary>
    public static string BundledMavenRepoDir => Path.Combine(RuntimeDir, "m2repo");

    // 进程内只判定一次：一次编译里 compile 与 dependency:build-classpath 必须落在同一个仓库，
    // 否则 classpath 快照会指向另一份仓库里的 jar（甚至指向已被清掉的文件）。
    private static readonly Lazy<(bool UseUser, string Dir)> MavenRepoDecision = new(ResolveMavenRepo);

    /// <summary>真正传给 mvn -Dmaven.repo.local 的仓库目录。</summary>
    public static string MavenRepoDir => MavenRepoDecision.Value.Dir;

    /// <summary>true = 复用用户 %USERPROFILE%\.m2；false = 用内置 runtime\m2repo。</summary>
    public static bool UseUserMavenRepo => MavenRepoDecision.Value.UseUser;

    private static (bool UseUser, string Dir) ResolveMavenRepo()
    {
        // 认目录存在与否，不猜内容：.m2 在（哪怕只有 settings.xml）就算「用户有仓库」
        if (UserHomeDir.Length > 0 && Directory.Exists(UserM2Dir))
            return (true, Path.Combine(UserM2Dir, "repository"));

        return (false, BundledMavenRepoDir);
    }

    private static string ResolveUserHome()
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (string.IsNullOrWhiteSpace(home))
            home = Environment.GetEnvironmentVariable("USERPROFILE") ?? "";
        return home.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
    }

    public static string CacheDir => Path.Combine(RuntimeDir, "cache");
    public static string LogsDir => Path.Combine(BaseDir, "logs");

    // ── 服务端构建产物 ──────────────────────────────────────────────────
    // 编译只在源码/pom/JDK 变化时发生，平时用 javac 的输出直接 java -cp 启动，
    // 因此「产物可用」这件事需要一份落盘的凭证，见 ServerBuild。
    public static string BuildDir => Path.Combine(RuntimeDir, "build");

    /// <summary>runtime scope 依赖的 classpath 快照（分号分隔的绝对路径）。</summary>
    public static string ServerClasspathFile => Path.Combine(BuildDir, "server-cp.txt");

    /// <summary>上次成功编译的记录；只有编译成功才会写，失败/中断一律没有。</summary>
    public static string BuildStampFile => Path.Combine(BuildDir, "build-stamp.json");

    /// <summary>mvn 的编译输出目录（每次编译前整个删掉，避免残留 class）。</summary>
    public static string ServerClassesDir => Path.Combine(SourceDir, "target", "classes");

    public const string ServerMainClass = "bnbemu.MainKt";

    /// <summary>主类编译产物，用来确认 classes 目录不是空壳。</summary>
    public static string ServerMainClassFile => Path.Combine(ServerClassesDir, "bnbemu", "MainKt.class");

    public static string PomFile => Path.Combine(SourceDir, "pom.xml");

    /// <summary>客户端运行目录：由 resource 子模块里的 exe/dll 与打包出来的 idd/idx 拼成。</summary>
    public static string ClientDir => Path.Combine(RuntimeDir, "client");

    /// <summary>客户端资源（git 子模块，仓库根下的 resource）。</summary>
    public static string ResourceDir => Path.Combine(SourceDir, "resource");

    public static string StatePath => Path.Combine(RuntimeDir, "state.json");
    public static string ConfigPath => Path.Combine(BaseDir, "launcher.config.json");

    // ── 启动器自身更新 ──────────────────────────────────────────────────
    // 正在运行的这个 exe 不在这里记：一律用 Environment.ProcessPath 取（见 LauncherUpdater），
    // 免得目录里再放一份可能和真相不一致的「BnbLauncher.exe」路径。

    /// <summary>上一版本，替换时由脚本挪过来；新版本第一次成功启动后仍保留，第二次才清掉。</summary>
    public static string PrevExePath => Path.Combine(BaseDir, "BnbLauncher.exe.prev");

    /// <summary>更新工作目录：暂存的新 exe、pending.json 都在这里，删掉它不会影响别的。</summary>
    public static string UpdateDir => Path.Combine(RuntimeDir, "update");

    /// <summary>
    /// 下载好的新 exe 统一改名成 new.exe 交给替换脚本。脚本里因此不需要写版本号，
    /// 也就永远不会拼错；脚本本身固定是纯 ASCII。
    /// </summary>
    public static string StagedUpdatePath => Path.Combine(UpdateDir, "new.exe");

    public static string PendingUpdatePath => Path.Combine(UpdateDir, "pending.json");

    /// <summary>替换脚本，放在启动器目录（脚本靠 %~dp0 定位，自己不含任何路径字面量）。</summary>
    public static string UpdateScriptPath => Path.Combine(BaseDir, "update-apply.cmd");

    public static string MvnCmd => Path.Combine(MavenDir, "bin", "mvn.cmd");

    /// <summary>
    /// 内置 Maven 设置（只在简体中文系统、且用户没有自己的 settings.xml 时生成，见 MavenSettings）。
    /// 放在 runtime 里而不是 %USERPROFILE%\.m2，是为了守住「不污染用户目录」这条。
    /// </summary>
    public static string MavenSettingsFile => Path.Combine(RuntimeDir, "maven-settings.xml");
    public static string JavaExe => Path.Combine(JdkDir, "bin", "java.exe");

    public static void EnsureDirectories()
    {
        Directory.CreateDirectory(RuntimeDir);
        Directory.CreateDirectory(CacheDir);
        Directory.CreateDirectory(LogsDir);
        // 用户没有 .m2 才需要自己的仓库目录；有就一个字都不往用户目录写
        if (!UseUserMavenRepo) Directory.CreateDirectory(BundledMavenRepoDir);
        Directory.CreateDirectory(UpdateDir);
        Directory.CreateDirectory(BuildDir);
    }

    /// <summary>源码是否已落地（用 pom.xml 作为完整性标记）。</summary>
    public static bool SourceExists => File.Exists(Path.Combine(SourceDir, "pom.xml"));
}
