using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using BnbLauncher.Models;
using BnbLauncher.ViewModels;

namespace BnbLauncher.Services;

/// <summary>
/// 无界面自检：BnbLauncher.exe --selftest [--server]
/// 走一遍「检测更新 → 下载 JDK/Maven → 下载源码 → 启动服务」的完整链路，
/// 结果同时打印到控制台和 logs\selftest-*.log，方便排查环境问题。
/// </summary>
public static class SelfTest
{
    private const int AttachParentProcess = -1;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AttachConsole(int dwProcessId);

    public static void ConsoleWrite(string text)
    {
        try { Console.WriteLine(text); } catch { }
    }

    /// <summary>WinExe 没有自己的控制台，接管调用它的那个 cmd 窗口。</summary>
    public static void AttachToConsole()
    {
        AttachConsole(AttachParentProcess);
        try { Console.OutputEncoding = Encoding.UTF8; } catch { }
    }

    /// <summary>
    /// BnbLauncher.exe --check-update [--download]：只查启动器自身有没有新版本。
    /// 发布后冒烟用（退出码 0 = 检查跑通，不管有没有新版本），也给排查网络/清单问题用。
    /// 带 --download 时顺便把新 exe 下下来并校验 SHA-256，用来确认 Release 资产和清单是配套的。
    /// </summary>
    public static async Task<int> CheckUpdateAsync(bool download)
    {
        AttachToConsole();

        var config = JsonStore.Load(AppPaths.ConfigPath, () => new AppConfig());
        Downloader.Configure(config);

        ConsoleWrite($"Current version v{LauncherUpdater.CurrentVersion}");
        if (!LauncherUpdater.CanSelfUpdate)
        {
            ConsoleWrite("RESULT: SKIPPED " + LauncherUpdater.UnsupportedReason);
            return 0;
        }

        foreach (var feed in LauncherUpdater.FeedUrls(config)) ConsoleWrite("Feed: " + feed);

        try
        {
            var info = await LauncherUpdater.CheckAsync(config);
            if (info is null)
            {
                ConsoleWrite("RESULT: OK already up to date");
                return 0;
            }

            ConsoleWrite($"New version v{info.Version} available ({ByteSize.Format(info.Size)})");
            ConsoleWrite("Download URL: " + info.Url);
            ConsoleWrite("SHA-256: " + info.Sha256);
            if (LauncherUpdater.IsMandatory(info.MinVersion)) ConsoleWrite($"Mandatory update: minimum v{info.MinVersion}");
            if (info.Notes.Length > 0) ConsoleWrite("Notes: " + info.Notes.Replace("\r", "").Replace("\n", " / "));

            if (download)
            {
                var lastPercent = -1;
                var progress = new Progress<DownloadProgress>(p =>
                {
                    var bucket = (int)p.Percent / 10;
                    if (bucket == lastPercent) return;
                    lastPercent = bucket;
                    ConsoleWrite($"  {p.Percent,5:F1}%  {ByteSize.Format(p.Bytes)} / {ByteSize.Format(p.Total)}");
                });

                ConsoleWrite("Downloading and verifying…");
                var path = await LauncherUpdater.DownloadAsync(info, progress);
                ConsoleWrite("Downloaded and verified: " + path);
                ConsoleWrite("(nothing was replaced; this only verifies that the manifest matches the asset)");
            }

            ConsoleWrite("RESULT: OK update available");
            return 0;
        }
        catch (Exception ex)
        {
            ConsoleWrite("Check failed: " + ex.Message);
            ConsoleWrite("RESULT: FAILED");
            return 1;
        }
    }

