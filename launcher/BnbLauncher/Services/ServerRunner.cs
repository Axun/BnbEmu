using System.Diagnostics;
using System.IO;
using System.Text;

namespace BnbLauncher.Services;

/// <summary>
/// 用 JDK 直接启动服务端（java -cp "target\classes;依赖 jar" bnbemu.MainKt）。
///
/// 以前是 mvn compile exec:java：每次启动都让 kotlin-maven-plugin 全量重编（十几秒），
/// 而且 exec:java 不开新进程，服务跑在 Maven 的 JVM 里，MAVEN_OPTS 的 -Xmx1024m 实际上
/// 成了服务的堆上限。现在编译交给 ServerBuild（只在源码变动时发生），运行时完全绕开 Maven：
/// 启动只剩 JVM + Netty 的固定开销，堆和 GC 参数也回到自己手里。
/// </summary>
public sealed class ServerRunner : IDisposable
{
    private Process? _proc;
    private IntPtr _job;
    private bool _stopping;

    public event Action<string>? Output;
    public event Action<int>? Exited;

    public bool IsRunning => _proc is { HasExited: false };
    public int ProcessId => _proc?.Id ?? 0;
    public string LastCommand { get; private set; } = "";

    public void Start(JdkInfo jdk)
    {
        if (IsRunning) throw new InvalidOperationException("The server is already running.");

        var java = Path.Combine(jdk.Home, "bin", "java.exe");
        if (!File.Exists(java))
            throw new FileNotFoundException("java.exe not found", java);
        if (!File.Exists(AppPaths.ServerMainClassFile))
            throw new InvalidOperationException("The server has not been compiled yet.");
        if (!File.Exists(AppPaths.ServerClasspathFile))
            throw new InvalidOperationException("The dependency classpath snapshot is missing.");

        var classpath = ServerBuild.ClasspathArgument();

        var psi = new ProcessStartInfo
        {
            FileName = java,
            // 服务端用 File("Config.yaml") 这类相对路径读配置，工作目录必须是源码目录
            WorkingDirectory = AppPaths.SourceDir,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };

        // ArgumentList 会替每个参数正确加引号（classpath 里有空格也不会散架），
        // 也就不再需要以前那套 cmd /s /c 的引号规则。
        psi.ArgumentList.Add("-Xmx1024m");
        psi.ArgumentList.Add("-Dfile.encoding=UTF-8");
        psi.ArgumentList.Add("-Dsun.stdout.encoding=UTF-8");
        psi.ArgumentList.Add("-Dsun.stderr.encoding=UTF-8");
        psi.ArgumentList.Add("-cp");
        psi.ArgumentList.Add(classpath);
        psi.ArgumentList.Add(AppPaths.ServerMainClass);

        LastCommand = java + " -cp <" + classpath.Split(';').Length + " entries> " + AppPaths.ServerMainClass;

        _stopping = false;
        _proc = new Process { StartInfo = psi, EnableRaisingEvents = true };
        _proc.OutputDataReceived += (_, e) => { if (e.Data is not null) Output?.Invoke(e.Data); };
        _proc.ErrorDataReceived += (_, e) => { if (e.Data is not null) Output?.Invoke(e.Data); };
        _proc.Exited += OnProcessExited;
        _proc.Start();
        _proc.BeginOutputReadLine();
        _proc.BeginErrorReadLine();

        _job = NativeMethods.CreateKillOnCloseJob();
        if (_job != IntPtr.Zero && !NativeMethods.AssignProcessToJobObject(_job, _proc.Handle))
        {
            NativeMethods.CloseHandle(_job);
            _job = IntPtr.Zero;
        }
    }

    private void OnProcessExited(object? sender, EventArgs e)
    {
        int code;
        try { code = _proc?.ExitCode ?? -1; }
        catch { code = -1; }
        if (!_stopping) Exited?.Invoke(code);
    }

    public async Task StopAsync(int timeoutMs = 8000)
    {
        var proc = _proc;
        if (proc is null) return;

        _stopping = true;

        if (_job != IntPtr.Zero)
        {
            NativeMethods.CloseHandle(_job);
            _job = IntPtr.Zero;
        }

        try
        {
            if (!proc.HasExited)
            {
                // taskkill 兜底：Job Object 失效时也能带走整个进程树
                await ProcessUtil.RunAsync("taskkill", $"/PID {proc.Id} /T /F",
                    timeout: TimeSpan.FromSeconds(5));
            }
        }
        catch
        {
            // ignore
        }

        try
        {
            if (!proc.HasExited) await proc.WaitForExitAsync().WaitAsync(TimeSpan.FromMilliseconds(timeoutMs));
            proc.WaitForExit(); // 等异步输出读完
        }
        catch
        {
            // ignore
        }

        try { proc.Dispose(); } catch { }
        _proc = null;
        _stopping = false;
    }

    public void Dispose()
    {
        try { _ = StopAsync(3000); } catch { }
        if (_job != IntPtr.Zero) NativeMethods.CloseHandle(_job);
    }
}
