using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using BnbLauncher.Models;
using BnbLauncher.Services;

namespace BnbLauncher.ViewModels;

/// <summary>
/// 提交记录弹窗的数据。两个视图都不截断：
/// ContextCommits = 本次更新带来的全部提交，AllCommitList = 仓库完整提交历史。
/// 有更新时弹窗提供切换按钮，否则只显示完整历史。
/// </summary>
public sealed class CommitListPayload : ObservableObject
{
    private List<CommitInfo> _commits = [];
    private string _subtitle = "";

    /// <summary>当前显示的列表；整体替换时同时通知 Count / IsEmpty，避免多段绑定路径不刷新。</summary>
    public List<CommitInfo> Commits
    {
        get => _commits;
        private set
        {
            if (!SetProperty(ref _commits, value)) return;
            OnPropertyChanged(nameof(Count));
            OnPropertyChanged(nameof(IsEmpty));
        }
    }

    public string Subtitle { get => _subtitle; private set => SetProperty(ref _subtitle, value); }

    public int Count => _commits.Count;
    public bool IsEmpty => _commits.Count == 0;

    public string Title { get; init; } = "Commits";
    public List<CommitInfo> ContextCommits { get; init; } = [];
    public List<CommitInfo> AllCommitList { get; init; } = [];
    public string ContextSubtitle { get; init; } = "";
    public string AllSubtitle { get; init; } = "";
    public bool ShowScopeSwitch { get; init; }

    public string ContextTabText => $"This update ({ContextCommits.Count})";
    public string AllTabText => $"Full history ({AllCommitList.Count})";

    public void ShowContext()
    {
        Commits = ContextCommits;
        Subtitle = ContextSubtitle;
    }

    public void ShowAll()
    {
        Commits = AllCommitList;
        Subtitle = AllSubtitle;
    }
}

public enum ServerState
{
    Stopped,
    Starting,
    Running,
    Stopping,
}

/// <summary>客户端（CA.exe）的状态。</summary>
public enum ClientState
{
    /// <summary>客户端目录还没拼出来（缺 exe 或资源包）。</summary>
    NotPrepared,

    /// <summary>正在拉资源 / 复制文件 / 打包。</summary>
    Preparing,

    /// <summary>一切就绪，可以点启动。</summary>
    Ready,

    /// <summary>客户端进程在跑。</summary>
    Running,
}

public sealed class MainViewModel : ObservableObject
{
    /// <summary>版本号只有一个来源：程序集里的 InformationalVersion（= csproj 的 Version）。</summary>
    public static string AppVersion => LauncherUpdater.CurrentVersion;

    private readonly LogBus _log;
    private readonly ServerRunner _server = new();
    private readonly ClientProvisioner _client;
    private AppConfig _config;
    private AppState _state;
    private JdkInfo? _jdk;
    private MavenInfo? _maven;
    private CommitInfo? _remoteHead;
    private List<CommitInfo> _pendingCommits = [];
    private CancellationTokenSource? _opCts;

    // 启动器自更新
    private LauncherUpdate? _launcherUpdate;
    private string? _stagedExe;
    private UpdateStage _updateStage = UpdateStage.None;
    private bool _updateMandatory;
    private string _updateDetail = "";
    private bool _restartForUpdate;

    private bool _isBusy;
    private bool _isChecking;
    private bool _hasChecked;
    private bool _checkFailed;
    private bool _isIndeterminate;
    private double _progressValue;
    private string _busyText = "";
    private string _statusText = "Ready";
    private string _sourceTitle = "Source not downloaded";
    private string _sourceMessage = "The latest source is fetched automatically on first launch";
    private string _sourceBadgeText = "Not installed";
    private string _remoteLine = "Remote not checked yet";
    private string _lastCheckText = "";
    private string _lastCheckShortText = "";
    private bool _hasUpdate;
    private int _updateCount;
    private string _jdkText = "Not installed";
    private string _jdkBadgeText = "Missing";
    private string _mavenText = "Not installed";
    private string _mavenBadgeText = "Missing";
    private ServerState _serverState = ServerState.Stopped;
    private int _serverPid;
    private CancellationTokenSource? _buildCts;
    private ClientState _clientState = ClientState.NotPrepared;
    private int _clientPid;
    private bool _autoScroll = true;

    public MainViewModel()
    {
        _log = new LogBus();
        _client = new ClientProvisioner(_log);
        _config = JsonStore.Load(AppPaths.ConfigPath, () => new AppConfig());
        _state = JsonStore.Load(AppPaths.StatePath, () => new AppState());
        GitSource.Initialize();

        _server.Output += line => _log.Server(line);
        _server.Exited += code => Application.Current?.Dispatcher.BeginInvoke(() => OnServerExited(code));

        // 客户端是独立进程：启动器退出不带走它，退出通知同样回 UI 线程处理
        _client.Exited += code => Application.Current?.Dispatcher.BeginInvoke(() => OnClientExited(code));

        CheckUpdateCommand = new AsyncRelayCommand(() => CheckUpdatesAsync(silent: false), () => !IsBusy && !IsChecking);
        UpdateSourceCommand = new AsyncRelayCommand(UpdateSourceAsync, () => CanDownload);
        ShowCommitsCommand = new AsyncRelayCommand(ShowCommitsAsync, () => !IsBusy);
        InstallJdkCommand = new AsyncRelayCommand(() => InstallJdkAsync(force: true), () => !IsBusy && !IsServerRunning);
        InstallMavenCommand = new AsyncRelayCommand(InstallMavenAsync, () => !IsBusy && !IsServerRunning);
        // 启动/停止共用一个按钮：由 ServerState 决定点击时执行哪个动作
        ToggleServerCommand = new AsyncRelayCommand(ToggleServerAsync, () => CanStart || IsServerRunning);
        ForceRebuildCommand = new AsyncRelayCommand(ForceRebuildAsync,
            () => !IsBusy && !IsServerRunning && AppPaths.SourceExists);
        LaunchClientCommand = new AsyncRelayCommand(LaunchClientAsync, () => CanLaunchClient);
        RepackClientCommand = new AsyncRelayCommand(() => PrepareClientAsync(forceRepack: true),
            () => !IsBusy && !IsClientRunning && AppPaths.SourceExists);
        OpenFolderCommand = new RelayCommand(() => OpenInExplorer(AppPaths.BaseDir));
        OpenSourceFolderCommand = new RelayCommand(() => OpenInExplorer(AppPaths.SourceDir));
        OpenRepoCommand = new RelayCommand(() => OpenUrl(_config.RepoUrl));
        ClearLogCommand = new RelayCommand(() => _log.Clear());
        CopyLogCommand = new RelayCommand(CopyLogToClipboard);
        OpenSettingsCommand = new RelayCommand(() => SettingsRequested?.Invoke());

        UpdateActionCommand = new AsyncRelayCommand(UpdateActionAsync, () => CanRunUpdateAction);

        // 首屏就要有「上次检测」，不能等第一次检测跑完（否则 ToolTip 一直空着）
        RefreshCheckText();
    }

    // ── 事件（由窗口处理：弹窗、设置页） ────────────────────────────────
    public event Action<CommitListPayload>? CommitsRequested;
    public event Action? SettingsRequested;

    /// <summary>替换脚本已经排好，窗口收到后应当立刻关闭（进程退出后脚本才会动 exe）。</summary>
    public event Action? RestartRequested;

