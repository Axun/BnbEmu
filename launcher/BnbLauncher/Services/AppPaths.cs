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
///       m2repo\                Maven 本地仓库（避免污染 %USERPROFILE%\.m2）
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
    public static string M2RepoDir => Path.Combine(RuntimeDir, "m2repo");
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
    /// <summary>
    /// 正在运行的这个 exe。名字写死成 BnbLauncher.exe：替换脚本全用相对路径（脚本里不能出现
    /// 中文，否则 cmd 按 ANSI 代码页读会乱码），改名运行的副本会被 LauncherUpdater 判定为不可自更新。
    /// </summary>
    public static string ExePath => Path.Combine(BaseDir, "BnbLauncher.exe");

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
    public static string JavaExe => Path.Combine(JdkDir, "bin", "java.exe");

    public static void EnsureDirectories()
    {
        Directory.CreateDirectory(RuntimeDir);
        Directory.CreateDirectory(CacheDir);
        Directory.CreateDirectory(LogsDir);
        Directory.CreateDirectory(M2RepoDir);
        Directory.CreateDirectory(UpdateDir);
        Directory.CreateDirectory(BuildDir);
    }

    /// <summary>源码是否已落地（用 pom.xml 作为完整性标记）。</summary>
    public static bool SourceExists => File.Exists(Path.Combine(SourceDir, "pom.xml"));
}