    public static async Task<int> RunAsync(string[] args)
    {
        AttachToConsole();

        var runServer = args.Any(a => a.Equals("--server", StringComparison.OrdinalIgnoreCase));
        // 只跑构建、不启动服务：端口被别的实例占着时也能验证编译链路
        var runBuild = args.Any(a => a.Equals("--build", StringComparison.OrdinalIgnoreCase));
        var logFile = Path.Combine(AppPaths.LogsDir, $"selftest-{DateTime.Now:yyyyMMdd-HHmmss}.log");
        var report = new StringBuilder();
        var exitCode = 0;

        void Say(string s)
        {
            ConsoleWrite(s);
            report.AppendLine(s);
        }

        Say($"[selftest] BnbLauncher v{MainViewModel.AppVersion}");
        Say($"[selftest] base = {AppPaths.BaseDir}");

        var config = JsonStore.Load(AppPaths.ConfigPath, () => new AppConfig());
        var state = JsonStore.Load(AppPaths.StatePath, () => new AppState());
        Say($"[selftest] repo = {config.RepoUrl}#{config.Branch}");

        JdkInfo? jdk = null;
        MavenInfo? maven = null;

        try
        {
            // 1) JDK
            jdk = JavaProvisioner.GetLocal() ?? JavaProvisioner.GetSystem();
            if (jdk is null)
            {
                Say("[jdk] not found, downloading…");
                jdk = await JavaProvisioner.InstallAsync(config.JdkVendor,
                    new Progress<SetupProgress>(p => Say($"[jdk] {p.Phase} {p.Percent:0}%")));
            }
            Say($"[jdk] OK {jdk.Display} @ {jdk.Home}");

            // 2) Maven：内置优先，其次下载，最后才退回系统 Maven
            maven = MavenProvisioner.GetLocal();
            if (maven is null)
            {
                try
                {
                    Say("[mvn] bundled Maven not found, downloading…");
                    maven = await MavenProvisioner.InstallAsync(
                        new Progress<SetupProgress>(p => Say($"[mvn] {p.Phase} {p.Percent:0}%")));
                }
                catch (Exception ex)
                {
                    Say("[mvn] download failed: " + ex.Message);
                    maven = await MavenProvisioner.GetSystemAsync(jdk?.Home);
                }
            }
            if (maven is null) throw new InvalidOperationException("No usable Maven found");
            Say($"[mvn] OK {maven.Version} @ {maven.Home}");
            Say($"[mvn] repo = {AppPaths.MavenRepoDir}" +
                (AppPaths.UseUserMavenRepo ? " (user .m2)" : " (bundled, no .m2)"));

            // 2.5) 用与编译完全相同的命令行验证一遍（引号 / JAVA_HOME / 本地仓库）
            if (jdk is not null)
            {
                var (code, output) = await ProcessUtil.RunAsync(
                    Environment.GetEnvironmentVariable("COMSPEC") ?? "cmd.exe",
                    ServerBuild.BuildMvnArguments(maven, "-v"),
                    AppPaths.SourceDir,
                    ServerBuild.BuildMvnEnvironment(jdk, maven),
                    TimeSpan.FromSeconds(90));

                var firstLine = output.Split('\n').Select(l => l.Trim())
                    .FirstOrDefault(l => l.StartsWith("Apache Maven", StringComparison.Ordinal)) ?? output.Trim();
                Say($"[mvn] command-line probe exit={code}: {firstLine}");
                if (code != 0) exitCode = 1;
            }

            // 3) 源码：git ls-remote 判断远端，没有本地仓库就 clone
            GitSource.Initialize();
            Say($"[git] libgit2 {GitSource.Version} · {GitNetwork.Note(config)} · {GitMirror.Note(config.Mirror)}");

            var status = await GitSource.CheckAsync(config);
            Say($"[git] remote head = {status.RemoteHead?.ShortSha ?? "(unknown)"} {status.RemoteHead?.Title ?? ""}");
            Say($"[git] local  = {status.LocalHead?.ShortSha ?? "(none)"} dirty={status.Dirty}");

            if (!GitSource.Exists)
            {
                Say("[git] no local repository, cloning…");
                var result = await GitSource.SyncAsync(config,
                    new Progress<SetupProgress>(p => Say($"[git] {p.Phase} {p.Percent:0}%")));
                state.InstalledSha = result.Head?.Sha;
                state.InstalledShortSha = result.Head?.ShortSha;
                state.InstalledMessage = result.Head?.Title;
                state.InstalledCommitDate = result.Head?.Date?.ToString("O");
                state.InstalledAt = DateTime.Now.ToString("O");
                JsonStore.Save(AppPaths.StatePath, state);
                Say($"[git] cloned {result.Head?.ShortSha}");
            }
            else
            {
                Say($"[git] upToDate={status.UpToDate} ahead={status.Ahead} diverged={status.Diverged}");
            }

            // 4) 构建状态：不带 --server 时也能看出下次启动会不会重编
            if (jdk is not null)
            {
                var needBuild = ServerBuild.NeedsBuild(jdk, out var buildReason);
                var stamp = ServerBuild.ReadStamp();
                var sha = stamp?.Sha ?? "";
                var stampText = stamp is null
                    ? "(none)"
                    : $"{sha[..Math.Min(7, sha.Length)]} · {stamp.ClassCount} classes · {stamp.BuiltAt}";
                Say($"[build] needBuild={needBuild}: {buildReason}");
                Say($"[build] stamp = {stampText}");
            }

            // 4.5) 服务
            if (runServer)
            {
                if (jdk is null || maven is null) throw new InvalidOperationException("Runtime not ready");

                var busy = PortProbe.BusyPorts(3838, 47611);
                if (busy.Length > 0)
                {
                    Say($"[server] port {string.Join("/", busy)} is in use; skipping the start test (maybe an IDE or another instance)");
                }
                else
                {
                    // 先走和界面完全相同的构建判定，自检因此也覆盖「能不能编出来」
                    var outcome = await ServerBuild.EnsureAsync(jdk, maven, line => Say("[build] " + line),
                        force: false, CancellationToken.None);
                    Say(outcome.Compiled
                        ? $"[build] compiled in {outcome.Elapsed.TotalSeconds:0.0}s ({outcome.Reason})"
                        : $"[build] skipped: {outcome.Reason}");

                    var runner = new ServerRunner();
                    var lines = new List<string>();
                    runner.Output += line =>
                    {
                        lock (lines) lines.Add(line);
                    };

                    Say("[server] start: java -cp <target classes + deps> " + AppPaths.ServerMainClass);
                    runner.Start(jdk);
                    Say($"[server] pid = {runner.ProcessId}");

                    var ready = false;
                    var deadline = DateTime.UtcNow.AddMinutes(8);
                    while (DateTime.UtcNow < deadline)
                    {
                        if (!runner.IsRunning) break;
                        if (await CanConnectAsync("127.0.0.1", 3838)) { ready = true; break; }
                        await Task.Delay(2000);
                    }

                    Say(ready ? "[server] port 3838 is listening, server ready" : "[server] could not confirm the server is ready");
                    if (ready) await Task.Delay(TimeSpan.FromSeconds(15));

                    await runner.StopAsync();
                    Say("[server] stopped");

                    lock (lines)
                    {
                        Say($"[server] output tail ({Math.Min(50, lines.Count)}/{lines.Count} lines):");
                        foreach (var line in lines.TakeLast(50)) Say("    " + line);
                    }

                    if (!ready) exitCode = 1;
                }
            }
            else if (runBuild)
            {
                if (jdk is null || maven is null) throw new InvalidOperationException("Runtime not ready");

                var outcome = await ServerBuild.EnsureAsync(jdk, maven, line => Say("[build] " + line),
                    force: false, CancellationToken.None);
                Say(outcome.Compiled
                    ? $"[build] compiled in {outcome.Elapsed.TotalSeconds:0.0}s ({outcome.Reason})"
                    : $"[build] skipped: {outcome.Reason}");
            }
        }
        catch (Exception ex)
        {
            Say("[error] " + ex);
            exitCode = 1;
        }

        Say($"[selftest] exit={exitCode}");
        try
        {
            Directory.CreateDirectory(AppPaths.LogsDir);
            File.WriteAllText(logFile, report.ToString(), new UTF8Encoding(false));
            ConsoleWrite("[selftest] report = " + logFile);
        }
        catch
        {
            // ignore
        }

        return exitCode;
    }

    private static async Task<bool> CanConnectAsync(string host, int port)
    {
        try
        {
            using var client = new System.Net.Sockets.TcpClient();
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            await client.ConnectAsync(host, port, cts.Token);
            return client.Connected;
        }
        catch
        {
            return false;
        }
    }
}
