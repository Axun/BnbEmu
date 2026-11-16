using System.Diagnostics;
using System.IO;
using System.Text.Json;
using BnbLauncher.Models;

namespace BnbLauncher.Services;

/// <summary>
/// 客户端（CA.exe）的落地与启动。
///
/// 客户端目录 <c>runtime\client</c> 完全由启动器拼出来：
/// <list type="number">
/// <item>把 resource 子模块里的 <c>other\</c>（CA.exe、若干 dll、字体、nm.bik）原样复制过去；</item>
/// <item>把 <c>fx\</c> <c>CharTE\</c> <c>EmoTE\</c> 三个解包目录重新打包成 <c>fx.idd/idx</c> 等
///   （见 <see cref="IddArchiveBuilder"/>）；</item>
/// <item>起 CA.exe，工作目录就是客户端目录。</item>
/// </list>
///
/// 打包很贵（三个包合计约 780 MB），所以用 <c>idd-stamps.json</c> 记住每个资源目录的指纹：
/// 源目录没变过就直接跳过，只做一次 stat 扫描。删掉整个 client 目录就回到干净状态。
///
/// 客户端进程**不**放进 Job Object：启动器退出时游戏继续跑是预期行为。
/// </summary>
public sealed class ClientProvisioner
{
    public const string ExeName = "CA.exe";

    /// <summary>resource 子模块里放 exe/dll 的目录（原样复制到客户端目录）。</summary>
    public const string LooseDirName = "other";

    /// <summary>需要打包成 idd/idx 的资源目录名（与客户端根目录下的包名一致）。</summary>
    public static readonly string[] ArchiveNames = ["fx", "CharTE", "EmoTE"];

    private const string StampFileName = "idd-stamps.json";

    private readonly LogBus _log;
    private Process? _client;

    public ClientProvisioner(LogBus log) => _log = log;

    /// <summary>命令行模式下把同一批日志同步打到控制台（GUI 里留空）。</summary>
    public Action<string>? Echo { get; set; }

    private void Say(LogLevel level, string text)
    {
        _log.Write(level, "CLIENT", text);
        Echo?.Invoke(text);
    }

    /// <summary>客户端自行退出（或被关掉）时回调，参数是退出码。</summary>
    public event Action<int>? Exited;

    public string ExePath => Path.Combine(AppPaths.ClientDir, ExeName);

    public bool IsRunning
    {
        get
        {
            try
            {
                return _client is { HasExited: false };
            }
            catch
            {
                return false;
            }
        }
    }

    public int ProcessId
    {
        get
        {
            try
            {
                return _client?.Id ?? 0;
            }
            catch
            {
                return 0;
            }
        }
    }

    /// <summary>resource 子模块是否已落地（拿 other\CA.exe 当标记）。</summary>
    public static bool ResourceAvailable
        => File.Exists(Path.Combine(AppPaths.ResourceDir, LooseDirName, ExeName));

    /// <summary>客户端文件是否齐（exe + 三个 idx）。只做文件存在判断，供 UI 快速取状态。</summary>
    public static bool IsPrepared
        => File.Exists(Path.Combine(AppPaths.ClientDir, ExeName))
           && ArchiveNames.All(n => File.Exists(Path.Combine(AppPaths.ClientDir, n + ".idx")));

    /// <summary>三个资源包的总字节数（UI 上显示个头）。</summary>
    public static long ArchiveBytes()
    {
        long sum = 0;
        foreach (var name in ArchiveNames)
        {
            foreach (var ext in new[] { ".idd", ".idx" })
            {
                var info = new FileInfo(Path.Combine(AppPaths.ClientDir, name + ext));
                if (info.Exists) sum += info.Length;
            }
        }

        return sum;
    }

    // ── 准备客户端目录 ──────────────────────────────────────────────────

