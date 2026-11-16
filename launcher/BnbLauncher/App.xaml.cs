using System.Threading;
using System.Windows;
using System.Windows.Threading;
using BnbLauncher.Services;
using BnbLauncher.ViewModels;

namespace BnbLauncher;

public partial class App : Application
{
    private Mutex? _mutex;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        AppPaths.EnsureDirectories();

        if (e.Args.Any(a => a.Equals("--selftest", StringComparison.OrdinalIgnoreCase)))
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            _ = RunSelfTestAsync(e.Args);
            return;
        }

        if (e.Args.Any(a => a.Equals("--pack-idd", StringComparison.OrdinalIgnoreCase)))
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            Shutdown(IddPackCommand.Run(e.Args));
            return;
        }

        // 只查启动器自身更新，不开界面：发布后冒烟、排查清单/网络问题都用它
        if (e.Args.Any(a => a.Equals("--check-update", StringComparison.OrdinalIgnoreCase)))
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            _ = RunCheckUpdateAsync(e.Args);
            return;
        }

        if (e.Args.Any(a => a.Equals("--prepare-client", StringComparison.OrdinalIgnoreCase)))
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            _ = RunPrepareClientAsync(e.Args);
            return;
        }

        // 同一个安装目录只允许一个实例；不同目录的副本可以各自运行（各自的服务/端口不同）
        var key = "Local\\BnbLauncher." + Convert.ToHexString(
            System.Security.Cryptography.SHA256.HashData(
                System.Text.Encoding.UTF8.GetBytes(AppPaths.BaseDir.ToLowerInvariant())))[..16];

        _mutex = new Mutex(true, key, out var created);
        if (!created)
        {
            MessageBox.Show("The BnbEmu launcher in this folder is already running.", "BnbEmu Launcher",
                MessageBoxButton.OK, MessageBoxImage.Information);
            Shutdown();
            return;
        }

        DispatcherUnhandledException += OnUnhandledException;

        var window = new MainWindow();
        MainWindow = window;
        window.Show();
    }

    private async Task RunSelfTestAsync(string[] args)
    {
        try
        {
            var code = await SelfTest.RunAsync(args);
            Shutdown(code);
        }
        catch (Exception ex)
        {
            SelfTest.ConsoleWrite("FATAL " + ex);
            Shutdown(1);
        }
    }

    private async Task RunPrepareClientAsync(string[] args)
    {
        try
        {
            var code = await ClientPrepareCommand.RunAsync(args);
            Shutdown(code);
        }
        catch (Exception ex)
        {
            SelfTest.ConsoleWrite("FATAL " + ex);
            Shutdown(1);
        }
    }

    private async Task RunCheckUpdateAsync(string[] args)
    {
        try
        {
            Shutdown(await SelfTest.CheckUpdateAsync(
                args.Any(a => a.Equals("--download", StringComparison.OrdinalIgnoreCase))));
        }
        catch (Exception ex)
        {
            SelfTest.ConsoleWrite("FATAL " + ex);
            Shutdown(1);
        }
    }

    private void OnUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        e.Handled = true;
        try
        {
            MessageBox.Show(e.Exception.Message, "Unhandled Error",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
        catch
        {
            // ignore
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _mutex?.Dispose();
        base.OnExit(e);
    }
}