    // ── 命令 ────────────────────────────────────────────────────────────
    public ICommand CheckUpdateCommand { get; }
    public ICommand UpdateSourceCommand { get; }
    public ICommand ShowCommitsCommand { get; }
    public ICommand InstallJdkCommand { get; }
    public ICommand InstallMavenCommand { get; }
    public ICommand ToggleServerCommand { get; }
    public ICommand ForceRebuildCommand { get; }
    public ICommand LaunchClientCommand { get; }
    public ICommand RepackClientCommand { get; }
    public ICommand OpenFolderCommand { get; }
    public ICommand OpenSourceFolderCommand { get; }
    public ICommand OpenRepoCommand { get; }
    public ICommand ClearLogCommand { get; }
    public ICommand CopyLogCommand { get; }
    public ICommand OpenSettingsCommand { get; }
    public ICommand UpdateActionCommand { get; }

    // ── 绑定属性 ────────────────────────────────────────────────────────
    public ObservableCollection<LogLine> Logs => _log.Lines;

    public string AppVersionText => "v" + AppVersion;
    public string RepoDisplay => $"{_config.Owner}/{_config.Repo} · {_config.Branch}";
    public string BaseDirText => AppPaths.BaseDir;

    public bool IsBusy
    {
        get => _isBusy;
        private set
        {
            if (!SetProperty(ref _isBusy, value)) return;
            RaiseBusyVisuals();
            // CanStart 依赖 IsBusy，必须主动刷新，否则按钮可用性只靠 WPF 被动 requery
            OnPropertyChanged(nameof(CanStart));
            CommandManager.InvalidateRequerySuggested();
        }
    }

    public bool IsIndeterminate
    {
        get => _isIndeterminate;
        private set
        {
            if (!SetProperty(ref _isIndeterminate, value)) return;
            RaiseBusyVisuals();
        }
    }

    public double ProgressValue { get => _progressValue; private set => SetProperty(ref _progressValue, value); }

    public string BusyText
    {
        get => _busyText;
        private set
        {
            if (!SetProperty(ref _busyText, value)) return;
            OnPropertyChanged(nameof(StatusLine));
        }
    }

    public string StatusText
    {
        get => _statusText;
        private set
        {
            if (!SetProperty(ref _statusText, value)) return;
            OnPropertyChanged(nameof(StatusLine));
        }
    }

    public string StatusLine => IsBusy && !string.IsNullOrEmpty(BusyText) ? BusyText : StatusText;

    /// <summary>
    /// 进度只有一条，忙碌时整条显示；确定 / 不确定由 <see cref="IsIndeterminate"/>
    /// 交给模板换轨道里的内容，不再切换控件。
    /// </summary>
    public Visibility ProgressBarVisibility => IsBusy ? Visibility.Visible : Visibility.Collapsed;

    private void RaiseBusyVisuals()
    {
        OnPropertyChanged(nameof(StatusLine));
        OnPropertyChanged(nameof(ProgressBarVisibility));
    }

    public string SourceTitle { get => _sourceTitle; private set => SetProperty(ref _sourceTitle, value); }
    public string SourceMessage { get => _sourceMessage; private set => SetProperty(ref _sourceMessage, value); }
    public string SourceBadgeText { get => _sourceBadgeText; private set => SetProperty(ref _sourceBadgeText, value); }
    public string RemoteLine { get => _remoteLine; private set => SetProperty(ref _remoteLine, value); }
    /// <summary>「上次检测 22:31」/「昨天 22:31」/「尚未检测过」——只出现在刷新按钮的 ToolTip 里。</summary>
    public string LastCheckText { get => _lastCheckText; private set => SetProperty(ref _lastCheckText, value); }
    /// <summary>短时间（22:31 / 昨天 22:31）——检测失败时才临时显示在卡片右上角。</summary>
    public string LastCheckShortText { get => _lastCheckShortText; private set => SetProperty(ref _lastCheckShortText, value); }
    /// <summary>检测失败且当前没在重试时，才把时间摆到卡头，旧结论才需要交代时间。</summary>
    public Visibility CheckFailedVisibility => _checkFailed && !IsChecking ? Visibility.Visible : Visibility.Collapsed;

    public bool HasUpdate
    {
        get => _hasUpdate;
        private set
        {
            if (!SetProperty(ref _hasUpdate, value)) return;
            OnPropertyChanged(nameof(SourceBadgeBg));
            OnPropertyChanged(nameof(SourceBadgeFg));
            OnPropertyChanged(nameof(UpdateButtonText));
            CommandManager.InvalidateRequerySuggested();
        }
    }

    public int UpdateCount
    {
        get => _updateCount;
        private set
        {
            if (!SetProperty(ref _updateCount, value)) return;
            OnPropertyChanged(nameof(SourceBadgeText));
            OnPropertyChanged(nameof(UpdateButtonText));
        }
    }

    public Brush SourceBadgeBg => !GitSource.Exists
        ? Ui.Brush("#F3F4F6")
        : IsChecking ? Ui.Brush("#E0F2FE")
        : _checkFailed || !_hasChecked ? Ui.Brush("#F3F4F6")
        : HasUpdate ? Ui.Brush("#FEF3C7")
        : Ui.Brush("#DCFCE7");

    public Brush SourceBadgeFg => !GitSource.Exists
        ? Ui.Brush("#6B7280")
        : IsChecking ? Ui.Brush("#0369A1")
        : _checkFailed || !_hasChecked ? Ui.Brush("#6B7280")
        : HasUpdate ? Ui.Brush("#92400E")
        : Ui.Brush("#15803D");

    public string UpdateButtonText => !GitSource.Exists ? "Clone Source" : HasUpdate ? "Pull Update" : "Re-sync";

    public string JdkText { get => _jdkText; private set => SetProperty(ref _jdkText, value); }
    public string JdkBadgeText { get => _jdkBadgeText; private set => SetProperty(ref _jdkBadgeText, value); }
    public string JdkHomeText => _jdk?.Home ?? "";
    public Brush JdkBadgeBg => _jdk is null ? Ui.Brush("#F3F4F6") : Ui.Brush("#DCFCE7");
    public Brush JdkBadgeFg => _jdk is null ? Ui.Brush("#6B7280") : Ui.Brush("#15803D");

    public string MavenText { get => _mavenText; private set => SetProperty(ref _mavenText, value); }
    public string MavenBadgeText { get => _mavenBadgeText; private set => SetProperty(ref _mavenBadgeText, value); }
    public string MavenHomeText => _maven?.Home ?? "";
    public Brush MavenBadgeBg => _maven is null ? Ui.Brush("#F3F4F6") : Ui.Brush("#DCFCE7");
    public Brush MavenBadgeFg => _maven is null ? Ui.Brush("#6B7280") : Ui.Brush("#15803D");

    // JDK 供应商只在「设置」里改（写 AppConfig.JdkVendor），主界面不再提供下拉框，
    // 所以这里没有对应的绑定属性；改完供应商点 JDK 行的「重新安装」即可换发行版。
    public ServerState ServerState
    {
        get => _serverState;
        private set
        {
            if (!SetProperty(ref _serverState, value)) return;
            OnPropertyChanged(nameof(ServerText));
            OnPropertyChanged(nameof(ServerDot));
            OnPropertyChanged(nameof(IsServerRunning));
            OnPropertyChanged(nameof(CanStart));
            OnPropertyChanged(nameof(ServerButtonText));
            OnPropertyChanged(nameof(ShowStopStyle));
            CommandManager.InvalidateRequerySuggested();
        }
    }

    public string ServerText => ServerState switch
    {
        ServerState.Starting => "Starting server…",
        ServerState.Running => $"Server running · PID {_serverPid}",
        ServerState.Stopping => "Stopping server…",
        _ => "Server stopped",
    };

    public Brush ServerDot => ServerState == ServerState.Running
        ? Ui.Brush("#16A34A")
        : ServerState is ServerState.Starting or ServerState.Stopping
            ? Ui.Brush("#F59E0B")
            : Ui.Brush("#9CA3AF");

    public bool IsServerRunning => ServerState is ServerState.Running or ServerState.Starting;