    /// <summary>
    /// 把客户端目录准备到可启动状态：拉子模块（需要时）→ 复制 exe/dll → 打包 idd/idx。
    /// 可重复调用，已经是最新状态时几乎不花时间。
    /// </summary>
    public async Task PrepareAsync(AppConfig cfg, IProgress<SetupProgress>? progress, bool forceRepack,
        CancellationToken ct)
    {
        if (!AppPaths.SourceExists)
        {
            throw new InvalidOperationException("The source has not downloaded yet, so the client cannot be prepared.");
        }

        Directory.CreateDirectory(AppPaths.ClientDir);

        // 1) 客户端资源（resource 子模块，首次要下几百 MB）
        if (!ResourceAvailable)
        {
            Say(LogLevel.Info, "Client resources are not initialized; fetching the resource submodule");
            await GitSource.EnsureSubmoduleAsync(cfg, progress, ct);

            if (!ResourceAvailable)
            {
                throw new InvalidOperationException(
                    $"Client resources are still missing: {ExeName} not found in {Path.Combine(AppPaths.ResourceDir, LooseDirName)}");
            }

            Say(LogLevel.Success, "Client resources are ready");
        }
        else if (GitSource.SubmoduleOutdated())
        {
            // 作者推了新客户端资源（补丁、贴图…）并 bump 了源码里的 gitlink：
            // 不做这一步的话，玩家端会永远停在第一次拉下来的那一版。
            Say(LogLevel.Info, "Client resources are older than the source repo; syncing the resource submodule");
            await GitSource.EnsureSubmoduleAsync(cfg, progress, ct);
            Say(LogLevel.Success, "Client resources updated");
        }

        // 2) 散装文件：exe / dll / 字体 / nm.bik
        var copied = await Task.Run(() => SyncLooseFiles(progress, ct), ct);
        if (copied > 0)
        {
            Say(LogLevel.Info, $"Copied {copied} client program files to {AppPaths.ClientDir}");
        }

        // 3) 资源包
        await Task.Run(() => BuildArchives(progress, forceRepack, ct), ct);
    }

    /// <summary>把 resource\other 下的文件按相对路径镜像到客户端目录，内容没变就跳过。</summary>
    private int SyncLooseFiles(IProgress<SetupProgress>? progress, CancellationToken ct)
    {
        var srcRoot = Path.Combine(AppPaths.ResourceDir, LooseDirName);
        if (!Directory.Exists(srcRoot))
        {
            throw new DirectoryNotFoundException($"Client program folder not found: {srcRoot}");
        }

        var files = Directory.GetFiles(srcRoot, "*", SearchOption.AllDirectories);
        var copied = 0;

        for (var i = 0; i < files.Length; i++)
        {
            ct.ThrowIfCancellationRequested();

            var rel = Path.GetRelativePath(srcRoot, files[i]);
            var dst = Path.Combine(AppPaths.ClientDir, rel);
            if (NeedsCopy(files[i], dst))
            {
                Directory.CreateDirectory(Path.GetDirectoryName(dst)!);
                File.Copy(files[i], dst, overwrite: true);
                copied++;
            }

            progress?.Report(new SetupProgress($"Copying client files ({i + 1}/{files.Length})",
                (i + 1) * 100.0 / Math.Max(files.Length, 1)));
        }

        return copied;
    }

    /// <summary>源文件更新、大小不同或目标缺失时需要复制。</summary>
    private static bool NeedsCopy(string src, string dst)
    {
        var target = new FileInfo(dst);
        if (!target.Exists) return true;

        var source = new FileInfo(src);
        if (target.Length != source.Length) return true;

        // FAT/NTFS 时间戳精度差异，给 2 秒容差
        return source.LastWriteTimeUtc - target.LastWriteTimeUtc > TimeSpan.FromSeconds(2);
    }

