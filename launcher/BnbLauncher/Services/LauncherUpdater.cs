using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using BnbLauncher.Models;

namespace BnbLauncher.Services;

/// <summary>更新状态机。界面横幅的文案与按钮全部由它派生。</summary>
public enum UpdateStage
{
    /// <summary>没有可用更新（或还没查过）。</summary>
    None,

    /// <summary>远端有更新的版本，还没下载。</summary>
    Available,

    Downloading,

    /// <summary>已下载 + 校验通过，等用户点重启（或退出时自动应用）。</summary>
    Ready,

    Failed,
}

public sealed record LauncherUpdate(
    string Version,
    string MinVersion,
    string Notes,
    string Url,
    long Size,
    string Sha256,
    string Page,
    string Feed);

/// <summary>
/// 启动器自身更新。
///
/// 为什么不用 Velopack / Squirrel 这类框架：本启动器是「自包含单文件 exe + 旁边的 runtime 目录」，
/// 更新只需要换掉一个文件，而这类框架的模型是「整个安装目录归它管、每次更新重建 current\」，
/// 会逼着把 runtime（JDK/Maven/源码/客户端，几百 MB）搬出去、老用户重下。
/// 所以这里只做四件事：读清单 → 下 exe → 校验 → 退出后由 helper 换文件。
///
/// 替换为什么必须发生在进程退出之后：Windows 不允许删除/覆盖正在运行的映像，
/// 但允许在同卷内改名。所以脚本的顺序是「旧 exe 改名成 .prev → 新 exe 落位 → 拉起」，
/// 任何一步失败都会把 .prev 改回去，用户不会落到「目录里没有 exe」的状态。
///
/// 回滚：.prev 会保留到新版本成功启动两次之后才删。中间那次启动如果发现版本号没变成目标版本，
/// 说明替换没发生（脚本被拦/被杀），直接清掉 pending，用户可以重新点更新。
/// </summary>
public static class LauncherUpdater
{
    public static string CurrentVersion { get; } = ReadVersion();

    /// <summary>被改名运行的副本不能自更新：替换脚本按固定文件名（BnbLauncher.exe）操作。</summary>
    public static bool CanSelfUpdate { get; } = CheckSelfUpdateSupported();

    public static string? UnsupportedReason => CanSelfUpdate
        ? null
        : $"The launcher file is not named BnbLauncher.exe (currently {Path.GetFileName(Environment.ProcessPath)}); self-update is disabled";

    // ── 清单 ────────────────────────────────────────────────────────────

    /// <summary>
    /// 更新源。默认两个，按顺序试：
    ///   1. 滚动 tag `launcher-latest` 下的 latest.json —— 发布流程每次覆盖上传，路径永远不变；
    ///   2. 仓库 raw 里的 launcher/latest.json —— 万一哪天改成提交进仓库，也不用动客户端。
    /// 注意别用 /releases/latest/download/：那个 latest 会被客户端资源那条 release 抢走。
    /// </summary>
    public static IReadOnlyList<string> FeedUrls(AppConfig cfg)
    {
        var custom = cfg.UpdateFeedUrl?.Trim();
        if (!string.IsNullOrEmpty(custom)) return [custom];

        return
        [
            $"https://github.com/{cfg.Owner}/{cfg.Repo}/releases/download/launcher-latest/latest.json",
            $"https://raw.githubusercontent.com/{cfg.Owner}/{cfg.Repo}/main/launcher/latest.json",
        ];
    }

    /// <summary>查一次远端；没有新版本（或清单还没建好）返回 null，网络错误往上抛。</summary>
    public static async Task<LauncherUpdate?> CheckAsync(AppConfig cfg, CancellationToken ct = default)
    {
        if (!CanSelfUpdate) throw new InvalidOperationException(UnsupportedReason!);

        Exception? last = null;
        foreach (var feed in FeedUrls(cfg))
        {
            try
            {
                var update = await FetchFeedAsync(feed, ct);
                if (update is null) continue;                     // 这个源还没有清单，试下一个
                return CompareVersion(CurrentVersion, update.Version) < 0 ? update : null;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                last = ex;
            }
        }

        if (last is not null) throw last;
        return null;
    }