    // 单按钮的文案：过渡态给出提示，避免"停止中"却显示成可点的"启动服务"
    public string ServerButtonText => ServerState switch
    {
        ServerState.Starting => "Starting…",
        ServerState.Running => "Stop Server",
        ServerState.Stopping => "Stopping…",
        _ => "Start Server",
    };

    // 只在真正 Running 时切成"停止"配色（Starting 期间仍用主色）
    public bool ShowStopStyle => ServerState == ServerState.Running;

    // ── 客户端 ──────────────────────────────────────────────────────────
    public ClientState ClientState
    {
        get => _clientState;
        private set
        {
            if (!SetProperty(ref _clientState, value)) return;
            OnPropertyChanged(nameof(ClientText));
            OnPropertyChanged(nameof(ClientDetail));
            OnPropertyChanged(nameof(ClientDot));
            OnPropertyChanged(nameof(ClientBadgeText));
            OnPropertyChanged(nameof(ClientBadgeBg));
            OnPropertyChanged(nameof(ClientBadgeFg));
            OnPropertyChanged(nameof(ClientButtonText));
            OnPropertyChanged(nameof(IsClientRunning));
            OnPropertyChanged(nameof(CanLaunchClient));
            CommandManager.InvalidateRequerySuggested();
        }
    }

    public bool IsClientRunning => ClientState == ClientState.Running;

    public string ClientText => ClientState switch
    {
        ClientState.Preparing => "Preparing client…",
        ClientState.Running => $"Client running · PID {_clientPid}",
        ClientState.Ready => "Client ready",
        _ => "Client not prepared",
    };

    /// <summary>第二行小字：要么是资源包体积与目录，要么解释为什么点不了。</summary>
    public string ClientDetail => ClientState switch
    {
        ClientState.Preparing => "Pulling the resource submodule, copying files and repacking resources; the first run can take a few minutes",
        ClientState.Ready or ClientState.Running => DescribeClientFiles(),
        _ => !AppPaths.SourceExists
            ? "The client can be prepared once the source is ready"
            : ClientProvisioner.ResourceAvailable
                ? "Click \"Launch Client\" to copy the program files and pack the resources automatically"
                : "Initial launch downloads large client assets",
    };

    private static string DescribeClientFiles()
    {
        var size = ClientProvisioner.ArchiveBytes();
        return size > 0
            ? $"{ByteSize.Format(size)}"
            : AppPaths.ClientDir;
    }

    public Brush ClientDot => ClientState switch
    {
        ClientState.Running => Ui.Brush("#16A34A"),
        // Ready 用主题蓝而不是绿：Running 已经占了绿，两态同色就分不出来了；
        // 这里是整个界面里唯一一处「主题色出现在状态点上」，跟着 AccentBrush 一起换。
        ClientState.Ready => Ui.Brush("#1D74D8"),
        ClientState.Preparing => Ui.Brush("#F59E0B"),
        _ => Ui.Brush("#9CA3AF"),
    };

    // 环境卡片里客户端那一行跟 JDK / Maven 同款徽标：状态用短词，正文另说（见 ClientText）
    public string ClientBadgeText => ClientState switch
    {
        ClientState.Preparing => "Preparing",
        ClientState.Ready => "Ready",
        ClientState.Running => "Running",
        _ => "Missing",
    };

    public Brush ClientBadgeBg => ClientState switch
    {
        ClientState.Ready or ClientState.Running => Ui.Brush("#DCFCE7"),
        ClientState.Preparing => Ui.Brush("#FEF3C7"),
        _ => Ui.Brush("#F3F4F6"),
    };

    public Brush ClientBadgeFg => ClientState switch
    {
        ClientState.Ready or ClientState.Running => Ui.Brush("#15803D"),
        ClientState.Preparing => Ui.Brush("#B45309"),
        _ => Ui.Brush("#6B7280"),
    };

    public string ClientButtonText => ClientState switch
    {
        ClientState.Preparing => "Preparing…",
        ClientState.Running => "Running",
        _ => "Launch Client",
    };

    // 未准备时也允许点：点下去先准备（首次会拉子模块 + 打包），准备完再启动
    public bool CanLaunchClient => !IsBusy && !IsUpdateRequired && AppPaths.SourceExists
                                   && ClientState is ClientState.NotPrepared or ClientState.Ready;

    // 收紧到 Stopped：Stopping 期间 StopAsync 还没释放 _proc，此时放行启动会造成进程引用错乱。
    // 强制更新期间也不放行：旧版引导流程可能已经跟服务端对不上了。
    public bool CanStart => !IsBusy && !IsUpdateRequired && ServerState == ServerState.Stopped
                            && AppPaths.SourceExists && _jdk is not null && _maven is not null;
    public bool CanDownload => !IsBusy && !IsChecking && !IsServerRunning;
    public bool IsChecking
    {
        get => _isChecking;
        private set
        {
            if (!SetProperty(ref _isChecking, value)) return;
            OnPropertyChanged(nameof(SourceBadgeBg));
            OnPropertyChanged(nameof(SourceBadgeFg));
            // 重试期间先把失败时间藏起来，卡头此刻说的是「检测中」，不该再挂旧时间
            OnPropertyChanged(nameof(CheckFailedVisibility));
            CommandManager.InvalidateRequerySuggested();
        }
    }

    public bool AutoScroll { get => _autoScroll; set => SetProperty(ref _autoScroll, value); }

    // ── 启动器自更新 ────────────────────────────────────────────────────
    // 界面只有一条横幅，状态全由 _updateStage 派生：
    //   Available ──点下载──> Downloading ──> Ready ──点重启/退出──> 进程退出、脚本换 exe
    //                                            └── 校验失败 ──> Failed（可重试）
    public Visibility UpdateBannerVisibility => _launcherUpdate is not null ? Visibility.Visible : Visibility.Collapsed;

    /// <summary>低于清单里的 minVersion：此时禁止启动服务/客户端，先把启动器换掉。</summary>
    public bool IsUpdateRequired => _updateMandatory;

    public string UpdateTitle => _launcherUpdate is null
        ? ""
        : _updateStage switch
        {
            UpdateStage.Downloading => $"Downloading launcher v{_launcherUpdate.Version}",
            UpdateStage.Ready => $"Launcher v{_launcherUpdate.Version} is ready",
            UpdateStage.Failed => "Launcher update failed",
            _ => $"New launcher version v{_launcherUpdate.Version} available (current v{AppVersion})",
        };

    public string UpdateDetail { get => _updateDetail; private set => SetProperty(ref _updateDetail, value); }

    public string UpdateActionText => _updateStage switch
    {
        UpdateStage.Downloading => "Downloading…",
        UpdateStage.Ready => "Restart & Update",
        UpdateStage.Failed => "Retry",
        _ => _launcherUpdate is { Size: > 0 } info ? $"Download update ({ByteSize.Format(info.Size)})" : "Download update",
    };

    public bool CanRunUpdateAction => _launcherUpdate is not null && _updateStage != UpdateStage.Downloading;

    private void RaiseUpdateUi()
    {
        OnPropertyChanged(nameof(UpdateBannerVisibility));
        OnPropertyChanged(nameof(UpdateTitle));
        OnPropertyChanged(nameof(UpdateActionText));
        OnPropertyChanged(nameof(CanRunUpdateAction));
        OnPropertyChanged(nameof(IsUpdateRequired));
        OnPropertyChanged(nameof(CanStart));
        OnPropertyChanged(nameof(CanLaunchClient));
        CommandManager.InvalidateRequerySuggested();
    }

