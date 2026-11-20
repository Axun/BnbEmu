using System.IO;
using System.Security.Cryptography;
using System.Text;
using BnbLauncher.Models;
using LibGit2Sharp;

namespace BnbLauncher.Services;

/// <summary>工作区状态：有没有未提交改动，以及这些改动的内容指纹。</summary>
public readonly record struct WorkTreeState(bool Dirty, string Fingerprint);

/// <summary>检查更新的结果。</summary>
public sealed class SourceStatus
{
    /// <summary>runtime\source 已经是 git 仓库。</summary>
    public bool RepoExists { get; init; }

    public CommitInfo? LocalHead { get; init; }
    public CommitInfo? RemoteHead { get; init; }

    /// <summary>本地 HEAD 与远端分支 tip 一致。</summary>
    public bool UpToDate { get; init; }

    /// <summary>本地不在远端历史里（远端被 force push / 本地有额外提交）。</summary>
    public bool Diverged { get; init; }

    /// <summary>工作区有未提交改动（拉取会丢弃）。</summary>
    public bool Dirty { get; init; }

    /// <summary>远端可达但本地不可达的提交数。列表不截断，所以这是真实数量。</summary>
    public int Ahead { get; init; }

    /// <summary>本地 → 远端之间的全部新提交（新 → 旧，不截断）。</summary>
    public List<CommitInfo> NewCommits { get; init; } = [];

    public bool HasUpdate => RepoExists && !UpToDate;
}

/// <summary>一次 clone / pull 的结果。</summary>
public sealed class SyncResult
{
    public bool Cloned { get; init; }
    public bool UpToDate { get; init; }

    /// <summary>本来就与远端一致，但工作区有改动，已强制对齐。</summary>
    public bool Repaired { get; init; }

    public bool Diverged { get; init; }

    /// <summary>本次更新涉及的全部提交数（真实值，不受展示条数影响）。</summary>
    public int CommitCount { get; init; }
    public CommitInfo? From { get; init; }
    public CommitInfo? Head { get; init; }
}

/// <summary>
/// runtime\source 的 git 操作（LibGit2Sharp，自带 libgit2，目标机器不需要装 git）。
///
/// 首次：git clone 当前分支的最新提交；
/// 之后：git fetch 判断有没有更新，更新则 git pull（快进）到远端 tip。
///
/// 子模块（.gitmodules 里的 resource，客户端资源）不跟源码一起拉：
/// 克隆时 RecurseSubmodules=false，源码侧保持"只有服务端代码"；
/// 用户点「启动客户端」时才由 <see cref="EnsureSubmoduleAsync"/> 单独初始化。
/// </summary>
public static class GitSource
{
    private const string RemoteName = "origin";

    private static readonly object InitGate = new();
    private static bool _initialized;

    public static string Version { get; private set; } = "(not initialized)";
    public static string? InitError { get; private set; }

    /// <summary>
    /// runtime\source 是否是真正的 git 仓库。
    /// 刻意先看 .git 是否存在：Repository.IsValid 会顺着父目录找仓库，
    /// 而启动器常被放在某个仓库目录里（开发时就在 BnbEmu\launcher\dist 下跑），
    /// 那样会把外层仓库误判成源码目录。
    /// </summary>
    public static bool Exists
    {
        get
        {
            var dir = AppPaths.SourceDir;
            if (!Directory.Exists(dir)) return false;
            return Directory.Exists(Path.Combine(dir, ".git")) || File.Exists(Path.Combine(dir, ".git"));
        }
    }

    /// <summary>幂等初始化：定位原生库、关闭 owner 校验、取版本号。</summary>
    public static void Initialize()
    {
        if (_initialized) return;
        lock (InitGate)
        {
            if (_initialized) return;
            _initialized = true;

            try
            {
                // 单文件发布时原生库被 self-extract 释放到临时目录，已在默认探测路径里；
                // 非单文件运行/发布时它躺在 runtimes\win-x64\native，显式指一下更稳。
                var native = new[]
                    {
                        Path.Combine(AppContext.BaseDirectory, "runtimes", "win-x64", "native"),
                        Path.Combine(AppContext.BaseDirectory, "native"),
                    }
                    .FirstOrDefault(d => Directory.Exists(d) && Directory.EnumerateFiles(d, "git2-*.dll").Any());

                if (native is not null) GlobalSettings.NativeLibraryPath = native;

                // 源码目录可能是从别人机器/网络盘拷过来的，关掉 owner 校验避免 open 直接失败
                GlobalSettings.SetOwnerValidation(false);

                Version = GlobalSettings.Version?.ToString() ?? "unknown";
            }
            catch (Exception ex)
            {
                InitError = ex.Message;
                Version = "(libgit2 failed to load)";
            }
        }
    }

