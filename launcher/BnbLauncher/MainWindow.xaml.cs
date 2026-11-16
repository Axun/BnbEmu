using System.ComponentModel;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using BnbLauncher.ViewModels;

namespace BnbLauncher;

public partial class MainWindow : Window
{
    private readonly MainViewModel _vm = new();
    private readonly DispatcherTimer _scrollTimer;
    private bool _initialized;
    private bool _scrollPending;
    private bool _shuttingDown;
    private bool _shutdownComplete;
    private bool _restarting;

    public MainWindow()
    {
        InitializeComponent();

        AppLogo.Source = LoadHighResAppIcon();

        // 高 DPI 小屏幕上避免窗口超出可用区域
        var area = SystemParameters.WorkArea;
        if (area.Width > 0 && area.Height > 0)
        {
            Width = Math.Min(Width, Math.Max(MinWidth, area.Width - 32));
            Height = Math.Min(Height, Math.Max(MinHeight, area.Height - 32));
        }

        DataContext = _vm;
        _vm.CommitsRequested += ShowCommits;
        _vm.SettingsRequested += ShowSettings;
        // 换启动器必须先让进程彻底退出（Windows 不允许覆盖正在运行的映像），
        // 所以这里只负责关窗，文件替换交给退出后仍在跑的 helper 脚本
        _vm.RestartRequested += OnRestartRequested;
        _vm.Logs.CollectionChanged += (_, _) => _scrollPending = true;

        _scrollTimer = new DispatcherTimer(TimeSpan.FromMilliseconds(180), DispatcherPriority.Background,
            (_, _) => ScrollToEndIfNeeded(), Dispatcher);
        _scrollTimer.Start();

        Loaded += OnLoaded;
        Closing += OnClosing;
    }

    /// <summary>
    /// 左上角徽标使用 exe 图标 Assets/app.ico 里分辨率最高的一帧（当前 256×256）。
    /// 直接把 .ico 交给 BitmapImage / Image.Source 时，WPF 会取 ico 目录里的第一帧
    /// （本文件是 16×16），放大后明显发虚；IconBitmapDecoder 可以枚举全部 6 帧，
    /// 这里按像素宽度挑最大的一帧，ico 更换后也无需同步修改界面。
    /// </summary>
    private static ImageSource LoadHighResAppIcon()
    {
        try
        {
            var resource = Application.GetResourceStream(new Uri("Assets/app.ico", UriKind.Relative));
            if (resource != null)
            {
                using var stream = resource.Stream;
                var decoder = new IconBitmapDecoder(stream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
                // BitmapCacheOption.OnLoad 已把像素缓存进内存，流可以安全释放
                var largest = decoder.Frames.OrderByDescending(f => f.PixelWidth).FirstOrDefault();
                if (largest != null) return largest;
            }
        }
        catch
        {
            // 资源读取异常时退回默认 ico 帧，保证徽标不会变成空白
        }

        return new BitmapImage(new Uri("pack://application:,,,/Assets/app.ico"));
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (_initialized) return;
        _initialized = true;
        await _vm.InitializeAsync();
    }

    private void ScrollToEndIfNeeded()
    {
        if (!_scrollPending || !_vm.AutoScroll) return;
        _scrollPending = false;

        if (LogList.Items.Count == 0) return;
        try
        {
            LogList.ScrollIntoView(LogList.Items[^1]);
        }
        catch
        {
            // ignore
        }
    }

    private void ShowCommits(CommitListPayload payload)
    {
        var window = new CommitWindow(payload) { Owner = this };
        window.ShowDialog();
    }

    private void ShowSettings()
    {
        var window = new SettingsWindow(_vm.Config) { Owner = this };
        if (window.ShowDialog() == true && window.Result is not null)
        {
            _vm.ApplyConfig(window.Result);
        }
    }

    /// <summary>更新流程已经把服务停掉的意图问过了，这里不要再弹「退出会停止服务」。</summary>
    private void OnRestartRequested()
    {
        _restarting = true;
        Close();
    }

    private async void OnClosing(object? sender, CancelEventArgs e)
    {
        if (_shutdownComplete) return;

        e.Cancel = true;
        if (_shuttingDown) return;

        if (_vm.IsServerRunning && !_restarting)
        {
            var answer = MessageBox.Show(this, "The server is running; exiting the launcher will stop it too.\nExit anyway?",
                "Confirm Exit", MessageBoxButton.YesNo, MessageBoxImage.Question);
            if (answer != MessageBoxResult.Yes) return;
        }

        _shuttingDown = true;
        try
        {
            await _vm.ShutdownAsync();
        }
        finally
        {
            _shutdownComplete = true;
            Application.Current.Shutdown();
        }
    }
}