    private async Task CheckLauncherUpdateAsync(bool silent)
    {
        if (_launcherUpdate is not null)
        {
            RaiseUpdateUi();
            return;
        }

        if (!LauncherUpdater.CanSelfUpdate)
        {
            _log.Info("UPDATE", LauncherUpdater.UnsupportedReason!);
            return;
        }

        try
        {
            _log.Info("UPDATE", $"Checking launcher update · current v{AppVersion} · feed {string.Join(" / ", LauncherUpdater.FeedUrls(_config))}");
            var info = await LauncherUpdater.CheckAsync(_config);

            if (info is null)
            {
                _log.Info("UPDATE", $"Launcher is up to date (v{AppVersion})");
                if (!silent) StatusText = "Launcher is up to date";
                return;
            }

            _launcherUpdate = info;
            _updateMandatory = LauncherUpdater.IsMandatory(info.MinVersion);
            _updateStage = UpdateStage.Available;
            DescribeAvailableUpdate();
            _log.Warn("UPDATE", $"New launcher version v{info.Version} available (current v{AppVersion})" +
                                (_updateMandatory ? $" — mandatory update (minimum v{info.MinVersion})" : ""));
            if (!silent) StatusText = $"Launcher update v{info.Version} available";
            RaiseUpdateUi();
        }
        catch (Exception ex)
        {
            _log.Info("UPDATE", "Launcher update check failed: " + ex.Message);
            if (!silent) StatusText = "Launcher update check failed";
        }
    }

    private void DescribeAvailableUpdate()
    {
        var info = _launcherUpdate!;
        var parts = new List<string> { ByteSize.Format(info.Size) };

        var note = FirstLine(info.Notes);
        if (note.Length > 0) parts.Add(note);

        parts.Add(_updateMandatory
            ? $"This is a mandatory update (minimum v{info.MinVersion})"
            : "Only the launcher executable is replaced; the runtime folder is untouched");

        UpdateDetail = string.Join(" · ", parts);
    }

    private static string FirstLine(string text)
    {
        var line = text.Replace("\r", "").Split('\n')[0].Trim();
        return line.Length > 80 ? line[..80] + "…" : line;
    }

    private void OnUpdateProgress(DownloadProgress p)
    {
        var total = p.Total > 0 ? p.Total : _launcherUpdate?.Size ?? 0;
        UpdateDetail = total > 0
            ? $"Downloaded {ByteSize.Format(p.Bytes)} / {ByteSize.Format(total)} ({p.Percent:F0}%)"
            : $"Downloaded {ByteSize.Format(p.Bytes)}";
    }

    private async Task UpdateActionAsync()
    {
        if (_launcherUpdate is null) return;

        if (_updateStage == UpdateStage.Ready)
        {
            ApplyUpdate();
            return;
        }

        if (_updateStage is not (UpdateStage.Available or UpdateStage.Failed)) return;

        _updateStage = UpdateStage.Downloading;
        RaiseUpdateUi();

        try
        {
            var progress = new Progress<DownloadProgress>(OnUpdateProgress);
            _stagedExe = await LauncherUpdater.DownloadAsync(_launcherUpdate, progress);
            _updateStage = UpdateStage.Ready;
            UpdateDetail = "\"Restart & Update\" stops the running server, replaces the executable and relaunches it; " +
                           "closing the launcher also applies the update on exit, so the next launch uses the new version";
            _log.Success("UPDATE", $"Launcher v{_launcherUpdate.Version} downloaded, waiting for restart");
        }
        catch (Exception ex)
        {
            _updateStage = UpdateStage.Failed;
            UpdateDetail = ex.Message;
            _log.Error("UPDATE", "Launcher update download failed: " + ex.Message);
        }
        finally
        {
            RaiseUpdateUi();
        }
    }