    /// <summary>读取本地 HEAD（没有仓库时返回 null）。</summary>
    public static CommitInfo? TryReadHead()
    {
        if (!Exists) return null;

        try
        {
            using var repo = new Repository(AppPaths.SourceDir);
            return repo.Head.Tip is null ? null : ToCommit(repo.Head.Tip);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// 完整提交历史（给「提交记录」用，不截断）。
    /// 优先从远端跟踪引用遍历：检测更新时已经 fetch 过对象，所以即便本地 HEAD 落后，
    /// 待拉取的新提交也在列表里；没有跟踪引用（例如从未检测更新）时退回本地 HEAD。
    /// 全程只读本地对象库，不发网络请求。
    /// </summary>
    public static List<CommitInfo> AllCommits(AppConfig cfg)
    {
        if (!Exists) return [];

        using var repo = new Repository(AppPaths.SourceDir);
        var tip = RemoteTip(repo, cfg.Branch) ?? repo.Head.Tip;
        if (tip is null) return [];

        return repo.Commits
            .QueryBy(new CommitFilter { IncludeReachableFrom = tip })
            .Select(ToCommit)
            .ToList();
    }

    /// <summary>工作区是否有未提交改动（拉取会直接丢弃它们）。</summary>
    public static bool HasLocalChanges()
    {
        if (!Exists) return false;

        try
        {
            using var repo = new Repository(AppPaths.SourceDir);
            return repo.RetrieveStatus(StatusOptions()).IsDirty;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>
    /// 与「编译输入」相关的工作区改动指纹：只看 src\ 与 pom.xml，
    /// 用「路径 + 状态 + 大小 + 修改时间」汇总成哈希。
    ///
    /// 为什么不直接看 IsDirty：用户在 runtime\source 里手改源码是被支持的（启动器同步前会先确认），
    /// 如果他改完编译过一次、随后反复点启动，光看「脏」会每次都触发全量重编。
    /// 记下指纹就能区分「改过了没重编」和「已经编译过这批改动」。
    /// 反过来，服务运行期可能在源码根目录写日志/缓存，这些不在过滤范围里，不会让指纹抖动。
    /// </summary>
    public static WorkTreeState ReadWorkTree()
    {
        if (!Exists) return new WorkTreeState(false, "no-repo");

        try
        {
            using var repo = new Repository(AppPaths.SourceDir);
            var entries = repo.RetrieveStatus(StatusOptions())
                .Where(e => !e.State.HasFlag(FileStatus.Ignored))
                .Select(e => e.FilePath.Replace('\\', '/').TrimEnd('/'))
                .Where(IsBuildInput)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderBy(p => p, StringComparer.Ordinal)
                .Select(p =>
                {
                    var full = Path.Combine(AppPaths.SourceDir, p.Replace('/', Path.DirectorySeparatorChar));
                    try
                    {
                        if (Directory.Exists(full)) return p + "|dir";
                        var info = new FileInfo(full);
                        // 删除的条目没有文件，用固定标记占位，仍然参与指纹
                        return info.Exists ? $"{p}|{info.Length}|{info.LastWriteTimeUtc.Ticks}" : p + "|missing";
                    }
                    catch
                    {
                        return p + "|error";
                    }
                })
                .ToList();

            if (entries.Count == 0) return new WorkTreeState(false, "clean");

            var joined = string.Join("\n", entries);
            var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(joined)));
            return new WorkTreeState(true, hash[..16].ToLowerInvariant());
        }
        catch
        {
            return new WorkTreeState(false, "unknown");
        }
    }

    /// <summary>会影响到编译产物的路径：Kotlin 源与构建脚本，其余改动不需要重编。</summary>
    private static bool IsBuildInput(string path)
        => path.Equals("pom.xml", StringComparison.OrdinalIgnoreCase)
           || path.StartsWith("src/", StringComparison.OrdinalIgnoreCase);

    // ── 检查更新 ────────────────────────────────────────────────────────

    public static Task<SourceStatus> CheckAsync(AppConfig cfg, CancellationToken ct = default)
        => Task.Run(() => Check(cfg, ct), ct);

    private static SourceStatus Check(AppConfig cfg, CancellationToken ct)
    {
        Initialize();
        ThrowIfInitFailed();

        if (!Exists)
        {
            // 还没有本地仓库：只用 ls-remote 取远端 tip，不下载对象
            return new SourceStatus { RepoExists = false, RemoteHead = ReadRemoteHead(cfg, ct) };
        }

        using var repo = new Repository(AppPaths.SourceDir);
        EnsureRemote(repo, cfg);

        ct.ThrowIfCancellationRequested();
        Fetch(repo, cfg, null, ct);

        var remote = RemoteTip(repo, cfg.Branch)
                     ?? throw new InvalidOperationException(ExplainMissingBranch(cfg));

        var local = repo.Head.Tip;
        if (local is null || local.Sha == remote.Sha)
        {
            return new SourceStatus
            {
                RepoExists = true,
                LocalHead = local is null ? null : ToCommit(local),
                RemoteHead = ToCommit(remote),
                UpToDate = true,
                Dirty = local is not null && repo.RetrieveStatus(StatusOptions()).IsDirty,
            };
        }

        var diverged = repo.ObjectDatabase.FindMergeBase(local, remote)?.Sha != local.Sha;
        var commits = NewCommits(repo, local, remote);

        return new SourceStatus
        {
            RepoExists = true,
            LocalHead = ToCommit(local),
            RemoteHead = ToCommit(remote),
            Diverged = diverged,
            Ahead = commits.Count,
            NewCommits = commits,
            Dirty = repo.RetrieveStatus(StatusOptions()).IsDirty,
        };
    }

    // ── 克隆 / 拉取 ─────────────────────────────────────────────────────

    /// <summary>
    /// 把 runtime\source 对齐到远端分支 tip：没有仓库就 clone，有仓库就 pull。
    /// </summary>
    public static Task<SyncResult> SyncAsync(AppConfig cfg, IProgress<SetupProgress>? progress,
        CancellationToken ct = default)
        => Task.Run(() => Exists ? Update(cfg, progress, ct) : Clone(cfg, progress, ct), ct);

    private static SyncResult Clone(AppConfig cfg, IProgress<SetupProgress>? progress, CancellationToken ct)
    {
        Initialize();
        ThrowIfInitFailed();
        CleanLegacyArtifacts();

        var target = AppPaths.SourceDir;
        string? backup = null;

        if (Directory.Exists(target))
        {
            // 旧版本是下载 zip 解开的，没有 .git；也可能是坏掉的仓库。
            // 先整目录挪开，克隆成功再删，失败还能还原。
            progress?.Report(SetupProgress.Indeterminate("Cleaning up old source folder"));
            backup = target + ".old-" + Guid.NewGuid().ToString("N")[..6];
            try
            {
                Directory.Move(target, backup);
            }
            catch (IOException ex)
            {
                throw new InvalidOperationException(
                    "The source folder is in use and cannot be updated. Stop the server (or close any window using it) and try again.", ex);
            }
            catch (UnauthorizedAccessException ex)
            {
                throw new InvalidOperationException("No permission to modify the source folder: " + target, ex);
            }
        }

        try
        {
            progress?.Report(new SetupProgress("Cloning source", 0));

            var options = new CloneOptions(CreateFetchOptions(cfg, progress, "Cloning source", ct))
            {
                BranchName = cfg.Branch,
                Checkout = true,
                IsBare = false,
                // 子模块（resource 客户端资源）先不拉
                RecurseSubmodules = false,
            };
            options.OnCheckoutProgress = (_, done, total) =>
            {
                if (total <= 0) return;
                progress?.Report(new SetupProgress($"Checking out source files ({done}/{total})", done * 100.0 / total));
            };

            Repository.Clone(cfg.CloneUrl, target, options);
            ct.ThrowIfCancellationRequested();

            if (!File.Exists(Path.Combine(target, "pom.xml")))
                throw new InvalidOperationException($"Clone finished but pom.xml was not found; branch {cfg.Branch} may not contain the server source");
        }
        catch (Exception ex)
        {
            TryDeleteDirectory(target);
            if (backup is not null && !Directory.Exists(target))
            {
                try { Directory.Move(backup, target); } catch { }
            }

            throw Map(ex, cfg);
        }

        if (backup is not null) ZipHelper.TryDeleteDirectory(backup);

        var head = TryReadHead();
        progress?.Report(new SetupProgress($"Source cloned {head?.ShortSha}", 100));

        return new SyncResult { Cloned = true, Head = head, CommitCount = 0 };
    }

    private static SyncResult Update(AppConfig cfg, IProgress<SetupProgress>? progress, CancellationToken ct)
    {
        Initialize();
        ThrowIfInitFailed();

        Repository repo;
        try
        {
            repo = new Repository(AppPaths.SourceDir);
        }
        catch (LibGit2SharpException)
        {
            // .git 在但打不开（半截克隆 / 损坏）：当成没有仓库，整目录重新 clone
            return Clone(cfg, progress, ct);
        }

        using (repo)
        {
            return UpdateCore(repo, cfg, progress, ct);
        }
    }

    private static SyncResult UpdateCore(Repository repo, AppConfig cfg, IProgress<SetupProgress>? progress,
        CancellationToken ct)
    {
        EnsureRemote(repo, cfg);

        var before = repo.Head.Tip;

        progress?.Report(SetupProgress.Indeterminate("Connecting to remote repository"));
        Fetch(repo, cfg, progress, ct);

        var remote = RemoteTip(repo, cfg.Branch) ?? throw new InvalidOperationException(ExplainMissingBranch(cfg));

        if (before is not null && before.Sha == remote.Sha)
        {
            // "重新同步"也承担修复职责：版本没变但工作区被改乱了，照样对齐回 HEAD
            var repaired = false;
            if (repo.RetrieveStatus(StatusOptions()).IsDirty)
            {
                progress?.Report(SetupProgress.Indeterminate("Repairing working tree"));
                repo.Reset(ResetMode.Hard, remote, new CheckoutOptions
                {
                    CheckoutModifiers = CheckoutModifiers.Force,
                });
                repaired = true;
            }

            progress?.Report(new SetupProgress($"Up to date {Short(remote)}", 100));
            return new SyncResult
            {
                UpToDate = true,
                Repaired = repaired,
                From = ToCommit(before),
                Head = ToCommit(remote),
            };
        }

        // 设置里改过分支：先切到配置的分支（没有就按远端 tip 新建并跟踪）
        if (!string.Equals(repo.Head.FriendlyName, cfg.Branch, StringComparison.Ordinal))
            SwitchBranch(repo, cfg, remote);

        var diverged = before is null || repo.ObjectDatabase.FindMergeBase(before, remote)?.Sha != before.Sha;
        var commits = before is null ? NewCommits(repo, null, remote) : NewCommits(repo, before, remote);

        progress?.Report(SetupProgress.Indeterminate("Updating working tree"));
        ct.ThrowIfCancellationRequested();

        try
        {
            // 等价于 git pull --ff-only 的结果：直接对齐远端 tip。
            // 之所以不用 Commands.Pull：它需要本地 user.name/user.email 生成签名，
            // 遇到未跟踪文件被覆盖还会抛 CheckoutConflict —— 而这里的源码目录本来就是
            // 启动器全权管理的（旧实现是整目录替换），直接对齐语义最干净。
            repo.Reset(ResetMode.Hard, remote, new CheckoutOptions
            {
                CheckoutModifiers = CheckoutModifiers.Force,
            });
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or LibGit2SharpException)
        {
            throw new InvalidOperationException(
                "Failed to update the working tree; the source folder may be locked by the server. Stop the server and try again. (" + ex.Message + ")", ex);
        }

        progress?.Report(new SetupProgress($"Updated to {Short(remote)}", 100));

        return new SyncResult
        {
            Diverged = diverged,
            CommitCount = commits.Count,
            From = before is null ? null : ToCommit(before),
            Head = ToCommit(remote),
        };
    }

    // ── 子模块（客户端资源） ────────────────────────────────────────────

    /// <summary>
    /// 初始化 / 更新 <c>resource</c> 子模块。客户端（CA.exe + 资源目录）全在这块里，
    /// 体积很大，所以只在用户真的要启动客户端时才拉，不跟源码一起下。
    ///
    /// 正常路径走 libgit2 的子模块更新（目标机器不需要装 git）；
    /// <c>.gitmodules</c> 里是相对 URL（<c>../BnbEmuResource.git</c>），老版本 libgit2
    /// 偶尔解析不了，这时按父仓库地址自己拼绝对 URL 直接 clone 一份兜底。
    /// </summary>
    public static Task EnsureSubmoduleAsync(AppConfig cfg, IProgress<SetupProgress>? progress,
        CancellationToken ct = default)
        => Task.Run(() => EnsureSubmodule(cfg, progress, ct), ct);

    private static void EnsureSubmodule(AppConfig cfg, IProgress<SetupProgress>? progress, CancellationToken ct)
    {
        Initialize();
        ThrowIfInitFailed();

        if (!Exists) throw new InvalidOperationException("The source has not downloaded yet, so client resources cannot be fetched.");

        const string name = "resource";
        using var repo = new Repository(AppPaths.SourceDir);
        var sub = repo.Submodules[name]
                  ?? throw new InvalidOperationException("The source has no resource submodule (.gitmodules is missing or has been modified)");

        var target = Path.IsPathRooted(sub.Path) ? sub.Path : Path.Combine(AppPaths.SourceDir, sub.Path);
        // 相对地址按父仓库（可能已经带镜像前缀）拼成绝对地址；如果 .gitmodules 直接写了 github.com
        // 的绝对地址，这里再补一次镜像前缀 —— Rewrite 幂等，拼过的不会套两层。
        var url = GitMirror.Rewrite(ResolveRelativeUrl(cfg.CloneUrl, sub.Url), cfg.Mirror);

        // 源码仓库 pin 的子模块提交：HEAD 树里的 gitlink，索引缺失时再退回 index
        var want = sub.HeadCommitId ?? sub.IndexCommitId;

        // 刻意不用 repo.Submodules.Update()：
        //   LibGit2Sharp 0.32 的 SubmoduleUpdateOptions 没有 allow_fetch 开关（原生结构体恒为 0），
        //   libgit2 于是只在本地找 pin 的提交，找不到就直接抛 "object not found - no match for id"，
        //   一次都不联网。只要某次 clone 被中断（断网 / 关窗口 / 休眠 / 磁盘满），留下
        //   「resource\.git 在、.git\modules\resource 是空壳」的半成品，之后每次重试都是这个错；
        //   删掉 resource 目录也救不回来（换一个 "failed to resolve path ...\.git"）。
        //   这里改成 clone / fetch / checkout 自己走：残缺对象库直接 fetch 续传，
        //   真打不开了才清干净重来 —— 任何一次失败都不会把状态锁死。

        progress?.Report(SetupProgress.Indeterminate("Downloading client resources (the first pull is large)"));

        try
        {
            if (!TryOpenRepository(target, out var subRepo))
            {
                // 目录不存在 / 只剩半截 .git / 根本不成仓库：连 .git\modules\resource 空壳一起清掉再 clone
                DeleteSubmoduleLeftovers(name, target);

                if (Directory.Exists(target) && Directory.EnumerateFileSystemEntries(target).Any())
                {
                    throw new InvalidOperationException(
                        $"The client resource folder is broken and cannot be deleted: {target}. " +
                        "Close anything using it (Explorer preview, antivirus) and try again.");
                }

                Repository.Clone(url, target, new CloneOptions(
                    CreateFetchOptions(cfg, progress, "Downloading client resources", ct))
                {
                    Checkout = true,
                    IsBare = false,
                    RecurseSubmodules = false,
                });

                subRepo = new Repository(target);
            }

            using (subRepo)
            {
                if (want is not null && subRepo.Lookup<Commit>(want) is null)
                {
                    // 本地缺 pin 的提交：fetch 一次（已有对象不会重下；中断过的半成品在这里续传）
                    progress?.Report(SetupProgress.Indeterminate("Downloading client resources (resuming)"));
                    FetchSubmodule(subRepo, url, cfg, progress, ct);

                    if (subRepo.Lookup<Commit>(want) is null)
                    {
                        throw new InvalidOperationException(
                            $"The client resource submodule {url} has no commit {want.Sha}, which the source repo pins. " +
                            "Push the submodule first, or update its gitlink in the source repo.");
                    }
                }

                // clone 拿的是远端分支 tip，可能和父仓库 pin 的不一致（作者刚推了新资源 / pin 回退了），统一对齐到 pin
                if (want is not null && subRepo.Head.Tip?.Sha != want.Sha)
                {
                    progress?.Report(SetupProgress.Indeterminate("Checking out client resources"));
                    Commands.Checkout(subRepo, subRepo.Lookup<Commit>(want)!, new CheckoutOptions
                    {
                        CheckoutModifiers = CheckoutModifiers.Force,
                    });
                }
            }
        }
        catch (UserCancelledException)
        {
            throw new OperationCanceledException(ct);
        }
        catch (Exception ex) when (ex is LibGit2SharpException or IOException or UnauthorizedAccessException)
        {
            // 已经下到的对象留着（下次 fetch 能续传）；但把"看着像仓库、其实用不了"的残骸清掉，
            // 否则下次打开成功又走不到 clone，永远停在原地。
            if (!TryOpenRepository(target, out var probe)) DeleteSubmoduleLeftovers(name, target);
            else probe.Dispose();

            var mapped = Map(ex, cfg);
            throw new InvalidOperationException("Failed to download client resources. " + mapped.Message, mapped);
        }

        ct.ThrowIfCancellationRequested();
        progress?.Report(new SetupProgress("Client resources ready", 100));
    }

    /// <summary>
    /// resource 子模块的工作区提交是否和源码 pin 的不一致（作者推了新资源，比如客户端补丁）。
    /// 只看本地提交号，不联网；宁可漏判也不误判成"需要重下"，所以目录不是正经仓库时一律返回 false。
    /// </summary>
    public static bool SubmoduleOutdated()
    {
        try
        {
            if (!Exists) return false;

            using var repo = new Repository(AppPaths.SourceDir);
            var sub = repo.Submodules["resource"];
            if (sub is null) return false;

            var want = sub.HeadCommitId ?? sub.IndexCommitId;
            if (want is null) return false;

            var target = Path.IsPathRooted(sub.Path) ? sub.Path : Path.Combine(AppPaths.SourceDir, sub.Path);
            if (!TryOpenRepository(target, out var subRepo)) return false;

            using (subRepo)
            {
                var have = subRepo.Head.Tip?.Sha;
                return have is not null && have != want.Sha;
            }
        }
        catch
        {
            // 判断失败就当作不需要同步，别因此挡住启动
            return false;
        }
    }

    /// <summary>打开目录里那个仓库（只认目录下的 .git，不往上找，避免误开成父仓库）。</summary>
    private static bool TryOpenRepository(string path, out Repository repo)
    {
        repo = null!;

        var gitPath = Path.Combine(path, ".git");
        if (!Directory.Exists(gitPath) && !File.Exists(gitPath)) return false;

        try
        {
            repo = new Repository(path);
            return true;
        }
        catch (Exception ex) when (ex is LibGit2SharpException or IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    private static void FetchSubmodule(Repository repo, string url, AppConfig cfg,
        IProgress<SetupProgress>? progress, CancellationToken ct)
    {
        var remote = repo.Network.Remotes[RemoteName];
        if (remote is null) repo.Network.Remotes.Add(RemoteName, url);
        else if (!string.Equals(remote.Url, url, StringComparison.OrdinalIgnoreCase))
            repo.Network.Remotes.Update(RemoteName, r => r.Url = url);

        Commands.Fetch(repo, RemoteName, ["+refs/heads/*:refs/remotes/origin/*"],
            CreateFetchOptions(cfg, progress, "Downloading client resources", ct), "BnbLauncher submodule fetch");
    }

    /// <summary>清掉子模块的两种残留：工作区目录，以及 .git\modules\&lt;name&gt; 那个空壳仓库。</summary>
    private static void DeleteSubmoduleLeftovers(string name, string target)
    {
        TryDeleteTree(target);
        TryDeleteTree(Path.Combine(AppPaths.SourceDir, ".git", "modules", name));
    }

    /// <summary>把 <c>../BnbEmuResource.git</c> 这种相对子模块地址按父仓库地址拼成绝对 URL。</summary>
    private static string ResolveRelativeUrl(string parentUrl, string subUrl)
    {
        if (!subUrl.StartsWith("../", StringComparison.Ordinal)) return subUrl;

        var baseUrl = parentUrl;
        while (subUrl.StartsWith("../", StringComparison.Ordinal))
        {
            subUrl = subUrl[3..];
            var cut = baseUrl.LastIndexOf('/');
            baseUrl = cut > 8 ? baseUrl[..cut] : baseUrl;
        }

        return baseUrl.TrimEnd('/') + "/" + subUrl;
    }

    // ── git 细节 ────────────────────────────────────────────────────────

    private static FetchOptions CreateFetchOptions(AppConfig cfg, IProgress<SetupProgress>? progress,
        string phase, CancellationToken ct)
    {
        var lastPercent = -1;

        var options = new FetchOptions
        {
            CredentialsProvider = GitNetwork.Credentials,
            TagFetchMode = TagFetchMode.None,
            Prune = false,
        };
        GitNetwork.ApplyProxy(options, cfg);

        options.OnTransferProgress = p =>
        {
            if (ct.IsCancellationRequested) return false;
            if (progress is null) return true;

            var percent = p.TotalObjects > 0 ? p.ReceivedObjects * 100.0 / p.TotalObjects : -1;
            var bucket = (int)percent;
            if (bucket == lastPercent) return true; // 回调很密，按百分比去重
            lastPercent = bucket;

            progress.Report(percent < 0
                ? SetupProgress.Indeterminate($"{phase} ({p.ReceivedObjects} objects)")
                : new SetupProgress($"{phase} ({p.ReceivedObjects}/{p.TotalObjects})", percent));
            return true;
        };

        options.OnProgress = _ => !ct.IsCancellationRequested;

        return options;
    }

    private static void Fetch(Repository repo, AppConfig cfg, IProgress<SetupProgress>? progress, CancellationToken ct)
    {
        var refSpec = $"+refs/heads/{cfg.Branch}:refs/remotes/{RemoteName}/{cfg.Branch}";

        try
        {
            Commands.Fetch(repo, RemoteName, [refSpec], CreateFetchOptions(cfg, progress, "Fetching source", ct),
                "BnbLauncher fetch");
        }
        catch (UserCancelledException)
        {
            throw new OperationCanceledException(ct);
        }
        catch (LibGit2SharpException ex)
        {
            throw Map(ex, cfg);
        }
    }

    private static void EnsureRemote(Repository repo, AppConfig cfg)
    {
        var remote = repo.Network.Remotes[RemoteName];
        if (remote is null)
        {
            repo.Network.Remotes.Add(RemoteName, cfg.CloneUrl);
            return;
        }

        if (!string.Equals(remote.Url, cfg.CloneUrl, StringComparison.OrdinalIgnoreCase))
        {
            repo.Network.Remotes.Update(RemoteName, r => r.Url = cfg.CloneUrl);
        }
    }

    private static void SwitchBranch(Repository repo, AppConfig cfg, Commit remote)
    {
        var branch = repo.Branches[cfg.Branch];
        if (branch is null)
        {
            branch = repo.CreateBranch(cfg.Branch, remote);
            repo.Branches.Update(branch,
                b => b.Remote = RemoteName,
                b => b.UpstreamBranch = $"refs/heads/{cfg.Branch}");
        }

        Commands.Checkout(repo, branch, new CheckoutOptions
        {
            CheckoutModifiers = CheckoutModifiers.Force,
        });
    }

    private static Commit? RemoteTip(Repository repo, string branch)
        => repo.Lookup<Commit>($"refs/remotes/{RemoteName}/{branch}");

    /// <summary>没有本地仓库时，用 ls-remote 取远端分支 tip（不下载对象）。</summary>
    private static CommitInfo? ReadRemoteHead(AppConfig cfg, CancellationToken ct)
    {
        ct.ThrowIfCancellationRequested();

        IEnumerable<Reference> refs;
        try
        {
            refs = Repository.ListRemoteReferences(cfg.CloneUrl, GitNetwork.Credentials,
                GitNetwork.ProxyOptions(cfg));
        }
        catch (LibGit2SharpException ex)
        {
            throw Map(ex, cfg);
        }

        var sha = refs
            .Where(r => string.Equals(r.CanonicalName, $"refs/heads/{cfg.Branch}", StringComparison.Ordinal))
            .Select(r => (r as DirectReference)?.TargetIdentifier ?? r.TargetIdentifier)
            .FirstOrDefault(s => !string.IsNullOrEmpty(s));

        return sha is null ? null : CommitInfo.FromSha(sha);
    }

    /// <summary>
    /// 远端 tip 可达、本地 HEAD 不可达的全部提交（新 → 旧）。
    /// 刻意不截断：SourceStatus.Ahead / SyncResult.CommitCount 直接取它的 Count，
    /// 界面上的「N 个新提交」才是真实值；提交记录弹窗也用它显示全部待更新提交。
    /// </summary>
    private static List<CommitInfo> NewCommits(Repository repo, Commit? from, Commit to)
    {
        var filter = new CommitFilter { IncludeReachableFrom = to };
        if (from is not null) filter.ExcludeReachableFrom = from;

        return repo.Commits.QueryBy(filter).Select(ToCommit).ToList();
    }

    private static StatusOptions StatusOptions() => new()
    {
        ExcludeSubmodules = true, // resource 子模块没初始化，别让它一直显示成"有改动"
        RecurseUntrackedDirs = false,
    };

    private static CommitInfo ToCommit(Commit commit)
        => new(commit.Sha, commit.MessageShort ?? commit.Message ?? "", commit.Author?.Name ?? "unknown",
            commit.Author?.When);

    private static string Short(Commit commit) => commit.Sha.Length > 7 ? commit.Sha[..7] : commit.Sha;

    private static void ThrowIfInitFailed()
    {
        if (InitError is not null)
            throw new InvalidOperationException("Failed to load git components: " + InitError);
    }

    // ── 旧版本残留清理 ──────────────────────────────────────────────────

    /// <summary>删掉旧实现留下的源码 zip 与解压临时目录（cache\source-*.zip 等）。</summary>
    private static void CleanLegacyArtifacts()
    {
        try
        {
            foreach (var file in Directory.EnumerateFiles(AppPaths.CacheDir, "source-*.zip"))
            {
                try { File.Delete(file); } catch { }
            }

            foreach (var dir in Directory.EnumerateDirectories(AppPaths.CacheDir, "source-extract-*"))
            {
                ZipHelper.TryDeleteDirectory(dir);
            }
        }
        catch
        {
            // 清理是尽力而为
        }
    }

    private static void TryDeleteDirectory(string path)
    {
        try
        {
            if (Directory.Exists(path)) Directory.Delete(path, recursive: true);
        }
        catch
        {
            ZipHelper.TryDeleteDirectory(path);
        }
    }

    /// <summary>
    /// 删一个可能装过 git 仓库的目录：对象文件在 Windows 上是只读的，
    /// 直接 Directory.Delete 会抛 UnauthorizedAccessException；先把属性清掉再删。
    /// </summary>
    private static void TryDeleteTree(string path)
    {
        if (!Directory.Exists(path)) return;

        try
        {
            foreach (var file in Directory.EnumerateFiles(path, "*", SearchOption.AllDirectories))
            {
                try { File.SetAttributes(file, FileAttributes.Normal); } catch { }
            }
        }
        catch
        {
            // 扫不动就当没扫，交给下面的删除尽力而为
        }

        TryDeleteDirectory(path);
    }

    // ── 错误翻译 ────────────────────────────────────────────────────────

    private static Exception Map(Exception ex, AppConfig cfg)
    {
        if (ex is OperationCanceledException or UserCancelledException) return ex;
        if (ex is InvalidOperationException) return ex;

        var message = ex.Message;

        if (message.Contains("unknown http scheme", StringComparison.OrdinalIgnoreCase))
        {
            return new InvalidOperationException(
                "Unsupported proxy scheme (libgit2 only supports http/https proxies). Enter an address " +
                "such as http://127.0.0.1:7890 under Settings → HTTPS Proxy, or clear the proxy to connect directly. Original error: " + message, ex);
        }

        if (message.Contains("could not resolve host", StringComparison.OrdinalIgnoreCase)
            || message.Contains("Failed to resolve address", StringComparison.OrdinalIgnoreCase))
        {
            var host = GitMirror.Host(cfg.Mirror);
            var hint = GitMirror.IsMirror(cfg.Mirror)
                ? $"Cannot resolve {host} (the mirror). Check your network or proxy settings, or switch Settings → GitHub access back to Direct."
                : $"Cannot resolve {host}. Check your network or proxy settings, or try a mirror under Settings → GitHub access.";
            return new InvalidOperationException(hint + " Original error: " + message, ex);
        }

        if (message.Contains("authentication", StringComparison.OrdinalIgnoreCase)
            || message.Contains("401", StringComparison.Ordinal)
            || message.Contains("too many redirects", StringComparison.OrdinalIgnoreCase))
        {
            return new InvalidOperationException(
                $"Authentication failed for {cfg.DisplayName}: the repository does not exist or is not public (this launcher only supports public repositories). Original error: " + message, ex);
        }

        if (message.Contains("remote ref", StringComparison.OrdinalIgnoreCase)
            || message.Contains("not found", StringComparison.OrdinalIgnoreCase))
        {
            return new InvalidOperationException(ExplainMissingBranch(cfg) + "Original error: " + message, ex);
        }

        if (message.Contains("unknown certificate", StringComparison.OrdinalIgnoreCase)
            || message.Contains("SSL", StringComparison.Ordinal))
        {
            return new InvalidOperationException("HTTPS certificate validation failed (often caused by a corporate proxy or antivirus interception). Original error: " + message, ex);
        }

        return new InvalidOperationException("Git operation failed: " + message, ex);
    }

    private static string ExplainMissingBranch(AppConfig cfg)
        => $"The remote has no branch {cfg.Branch} (repository {cfg.DisplayName}). Check the branch name in Settings.";
}