    /// <summary>
    /// 逐个资源目录打包。指纹没变且输出还在就跳过 —— 这是「点启动」的常规路径，
    /// 只花一次目录扫描的时间。
    /// </summary>
    private void BuildArchives(IProgress<SetupProgress>? progress, bool forceRepack, CancellationToken ct)
    {
        var stamps = LoadStamps();
        var total = ArchiveNames.Length;

        for (var index = 0; index < total; index++)
        {
            ct.ThrowIfCancellationRequested();

            var name = ArchiveNames[index];
            var dir = Path.Combine(AppPaths.ResourceDir, name);
            var outBase = Path.Combine(AppPaths.ClientDir, name);

            if (!File.Exists(Path.Combine(dir, IddArchiveBuilder.ManifestName)))
            {
                Say(LogLevel.Warn, $"Skipping {name}: no {IddArchiveBuilder.ManifestName} in {dir}");
                continue;
            }

            var stamp = IddArchiveBuilder.ComputeStamp(dir);
            var upToDate = !forceRepack
                           && stamps.TryGetValue(name, out var old) && old == stamp
                           && File.Exists(outBase + ".idd")
                           && File.Exists(outBase + ".idx");

            if (upToDate)
            {
                Say(LogLevel.Info, $"{name} unchanged, skipping repack ({ByteSize.Format(new FileInfo(outBase + ".idd").Length)})");
                continue;
            }

            Say(LogLevel.Info, $"Packing {name}: {dir} → {name}.idd / {name}.idx");

            var outer = progress;
            var offset = index;
            var inner = outer is null
                ? null
                : new Progress<SetupProgress>(p => outer.Report(new SetupProgress(p.Phase,
                    (offset + Math.Clamp(p.Percent, 0, 100) / 100.0) / total * 100)));

            var result = IddArchiveBuilder.Build(dir, outBase, msg => Say(LogLevel.Info, msg), inner, ct);

            Say(LogLevel.Success,
                $"{name}.idd packed: {result.Summary}, " +
                $"{ByteSize.Format(result.IddSize)} / idx {ByteSize.Format(result.IdxSize)}, " +
                $"took {result.Elapsed.TotalSeconds:0.0}s");

            stamps[name] = stamp;
            SaveStamps(stamps);
        }
    }

    private static Dictionary<string, string> LoadStamps()
    {
        var path = Path.Combine(AppPaths.ClientDir, StampFileName);
        try
        {
            if (!File.Exists(path)) return new Dictionary<string, string>();
            return JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(path))
                   ?? new Dictionary<string, string>();
        }
        catch
        {
            return new Dictionary<string, string>();
        }
    }

    private static void SaveStamps(Dictionary<string, string> stamps)
    {
        try
        {
            Directory.CreateDirectory(AppPaths.ClientDir);
            File.WriteAllText(Path.Combine(AppPaths.ClientDir, StampFileName),
                JsonSerializer.Serialize(stamps, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch
        {
            // 记不住指纹最多下次重新打包，不影响启动
        }
    }

    // ── 启动 / 跟踪 ────────────────────────────────────────────────────

    /// <summary>启动客户端。已在运行时不会再起一个。</summary>
    public void Launch()
    {
        if (IsRunning)
        {
            Say(LogLevel.Warn, $"Client is already running (PID {ProcessId})");
            return;
        }

        if (!File.Exists(ExePath))
        {
            throw new FileNotFoundException($"Client executable not found: {ExePath}", ExePath);
        }

        var psi = new ProcessStartInfo
        {
            FileName = ExePath,
            WorkingDirectory = AppPaths.ClientDir,
            // 不用 ShellExecute：需要真实进程句柄才能跟踪退出
            UseShellExecute = false,
        };

        var proc = Process.Start(psi) ?? throw new InvalidOperationException("Unable to start the client process");
        proc.EnableRaisingEvents = true;
        proc.Exited += OnClientExited;

        _client = proc;
        Say(LogLevel.Success, $"Client started (PID {proc.Id}), working directory {AppPaths.ClientDir}");
    }

    private void OnClientExited(object? sender, EventArgs e)
    {
        var code = -1;
        try
        {
            code = sender is Process p ? p.ExitCode : -1;
            (sender as Process)?.Dispose();
        }
        catch
        {
            // ignore
        }

        Exited?.Invoke(code);
    }

    public void Dispose()
    {
        // 客户端是独立进程，退出启动器时不管它；这里只解绑事件避免泄漏
        if (_client is null) return;
        try
        {
            _client.Exited -= OnClientExited;
        }
        catch
        {
            // ignore
        }
    }
}