    /// <summary>
    /// 排好替换并请求窗口关闭。真正的文件操作在进程退出后由 helper 脚本做——
    /// Windows 不允许覆盖正在运行的 exe，只能等它退干净。
    /// </summary>
    private void ApplyUpdate()
    {
        var update = _launcherUpdate;
        var staged = _stagedExe;
        if (update is null || staged is null) return;

        if (IsServerRunning)
        {
            var answer = MessageBox.Show(
                "Replacing the launcher requires stopping the running server; clients in game will be disconnected.\nContinue?",
                "Update Launcher", MessageBoxButton.YesNo, MessageBoxImage.Question);
            if (answer != MessageBoxResult.Yes) return;
        }

        try
        {
            LauncherUpdater.ScheduleApply(update, staged, relaunch: true);
            _restartForUpdate = true;
            _log.Sys($"Restarting to update the launcher to v{update.Version}…");
            RestartRequested?.Invoke();
        }
        catch (Exception ex)
        {
            _log.Error("UPDATE", "Failed to schedule replacement: " + ex.Message);
            MessageBox.Show(ex.Message, "Update Launcher", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    /// <summary>主动退出时，如果已经下载好新版本就顺手换上：下次双击启动器直接就是新版。</summary>
    private void TryApplyStagedUpdateOnExit()
    {
        if (_restartForUpdate) return;
        if (_updateStage != UpdateStage.Ready || _stagedExe is null || _launcherUpdate is null) return;

        try
        {
            LauncherUpdater.ScheduleApply(_launcherUpdate, _stagedExe, relaunch: false);
            _log.Info("UPDATE", $"Applying downloaded v{_launcherUpdate.Version} on exit; next launch will be the new version");
        }
        catch (Exception ex)
        {
            _log.Warn("UPDATE", "Failed to schedule replacement on exit (will re-check next launch): " + ex.Message);
        }
    }

    // ── 启动流程 ────────────────────────────────────────────────────────
    public async Task InitializeAsync()
    {
        _log.Sys($"BnbLauncher v{AppVersion} started");
        _log.Sys($"Launcher folder: {AppPaths.BaseDir}");
        _log.Info("GIT", $"Repository: {_config.CloneUrl}#{_config.Branch}");
        _log.Info("GIT", $"libgit2 {GitSource.Version} · {GitNetwork.Note(_config)} · {GitMirror.Note(_config.Mirror)}");
        if (GitSource.InitError is not null) _log.Error("GIT", "Failed to load libgit2: " + GitSource.InitError);

        // 简体中文 + 无代理 + 未开镜像：主动提一句可以换镜像（条件判断见 SystemLocale.MirrorTip）
        if (SystemLocale.MirrorTip(_config) is { } mirrorTip) _log.Info("GIT", mirrorTip);

        // 启动器自身：先对账上一次替换有没有生效，再异步查一次远端。
        // 不直接 await：网络差的时候能卡十几秒；这里最多等 2.5 秒，够判断是否强制更新了。
        LauncherUpdater.Reconcile(_log);
        Downloader.Configure(_config);
        if (_config.AutoCheckLauncher)
        {
            var probe = CheckLauncherUpdateAsync(silent: true);
            await Task.WhenAny(probe, Task.Delay(2500));
        }

        RefreshAll();

        var firstRun = !GitSource.Exists;
        if (firstRun) _log.Info("GIT", "No local git repository (first run or legacy zip install): cloning the latest source automatically");

        await RunOperationAsync("Checking runtime", async ct =>
        {
            await EnsureJavaAsync(autoDownload: true, forceLocal: false, ct);
            await EnsureMavenAsync(autoDownload: true, ct);
        }, silent: true);

        if (!GitSource.Exists)
        {
            await RunOperationAsync("Cloning latest source", ct => SyncSourceAsync(ct), silent: false);
        }

        RefreshAll();

        if (_config.AutoCheckUpdates)
        {
            _ = CheckUpdatesAsync(silent: true);
        }

        if (IsUpdateRequired)
        {
            StatusText = $"Launcher update required (minimum v{_launcherUpdate?.MinVersion})";
        }
        else if (_config.AutoStartServer && CanStart)
        {
            await StartServerAsync();
        }
        else if (!AppPaths.SourceExists)
        {
            StatusText = "Source not ready";
        }
        else
        {
            StatusText = "Ready";
        }
    }

    private async Task RunOperationAsync(string title, Func<CancellationToken, Task> body, bool silent)
    {
        if (IsBusy) return;

        IsBusy = true;
        BusyText = title;
        StatusText = title;
        ProgressValue = 0;
        IsIndeterminate = true;
        _opCts = new CancellationTokenSource();

        try
        {
            await body(_opCts.Token);
        }
        catch (OperationCanceledException)
        {
            _log.Warn("LAUNCH", "Operation cancelled");
            StatusText = "Cancelled";
        }
        catch (Exception ex)
        {
            _log.Error("LAUNCH", ex.Message);
            StatusText = "Failed: " + ex.Message;
            if (!silent)
            {
                MessageBox.Show(ex.Message, "Operation Failed", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }
        finally
        {
            IsBusy = false;
            BusyText = "";
            IsIndeterminate = false;
            ProgressValue = 0;
            _opCts?.Dispose();
            _opCts = null;
            RefreshAll();
        }
    }

    private IProgress<SetupProgress> CreateProgress() => new Progress<SetupProgress>(p =>
    {
        // Progress<T> 走的是异步投递：操作抛异常之后队列里可能还压着几条迟到的报告，
        // 落到 UI 上会把「失败：…」又盖回「打包 xxx（2/3）」。操作结束后一律丢弃。
        if (!IsBusy) return;

        BusyText = p.Phase;
        StatusText = p.Phase;
        if (p.Percent < 0)
        {
            IsIndeterminate = true;
        }
        else
        {
            IsIndeterminate = false;
            ProgressValue = p.Percent;
        }
    });

    // ── 运行环境 ────────────────────────────────────────────────────────
    private async Task EnsureJavaAsync(bool autoDownload, bool forceLocal, CancellationToken ct)
    {
        if (!forceLocal)
        {
            var local = JavaProvisioner.GetLocal();
            if (local is not null)
            {
                _jdk = local;
                _log.Info("JDK", $"Using bundled JDK {local.Display}");
                UpdateJdkUi();
                return;
            }

            var system = JavaProvisioner.GetSystem();
            if (system is not null)
            {
                _jdk = system;
                _log.Warn("JDK", $"Bundled JDK not found, falling back to system JDK {system.Display} ({system.Home})");
                UpdateJdkUi();
                return;
            }
        }

        if (!autoDownload) return;

        _log.Info("JDK", $"No usable JDK found, downloading JDK 21 ({_config.JdkVendor})");
        _jdk = await JavaProvisioner.InstallAsync(_config.JdkVendor, CreateProgress(), ct);
        _log.Success("JDK", $"JDK {_jdk.Display} installed to {_jdk.Home}");

        _state.JdkVersion = _jdk.Version;
        SaveState();
        UpdateJdkUi();
    }

    private async Task EnsureMavenAsync(bool autoDownload, CancellationToken ct)
    {
        var local = MavenProvisioner.GetLocal();
        if (local is not null)
        {
            _maven = local;
            _log.Info("MVN", $"Using bundled Maven {local.Version}");
            UpdateMavenUi();
            return;
        }

        // 内置优先：Maven 只有 ~10MB，装一份在本地比依赖系统环境更可控
        if (autoDownload)
        {
            try
            {
                _log.Info("MVN", "Bundled Maven not found, downloading Maven 3.9");
                _maven = await MavenProvisioner.InstallAsync(CreateProgress(), ct);
                _log.Success("MVN", $"Maven {_maven.Version} installed to {_maven.Home}");

                _state.MavenVersion = _maven.Version;
                SaveState();
                UpdateMavenUi();
                return;
            }
            catch (Exception ex)
            {
                _log.Warn("MVN", "Failed to download Maven, trying system Maven: " + ex.Message);
            }
        }

        var system = await MavenProvisioner.GetSystemAsync(_jdk?.Home, ct);
        if (system is not null)
        {
            _maven = system;
            _log.Warn("MVN", $"Falling back to system Maven {system.Version} ({system.Home})");
        }
        else
        {
            _log.Error("MVN", "No usable Maven found; check your network and try again");
        }

        UpdateMavenUi();
    }

    private Task InstallJdkAsync(bool force)
    {
        return RunOperationAsync("Installing JDK 21", async ct =>
        {
            _jdk = null;
            UpdateJdkUi();
            await EnsureJavaAsync(autoDownload: true, forceLocal: true, ct);
        }, silent: false);
    }

    private Task InstallMavenAsync()
    {
        return RunOperationAsync("Installing Maven", async ct =>
        {
            _maven = null;
            UpdateMavenUi();
            await EnsureMavenAsync(autoDownload: true, ct);
        }, silent: false);
    }

    private void UpdateJdkUi()
    {
        JdkBadgeText = _jdk is null ? "Missing" : "Ready";
        JdkText = _jdk is null ? "Not installed" : _jdk.Display;
        OnPropertyChanged(nameof(JdkHomeText));
        OnPropertyChanged(nameof(JdkBadgeBg));
        OnPropertyChanged(nameof(JdkBadgeFg));
        CommandManager.InvalidateRequerySuggested();
    }

    private void UpdateMavenUi()
    {
        MavenBadgeText = _maven is null ? "Missing" : "Ready";
        MavenText = _maven is null ? "Not installed" : _maven.Display;
        OnPropertyChanged(nameof(MavenHomeText));
        OnPropertyChanged(nameof(MavenBadgeBg));
        OnPropertyChanged(nameof(MavenBadgeFg));
        CommandManager.InvalidateRequerySuggested();
    }

    // ── 源码 ────────────────────────────────────────────────────────────
    private async Task CheckUpdatesAsync(bool silent)
    {
        if (IsChecking) return;
        IsChecking = true;
        StatusText = "Checking for updates…";
        try
        {
            var status = await GitSource.CheckAsync(_config);
            _remoteHead = status.RemoteHead;
            RemoteLine = DescribeRemote(status);

            if (!status.RepoExists)
            {
                HasUpdate = false;
                UpdateCount = 0;
                _log.Info("GIT", $"Remote head {status.RemoteHead?.ShortSha ?? "?"}; source not cloned yet");
            }
            else if (status.UpToDate)
            {
                HasUpdate = false;
                UpdateCount = 0;
                _pendingCommits = [];
                _log.Success("GIT", $"Already up to date ({status.LocalHead?.ShortSha})");
            }
            else if (status.Diverged)
            {
                _pendingCommits = status.NewCommits;
                HasUpdate = true;
                // 本地不在远端历史里时，Ahead 仍表示「远端有而本地没有」的真实条数，
                // 只是不能再叫「落后 N 个提交」，所以日志单独换一种说法。
                UpdateCount = status.Ahead;
                _log.Warn("GIT",
                    $"Found {status.Ahead} remote commits: {status.LocalHead?.ShortSha} → {status.RemoteHead?.ShortSha}" +
                    " (local commits are not in the remote history; the update will force-align)");
            }
            else
            {
                _pendingCommits = status.NewCommits;
                HasUpdate = true;
                UpdateCount = status.Ahead;
                _log.Warn("GIT",
                    $"Found {status.Ahead} new commits: {status.LocalHead?.ShortSha} → {status.RemoteHead?.ShortSha}");
            }

            SyncStateFromRepo();
            _hasChecked = true;
            SetCheckFailed(false);
        }
        catch (Exception ex)
        {
            SetCheckFailed(true);
            _log.Warn("GIT", "Update check failed: " + ex.Message);
            if (!silent) StatusText = "Check failed";
        }
        finally
        {
            IsChecking = false;
            // 无论成功还是失败都记这一次尝试的时间：失败时卡头要说明「这个失败结论是什么时候的」
            _state.LastCheckUtc = DateTime.UtcNow.ToString("O");
            SaveState();
            RefreshCheckText();
            RefreshSourceUi();
            CommandManager.InvalidateRequerySuggested();
        }
    }

    /// <summary>
    /// 记录本次检测结论。「上次检测」时间只在这里联动刷新，避免各处漏发通知。
    /// </summary>
    private void SetCheckFailed(bool value)
    {
        if (_checkFailed == value) return;
        _checkFailed = value;
        OnPropertyChanged(nameof(CheckFailedVisibility));
        OnPropertyChanged(nameof(SourceBadgeBg));
        OnPropertyChanged(nameof(SourceBadgeFg));
    }

    /// <summary>从 state.json 的 lastCheckUtc 生成 ToolTip / 卡头文案；启动时也要调一次。</summary>
    private void RefreshCheckText()
    {
        var stamp = FormatCheckStamp(ParseStamp(_state.LastCheckUtc));
        LastCheckText = stamp.Length == 0 ? "Never checked" : "Last checked " + stamp;
        LastCheckShortText = stamp;
    }

    /// <summary>检测时间本地化：今天只给 HH:mm，昨天带前缀，更早给月-日。</summary>
    private static string FormatCheckStamp(DateTimeOffset? utc)
    {
        if (utc is null) return "";

        var local = utc.Value.ToLocalTime();
        var today = DateTimeOffset.Now.Date;
        if (local.Date == today) return local.ToString("HH:mm", CultureInfo.InvariantCulture);
        if (local.Date == today.AddDays(-1)) return "Yesterday " + local.ToString("HH:mm", CultureInfo.InvariantCulture);
        return local.ToString("MM-dd HH:mm", CultureInfo.InvariantCulture);
    }

    private static string DescribeRemote(SourceStatus status)
    {
        var head = status.RemoteHead;
        if (head is null) return "Remote branch unavailable";

        return status.RepoExists && head.Date is not null
            ? $"Remote {head.ShortSha} · {head.Title} · {head.RelativeText}"
            : $"Remote {head.ShortSha} (not cloned yet)";
    }

    private async Task UpdateSourceAsync()
    {
        // 源码目录归启动器管理，本地手改的东西同步时会被覆盖，先问一句
        if (await Task.Run(GitSource.HasLocalChanges))
        {
            var answer = MessageBox.Show(
                "The local source has uncommitted changes. Syncing will force-align the working tree to the remote commit (equivalent to git reset --hard).\nContinue?",
                "Update Source", MessageBoxButton.YesNo, MessageBoxImage.Warning);
            if (answer != MessageBoxResult.Yes) return;
        }

        await RunOperationAsync("Updating source", async ct =>
        {
            await SyncSourceAsync(ct);
            StatusText = "Source updated";
        }, silent: false);
    }

    /// <summary>clone（首次 / 旧版 zip 落地）或 pull（已有仓库），结果写回 state.json。</summary>
    private async Task SyncSourceAsync(CancellationToken ct)
    {
        var result = await GitSource.SyncAsync(_config, CreateProgress(), ct);

        _remoteHead = result.Head;
        RemoteLine = result.Head is null
            ? "Remote branch unavailable"
            : $"Remote {result.Head.ShortSha} · {result.Head.Title} · {result.Head.RelativeText}";

        ApplyInstalled(result.Head);
        _pendingCommits = [];
        HasUpdate = false;
        UpdateCount = 0;
        _hasChecked = true;
        SetCheckFailed(false);

        if (result.UpToDate && result.Repaired)
            _log.Success("GIT", $"Already up to date ({result.Head?.ShortSha}); working tree force-aligned");
        else if (result.UpToDate)
            _log.Success("GIT", $"Already up to date ({result.Head?.ShortSha})");
        else if (result.Cloned)
            _log.Success("GIT", $"Source cloned to {AppPaths.SourceDir} ({result.Head?.ShortSha})");
        else
            _log.Success("GIT",
                $"git pull complete: {result.From?.ShortSha ?? "?"} → {result.Head?.ShortSha} ({result.CommitCount} commits)");

        if (result.Diverged && !result.Cloned)
            _log.Warn("GIT", "Local commits are not in the remote history; force-aligned to the remote branch (local changes discarded)");
    }

    private void ApplyInstalled(CommitInfo? head)
    {
        if (head is null) return;

        _state.InstalledSha = head.Sha;
        _state.InstalledShortSha = head.ShortSha;
        _state.InstalledMessage = head.Title;
        _state.InstalledCommitDate = head.Date?.ToString("O");
        _state.InstalledAt = DateTime.Now.ToString("O");
        SaveState();
    }

    /// <summary>state.json 里的版本可能因为外部 git 操作而过期，统一以仓库 HEAD 为准。</summary>
    private void SyncStateFromRepo()
    {
        var head = GitSource.TryReadHead();
        if (head is null) return;
        if (string.Equals(_state.InstalledSha, head.Sha, StringComparison.OrdinalIgnoreCase)) return;

        ApplyInstalled(head);
        RefreshSourceUi();
    }

    private async Task ShowCommitsAsync()
    {
        try
        {
            // 完整历史从本地对象库遍历（优先远端跟踪引用），不截断、不联网。
            var all = await Task.Run(() => GitSource.AllCommits(_config));
            var pending = _pendingCommits.Count;
            var hasUpdates = pending > 0;

            var contextSubtitle = hasUpdates && _state.InstalledShortSha is not null && _remoteHead is not null
                ? $"{_state.InstalledShortSha} → {_remoteHead.ShortSha} · {pending} new commits"
                : $"{pending} commits pending update";

            string allSubtitle;
            if (!GitSource.Exists)
                allSubtitle = "Source not cloned yet, so there is no commit history";
            else if (hasUpdates)
                allSubtitle = $"Full history · {all.Count} commits ({pending} pending update)";
            else
                allSubtitle = $"Up to date · full history, {all.Count} commits";

            CommitsRequested?.Invoke(new CommitListPayload
            {
                ContextCommits = [.. _pendingCommits],
                AllCommitList = all,
                ContextSubtitle = contextSubtitle,
                AllSubtitle = allSubtitle,
                // 没有待更新提交（或还没克隆）时，两个视图一样，不必显示切换按钮
                ShowScopeSwitch = hasUpdates && all.Count > 0,
            });
        }
        catch (Exception ex)
        {
            _log.Warn("GIT", "Failed to get commit history: " + ex);
            MessageBox.Show("Unable to open commits: " + ex.Message, "Failed to Load Commits", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    // ── 服务进程 ────────────────────────────────────────────────────────
    // 单按钮入口：自动启动、退出清理仍直接走 StartServerAsync / _server.StopAsync，不受影响
    private Task ToggleServerAsync()
        => ServerState == ServerState.Running ? StopServerAsync() : StartServerAsync();

    private async Task StartServerAsync()
    {
        if (IsServerRunning) return;

        try
        {
            if (_jdk is null) await EnsureJavaAsync(true, false, CancellationToken.None);
            if (_maven is null) await EnsureMavenAsync(true, CancellationToken.None);
            if (_jdk is null || _maven is null)
            {
                MessageBox.Show("The runtime is not ready, so the server cannot start.", "Launch Failed", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            // 端口检查放在编译之前：端口冲突时不该让用户先白等十几秒编译
            var busy = PortProbe.BusyPorts(3838, 47611);
            if (busy.Length > 0)
            {
                _log.Warn("SERVER", "Ports in use: " + string.Join(", ", busy));
                var answer = MessageBox.Show(
                    $"Port {string.Join(" / ", busy)} is already in use by another program (another server instance, or a debugger in the IDE).\n" +
                    "Starting anyway may fail to bind. Start anyway?",
                    "Port Conflict", MessageBoxButton.YesNo, MessageBoxImage.Warning);
                if (answer != MessageBoxResult.Yes)
                {
                    StatusText = "Port in use";
                    return;
                }

                // 端口被占很可能意味着另一个实例正跑在同一份源码上；编译会清空
                // target\classes，正在运行的 JVM 之后再懒加载类就会失败。
                _log.Warn("BUILD", "Port is busy — if another instance runs from this folder, stop it before recompiling.");
            }

            var jdk = _jdk;
            var maven = _maven;

            // 判定 + 按需编译。产物齐全时这里只花几十毫秒（git 状态 + 文件比对），
            // 真正需要编译时才会出现十几秒的全量 Kotlin 编译，所以必须有可见进度。
            _log.Info("BUILD", "Checking build state…");
            await RunBuildAsync(jdk, maven, force: false, "Preparing server…");

            ServerState = ServerState.Starting;
            StatusText = "Starting server…";
            _log.Info("SERVER", $"Launching {AppPaths.ServerMainClass} with {jdk.Display}");
            _log.Info("SERVER", $"Working directory: {AppPaths.SourceDir}");
            _log.Info("SERVER", $"Classpath: {AppPaths.ServerClassesDir} + {AppPaths.ServerClasspathFile}");

            _server.Start(jdk);
            _serverPid = _server.ProcessId;
            ServerState = ServerState.Running;
            StatusText = "Server running";
            _log.Success("SERVER", $"Server process started (PID {_serverPid})");
        }
        catch (Exception ex)
        {
            ServerState = ServerState.Stopped;
            StatusText = "Launch failed";
            _log.Error("SERVER", "Launch failed: " + ex.Message);
            MessageBox.Show(ex.Message, "Failed to Start Server", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    /// <summary>
    /// 跑一次「判定 + 按需编译」，负责 Busy 状态与日志；
    /// 失败时把异常抛给调用方（由它决定弹窗文案）。
    /// </summary>
    private async Task<BuildOutcome> RunBuildAsync(JdkInfo jdk, MavenInfo maven, bool force, string busyText)
    {
        IsBusy = true;
        BusyText = busyText;
        IsIndeterminate = true;
        _buildCts = new CancellationTokenSource();

        try
        {
            var outcome = await ServerBuild.EnsureAsync(jdk, maven,
                line => _log.Info("BUILD", line), force, _buildCts.Token);

            if (outcome.Compiled)
                _log.Success("BUILD", $"Compiled in {outcome.Elapsed.TotalSeconds:0.0}s ({outcome.Reason})");
            else
                _log.Info("BUILD", "Skip compile: " + outcome.Reason);

            return outcome;
        }
        finally
        {
            _buildCts.Dispose();
            _buildCts = null;
            IsBusy = false;
            BusyText = "";
            IsIndeterminate = false;
        }
    }

    /// <summary>
    /// 界面上的「重新编译」：跳过判定，清空产物重新编译一次。
    /// 怀疑产物损坏（杀软/手动删过 target）但判定仍然认为可用时用得上。
    /// </summary>
    private async Task ForceRebuildAsync()
    {
        if (IsServerRunning) return;

        if (_jdk is null) await EnsureJavaAsync(true, false, CancellationToken.None);
        if (_maven is null) await EnsureMavenAsync(true, CancellationToken.None);
        if (_jdk is null || _maven is null) return;

        var jdk = _jdk;
        var maven = _maven;

        try
        {
            await RunBuildAsync(jdk, maven, force: true, "Rebuilding server…");
            StatusText = "Rebuilt";
        }
        catch (OperationCanceledException)
        {
            _log.Warn("BUILD", "Rebuild cancelled");
            StatusText = "Rebuild cancelled";
        }
        catch (Exception ex)
        {
            _log.Error("BUILD", "Rebuild failed: " + ex.Message);
            StatusText = "Rebuild failed";
            MessageBox.Show(ex.Message, "Rebuild Failed", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private async Task StopServerAsync()
    {
        if (!IsServerRunning) return;
        ServerState = ServerState.Stopping;
        _log.Info("SERVER", "Stopping server…");
        await _server.StopAsync();
        ServerState = ServerState.Stopped;
        _serverPid = 0;
        StatusText = "Server stopped";
        _log.Warn("SERVER", "Server stopped");
    }

    private void OnServerExited(int code)
    {
        _serverPid = 0;
        ServerState = ServerState.Stopped;
        if (code == 0)
        {
            _log.Warn("SERVER", "Server process exited (code 0)");
            StatusText = "Server exited";
        }
        else
        {
            _log.Error("SERVER", $"Server process exited unexpectedly (code {code}); see the log above");
            StatusText = $"Server exited unexpectedly ({code})";
        }
    }

    // ── 客户端 ──────────────────────────────────────────────────────────
    private async Task LaunchClientAsync()
    {
        if (IsClientRunning) return;

        // 1) 客户端目录还没拼出来：先准备（首次要拉子模块 + 打包，会走进度条）
        if (!ClientProvisioner.IsPrepared && !await PrepareClientAsync(forceRepack: false))
        {
            return;
        }

        // 2) 服务没起来时客户端会卡在登录，先问一句
        if (!IsServerRunning)
        {
            var answer = MessageBox.Show(
                "The background server is not running, so the client will not be able to log in.\nLaunch the client anyway?",
                "Launch Client", MessageBoxButton.YesNo, MessageBoxImage.Question);
            if (answer != MessageBoxResult.Yes) return;
        }

        try
        {
            _client.Launch();
            _clientPid = _client.ProcessId;
            ClientState = ClientState.Running;
            StatusText = "Client running";
        }
        catch (Exception ex)
        {
            StatusText = "Failed to launch client";
            _log.Error("CLIENT", "Launch failed: " + ex.Message);
            MessageBox.Show(ex.Message, "Failed to Launch Client", MessageBoxButton.OK, MessageBoxImage.Warning);
            RefreshClientUi();
        }
    }

    /// <summary>准备客户端目录，返回是否已经可以启动。</summary>
    private async Task<bool> PrepareClientAsync(bool forceRepack)
    {
        if (!AppPaths.SourceExists)
        {
            MessageBox.Show("The source has not finished downloading, so the client cannot be prepared.", "Prepare Client",
                MessageBoxButton.OK, MessageBoxImage.Information);
            return false;
        }

        ClientState = ClientState.Preparing;
        StatusText = "Preparing client…";

        var ok = false;
        await RunOperationAsync(forceRepack ? "Repacking client resources" : "Preparing client", async ct =>
        {
            await _client.PrepareAsync(_config, CreateProgress(), forceRepack, ct);
            ok = true;
        }, silent: false);

        // 异常/取消时 Preparing 不会被清掉（RunOperationAsync 只负责 IsBusy 那套），
        // 这里强制按磁盘实际状态落回去：失败就回到「未准备」，用户可以直接再点一次。
        RefreshClientUi(settle: true);

        // 准备流程跑完了但文件还是不齐：多半是 resource 子模块缺内容（缺 manifest 的目录会被跳过）
        if (ok && !ClientProvisioner.IsPrepared)
        {
            MessageBox.Show(
                $"Client files are still incomplete: {AppPaths.ClientDir}\n" +
                "Check the resource submodule (it needs other\\CA.exe plus the unpacked fx / CharTE / EmoTE folders).",
                "Prepare Client", MessageBoxButton.OK, MessageBoxImage.Warning);
        }

        return ok && ClientProvisioner.IsPrepared;
    }

    /// <summary>
    /// 按磁盘上的实际状态刷新客户端卡片（进程在跑 > 文件齐 > 未准备）。
    /// <para><paramref name="settle"/> 用于「准备流程刚刚结束」：操作中途失败（比如 resource
    /// 子模块更新抛异常）时状态会卡在 Preparing，这里必须强制落回真实状态，
    /// 否则卡片会一直显示"正在准备客户端/准备中"，启动按钮也点不动。</para>
    /// </summary>
    private void RefreshClientUi(bool settle = false)
    {
        if (_client.IsRunning)
        {
            _clientPid = _client.ProcessId;
            ClientState = ClientState.Running;
        }
        else if (settle || ClientState != ClientState.Preparing)
        {
            _clientPid = 0;
            ClientState = ClientProvisioner.IsPrepared ? ClientState.Ready : ClientState.NotPrepared;
        }

        // 状态没变时上面的 setter 不发通知，但资源包体积这类派生文案还得跟着刷
        OnPropertyChanged(nameof(ClientText));
        OnPropertyChanged(nameof(ClientDetail));
        OnPropertyChanged(nameof(CanLaunchClient));
    }

    private void OnClientExited(int code)
    {
        _clientPid = 0;
        RefreshClientUi();

        if (code == 0)
        {
            _log.Warn("CLIENT", "Client closed");
            StatusText = "Client exited";
        }
        else
        {
            _log.Warn("CLIENT", $"Client exited (code {code})");
            StatusText = $"Client exited ({code})";
        }
    }

    // ── 设置 / 杂项 ─────────────────────────────────────────────────────
    public AppConfig Config => _config;

    public void ApplyConfig(AppConfig config)
    {
        var repoChanged = !string.Equals(config.Owner, _config.Owner, StringComparison.OrdinalIgnoreCase)
                          || !string.Equals(config.Repo, _config.Repo, StringComparison.OrdinalIgnoreCase)
                          || !string.Equals(config.Branch, _config.Branch, StringComparison.Ordinal);
        var proxyChanged = config.Proxy != _config.Proxy;
        var vendorChanged = !string.Equals(config.JdkVendor, _config.JdkVendor, StringComparison.OrdinalIgnoreCase);

        // 换了下载通道等于换了远端地址（origin 会在下一次 fetch 时被 EnsureRemote 改写），
        // 所以「有新提交 / 有新版本」的判断全部作废重来，和改仓库地址一个待遇
        var mirrorChanged = GitMirror.Normalize(config.Mirror) != GitMirror.Normalize(_config.Mirror);

        _config = config;
        SaveConfig();

        OnPropertyChanged(nameof(RepoDisplay));

        _log.Info("LAUNCH", "Settings saved");
        if (proxyChanged)
        {
            _log.Info("GIT", GitNetwork.Note(_config));
            Downloader.Configure(_config);
        }
        if (mirrorChanged)
        {
            _log.Info("GIT", GitMirror.Note(_config.Mirror) + $" · remote becomes {_config.CloneUrl}");
        }
        if (repoChanged || proxyChanged || mirrorChanged)
        {
            _remoteHead = null;
            _pendingCommits = [];
            HasUpdate = false;
            UpdateCount = 0;
            RefreshSourceUi();
            _ = CheckUpdatesAsync(silent: true);
        }

        // 更新源/仓库/通道变了就重新查一次；之前发现过的更新不重复提示
        if (repoChanged || proxyChanged || mirrorChanged) _ = CheckLauncherUpdateAsync(silent: true);

        // 供应商只在设置里改，改完已装好的 JDK 不会自己换：提示一下该点哪儿
        if (vendorChanged && _jdk is not null)
            _log.Info("JDK", $"JDK vendor changed to {_config.JdkVendor}; click \"Reinstall\" on the JDK row to switch to that distribution");
    }

    private void SaveConfig() => JsonStore.Save(AppPaths.ConfigPath, _config);

    private void SaveState() => JsonStore.Save(AppPaths.StatePath, _state);

    private void RefreshAll()
    {
        SyncStateFromRepo();
        UpdateJdkUi();
        UpdateMavenUi();
        RefreshSourceUi();
        RefreshClientUi();
        OnPropertyChanged(nameof(CanStart));
        OnPropertyChanged(nameof(UpdateButtonText));
        OnPropertyChanged(nameof(ServerText));
        OnPropertyChanged(nameof(ServerDot));
        OnPropertyChanged(nameof(ServerButtonText));
        OnPropertyChanged(nameof(ShowStopStyle));
        CommandManager.InvalidateRequerySuggested();
    }

    private void RefreshSourceUi()
    {
        if (!GitSource.Exists)
        {
            SourceTitle = "Source not cloned";
            SourceMessage = "On first launch the repository is cloned to runtime\\source automatically";
            SourceBadgeText = "Not cloned";
        }
        else
        {
            SourceTitle = DescribeInstalledTitle();
            SourceMessage = string.IsNullOrWhiteSpace(_state.InstalledMessage)
                ? AppPaths.SourceDir
                : _state.InstalledMessage!;

            SourceBadgeText = IsChecking
                ? "Checking…"
                : _checkFailed ? "Check failed"
                : !_hasChecked ? "Not checked"
                : HasUpdate
                    ? (UpdateCount > 0 ? $"{UpdateCount} new commits" : "Update available")
                    : "Up to date";
        }

        OnPropertyChanged(nameof(SourceBadgeBg));
        OnPropertyChanged(nameof(SourceBadgeFg));
        OnPropertyChanged(nameof(UpdateButtonText));
        CommandManager.InvalidateRequerySuggested();
    }

    /// <summary>
    /// 源码卡片标题：日期时间 + 短 sha，例如「已安装 2026-11-15 22:19 · 2084401」。
    /// 光看 hash 认不出新旧，带上时间才能一眼判断装的是哪个版本。
    /// 时间取提交时间（installedCommitDate）；老 state.json 没这字段时退回安装时刻（installedAt），
    /// 两者都没有就只显示 hash。
    /// </summary>
    private string DescribeInstalledTitle()
    {
        var sha = _state.InstalledShortSha;
        if (string.IsNullOrEmpty(sha)) return "Cloned (version unknown)";

        var stamp = ParseStamp(_state.InstalledCommitDate) ?? ParseStamp(_state.InstalledAt);
        return stamp is null
            ? $"Installed {sha}"
            : $"Installed {stamp.Value.ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture)}  #{sha}";
    }

    /// <summary>解析 state.json 里的 ISO 时间串（带偏移），转成本地时区；解析失败返回 null。</summary>
    private static DateTimeOffset? ParseStamp(string? iso)
        => DateTimeOffset.TryParse(iso, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var value)
            ? value.ToLocalTime()
            : null;

    private void CopyLogToClipboard()
    {
        try
        {
            Clipboard.SetText(_log.Dump());
            StatusText = "Log copied to clipboard";
        }
        catch (Exception ex)
        {
            StatusText = "Copy failed: " + ex.Message;
        }
    }

    private static void OpenInExplorer(string path)
    {
        try
        {
            if (!Directory.Exists(path)) Directory.CreateDirectory(path);
            Process.Start(new ProcessStartInfo("explorer.exe", $"\"{path}\"") { UseShellExecute = true });
        }
        catch
        {
            // ignore
        }
    }

    private static void OpenUrl(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch
        {
            // ignore
        }
    }

    public async Task ShutdownAsync()
    {
        try { _opCts?.Cancel(); } catch { }
        // 正在编译时退出：取消会把 mvn 的整棵进程树杀掉，不留下跑到一半的编译
        try { _buildCts?.Cancel(); } catch { }

        // 客户端是独立进程，退出启动器时故意不杀它，只解绑事件
        _client.Dispose();

        if (IsServerRunning)
        {
            _log.Info("SERVER", "Exiting launcher, stopping the background server…");
            await _server.StopAsync(4000);
        }

        // 服务停干净之后再排替换：helper 只等启动器这个 PID，服务进程不受影响
        TryApplyStagedUpdateOnExit();
    }
}