    private static async Task<LauncherUpdate?> FetchFeedAsync(string url, CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(10));

        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        // 刚发完版就点检测时要拿到新的那份；CDN 最多缓存几分钟，这里至少要它回源校验
        request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };

        using var resp = await Downloader.Http.SendAsync(request, HttpCompletionOption.ResponseContentRead, timeout.Token);
        if (resp.StatusCode == HttpStatusCode.NotFound) return null;   // 这个源不存在，换下一个
        if (!resp.IsSuccessStatusCode) throw new InvalidOperationException($"Failed to download the manifest: HTTP {(int)resp.StatusCode}");

        var json = await resp.Content.ReadAsStringAsync(timeout.Token);
        var feed = JsonSerializer.Deserialize(json, LauncherJsonContext.Default.UpdateFeed)
                   ?? throw new InvalidOperationException("The manifest is not valid JSON");

        if (string.IsNullOrWhiteSpace(feed.Version)) throw new InvalidOperationException("The manifest is missing version");
        if (feed.Exe is null || string.IsNullOrWhiteSpace(feed.Exe.Url)) throw new InvalidOperationException("The manifest is missing exe.url");

        var sha = (feed.Exe.Sha256 ?? "").Trim();
        if (sha.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase)) sha = sha[7..].Trim();
        if (sha.Length != 64) throw new InvalidOperationException("The manifest has no valid exe.sha256 (updates without verification are rejected)");

        return new LauncherUpdate(
            NormalizeVersion(feed.Version),
            NormalizeVersion(feed.MinVersion),
            feed.Notes?.Trim() ?? "",
            feed.Exe.Url.Trim(),
            feed.Exe.Size,
            sha,
            feed.Page?.Trim() ?? "",
            url);
    }

    /// <summary>当前版本低于清单里的 minVersion 时强制更新（旧版的引导流程可能已经跑不通）。</summary>
    public static bool IsMandatory(string minVersion)
        => !string.IsNullOrWhiteSpace(minVersion) && CompareVersion(CurrentVersion, minVersion) < 0;

    // ── 下载 ────────────────────────────────────────────────────────────

    /// <summary>
    /// 下载并校验，成功返回暂存路径（runtime\update\new.exe）。
    /// 暂存文件按版本命名（new-1.2.0.exe），这样断点续传不会把两个版本的字节拼在一起。
    /// </summary>
    public static async Task<string> DownloadAsync(LauncherUpdate update, IProgress<DownloadProgress>? progress,
        CancellationToken ct = default)
    {
        Directory.CreateDirectory(AppPaths.UpdateDir);

        var staged = Path.Combine(AppPaths.UpdateDir, $"new-{SafeVersion(update.Version)}.exe");

        // 上次已经下完并校验过（比如更新失败后重试），直接用
        if (File.Exists(AppPaths.StagedUpdatePath) && await VerifyAsync(AppPaths.StagedUpdatePath, update, ct))
            return AppPaths.StagedUpdatePath;

        if (File.Exists(staged) && await VerifyAsync(staged, update, ct))
        {
            File.Move(staged, AppPaths.StagedUpdatePath, overwrite: true);
            return AppPaths.StagedUpdatePath;
        }

        await Downloader.DownloadAsync(update.Url, staged, progress, ct, resume: true);

        if (!await VerifyAsync(staged, update, ct))
        {
            TryDelete(staged);
            TryDelete(staged + ".part");
            throw new InvalidOperationException("The downloaded launcher failed verification (length or SHA-256 mismatch) and was discarded");
        }

        File.Move(staged, AppPaths.StagedUpdatePath, overwrite: true);
        return AppPaths.StagedUpdatePath;
    }

    private static async Task<bool> VerifyAsync(string path, LauncherUpdate update, CancellationToken ct)
    {
        try
        {
            var info = new FileInfo(path);
            if (!info.Exists) return false;
            if (update.Size > 0 && info.Length != update.Size) return false;

            await using var stream = File.OpenRead(path);
            var hash = await SHA256.HashDataAsync(stream, ct);
            return Convert.ToHexString(hash).Equals(update.Sha256, StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }

    // ── 替换 ────────────────────────────────────────────────────────────

    /// <summary>
    /// 排好替换，然后调用方立刻退出进程。relaunch = true 时脚本换完文件会把启动器重新拉起来。
    /// </summary>
    public static void ScheduleApply(LauncherUpdate update, string stagedExe, bool relaunch)
    {
        if (!CanSelfUpdate) throw new InvalidOperationException(UnsupportedReason!);
        if (!File.Exists(stagedExe)) throw new FileNotFoundException("No downloaded launcher available", stagedExe);

        // 脚本只认 runtime\update\new.exe，先归位（路径已经一致时就是空操作）
        if (!string.Equals(Path.GetFullPath(stagedExe), Path.GetFullPath(AppPaths.StagedUpdatePath),
                StringComparison.OrdinalIgnoreCase))
        {
            File.Move(stagedExe, AppPaths.StagedUpdatePath, overwrite: true);
        }

        // 脚本内容全是 ASCII、路径全靠 %~dp0 和相对名，所以不存在「中文目录被 cmd 按 GBK 读坏」的问题
        File.WriteAllText(AppPaths.UpdateScriptPath, ApplyScript, new UTF8Encoding(false));

        JsonStore.Save(AppPaths.PendingUpdatePath, new PendingUpdate
        {
            TargetVersion = update.Version,
            ScheduledAt = DateTime.UtcNow.ToString("O"),
            ConfirmedRuns = 0,
        });

        var psi = new ProcessStartInfo(Environment.GetEnvironmentVariable("COMSPEC") ?? "cmd.exe")
        {
            // 相对脚本名 + WorkingDirectory：省掉 cmd /c ""path" args" 那一层引号地狱。
            // 前面的 .\ 是为了不依赖「命令行是否搜当前目录」（NoDefaultCurrentDirectoryInExePath 会关掉它）
            Arguments = $"/d /c .\\{Path.GetFileName(AppPaths.UpdateScriptPath)} {Environment.ProcessId} " +
                        $"{(relaunch ? "1" : "0")}",
            WorkingDirectory = AppPaths.BaseDir,
            UseShellExecute = false,
            CreateNoWindow = true,
        };

        using var proc = Process.Start(psi) ?? throw new InvalidOperationException("Unable to start the update script");
    }

    /// <summary>
    /// 替换脚本。刻意保持纯 ASCII + 相对路径：
    /// cmd 按系统 ANSI 代码页读脚本，脚本里出现中文路径就会乱码，而 %~dp0 由 cmd 自己展开，安全。
    ///
    /// 外部命令一律走 %SystemRoot%\System32 的绝对路径：装了 Git for Windows 的机器，
    /// PATH 里的 GNU find 会抢在 System32\find.exe 前面，`find /I` 会直接报错，
    /// 于是等待循环立刻退出、在 exe 还被占用时就去 move，更新静默失败。
    /// </summary>
    private const string ApplyScript = """
@echo off
setlocal
rem BnbLauncher self-update helper. Keep this file ASCII-only.
rem usage: update-apply.cmd <pid-to-wait> <1=relaunch>
set "WAITPID=%~1"
set "RELAUNCH=%~2"
set "EXE=BnbLauncher.exe"
set "PREV=BnbLauncher.exe.prev"
set "NEW=runtime\update\new.exe"
set "SYS=%SystemRoot%\System32"
cd /d "%~dp0"
if not exist "%NEW%" exit /b 0
set /a TRIES=0
:wait
"%SYS%\tasklist.exe" /FI "PID eq %WAITPID%" /NH 2>nul | "%SYS%\find.exe" /I "%WAITPID%" >nul
if errorlevel 1 goto ready
set /a TRIES+=1
if %TRIES% GEQ 120 goto ready
"%SYS%\ping.exe" -n 2 127.0.0.1 >nul
goto wait
:ready
"%SYS%\ping.exe" -n 2 127.0.0.1 >nul
move /y "%EXE%" "%PREV%" >nul 2>nul
if errorlevel 1 goto launch
move /y "%NEW%" "%EXE%" >nul 2>nul
if errorlevel 1 (
  move /y "%PREV%" "%EXE%" >nul 2>nul
  goto launch
)
:launch
if /I "%RELAUNCH%"=="1" start "" "%EXE%"
endlocal
exit /b 0
""";

    // ── 启动时对账 ──────────────────────────────────────────────────────

    /// <summary>
    /// 每次启动先跑一遍：确认上一次排的替换到底有没有生效、清掉孤儿暂存文件。
    /// </summary>
    public static void Reconcile(LogBus log)
    {
        PendingUpdate pending;
        try
        {
            pending = JsonStore.Load(AppPaths.PendingUpdatePath, () => new PendingUpdate());
        }
        catch
        {
            pending = new PendingUpdate();
        }

        CleanupStaged(keepStaged: !string.IsNullOrWhiteSpace(pending.TargetVersion));

        if (string.IsNullOrWhiteSpace(pending.TargetVersion)) return;

        try
        {
            if (CompareVersion(CurrentVersion, pending.TargetVersion) >= 0)
            {
                if (pending.ConfirmedRuns >= 1)
                {
                    // 第二次成功启动：新版本已经证明自己能跑，回退窗口可以关了
                    TryDelete(AppPaths.PrevExePath);
                    TryDelete(AppPaths.PendingUpdatePath);
                    log.Success("UPDATE", $"Launcher updated to v{CurrentVersion}; previous version cleaned up");
                }
                else
                {
                    pending.ConfirmedRuns = 1;
                    JsonStore.Save(AppPaths.PendingUpdatePath, pending);
                    log.Success("UPDATE", $"Launcher updated to v{CurrentVersion}; " +
                                          $"the previous build is kept as {Path.GetFileName(AppPaths.PrevExePath)} — " +
                                          "rename it back to BnbLauncher.exe to roll back if the new build will not start");
                }
            }
            else
            {
                log.Warn("UPDATE", $"The scheduled update to v{pending.TargetVersion} did not apply (still v{CurrentVersion}); " +
                                   "it has been reset — click update again in the UI");
                TryDelete(AppPaths.PendingUpdatePath);
                TryDelete(AppPaths.StagedUpdatePath);
            }
        }
        catch (Exception ex)
        {
            log.Warn("UPDATE", "Failed to check update state: " + ex.Message);
        }
    }

    /// <summary>暂存区里的 new-*.exe 都是历史残留（要用的那个已经改名成 new.exe 了），直接清掉。</summary>
    private static void CleanupStaged(bool keepStaged)
    {
        try
        {
            if (Directory.Exists(AppPaths.UpdateDir))
            {
                foreach (var file in Directory.EnumerateFiles(AppPaths.UpdateDir, "new-*"))
                    TryDelete(file);
            }

            if (!keepStaged) TryDelete(AppPaths.StagedUpdatePath);
        }
        catch
        {
            // 清理失败不影响启动
        }
    }

    // ── 版本比较 ────────────────────────────────────────────────────────

    /// <summary>SemVer 够用的子集：按 . 比较数字段，1.2.0-beta &lt; 1.2.0，多余段按 0 补齐。</summary>
    public static int CompareVersion(string? a, string? b)
    {
        var (aNumbers, aPre) = SplitVersion(a);
        var (bNumbers, bPre) = SplitVersion(b);

        for (var i = 0; i < Math.Max(aNumbers.Count, bNumbers.Count); i++)
        {
            var x = i < aNumbers.Count ? aNumbers[i] : 0;
            var y = i < bNumbers.Count ? bNumbers[i] : 0;
            if (x != y) return x < y ? -1 : 1;
        }

        if (aPre.Length == 0 && bPre.Length > 0) return 1;
        if (aPre.Length > 0 && bPre.Length == 0) return -1;
        return string.CompareOrdinal(aPre, bPre);
    }

    private static (List<int> Numbers, string Pre) SplitVersion(string? raw)
    {
        var text = (raw ?? "").Trim();
        if (text.Length > 0 && (text[0] is 'v' or 'V')) text = text[1..];

        var dash = text.IndexOf('-');
        var pre = dash >= 0 ? text[(dash + 1)..] : "";
        var core = (dash >= 0 ? text[..dash] : text).Split('+')[0];

        var numbers = new List<int>();
        foreach (var part in core.Split('.'))
        {
            numbers.Add(int.TryParse(part, NumberStyles.Integer, CultureInfo.InvariantCulture, out var n) ? n : 0);
        }

        return (numbers, pre);
    }

    private static string NormalizeVersion(string? raw)
    {
        var text = (raw ?? "").Trim();
        return text.Length > 0 && (text[0] is 'v' or 'V') ? text[1..] : text;
    }

    /// <summary>版本号进文件名前的清洗：只留字母数字和 . - _</summary>
    private static string SafeVersion(string version)
    {
        var sb = new StringBuilder(version.Length);
        foreach (var c in version)
            sb.Append(char.IsLetterOrDigit(c) || c is '.' or '-' or '_' ? c : '_');
        return sb.Length == 0 ? "unknown" : sb.ToString();
    }

    private static string ReadVersion()
    {
        var asm = Assembly.GetExecutingAssembly();
        var info = asm.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
        if (!string.IsNullOrWhiteSpace(info))
        {
            var version = info.Split('+')[0].Trim();
            if (version.Length > 0) return version;
        }

        return asm.GetName().Version?.ToString(3) ?? "0.0.0";
    }

    private static bool CheckSelfUpdateSupported()
    {
        try
        {
            var path = Environment.ProcessPath;
            if (string.IsNullOrEmpty(path)) return true;
            return string.Equals(Path.GetFileName(path), "BnbLauncher.exe", StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return true;
        }
    }

    public static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch
        {
            // 被占用就留着，下次启动再清
        }
    }
}
