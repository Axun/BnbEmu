using System.Diagnostics;
using System.IO;
using System.Text;

namespace BnbLauncher.Services;

/// <summary>在 runtime\source 里执行 mvn compile exec:java，并把输出逐行转发给 UI。</summary>
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

    public void Start(JdkInfo jdk, MavenInfo maven)
    {
        if (IsRunning) throw new InvalidOperationException("The server is already running.");

        if (!File.Exists(maven.MvnCmd))
            throw new FileNotFoundException("mvn.cmd not found", maven.MvnCmd);
        if (!Directory.Exists(AppPaths.SourceDir))
            throw new DirectoryNotFoundException("Source folder not found. Download the source first.");

        var goals = $"-B -Dstyle.color=never -Dmaven.repo.local=\"{AppPaths.M2RepoDir}\" compile exec:java";

        var psi = new ProcessStartInfo
        {
            FileName = Environment.GetEnvironmentVariable("COMSPEC") ?? "cmd.exe",
            Arguments = BuildArguments(maven, goals),
            WorkingDirectory = AppPaths.SourceDir,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };

        foreach (var (key, value) in BuildEnvironment(jdk, maven)) psi.Environment[key] = value;

        LastCommand = maven.MvnCmd + " " + goals;

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

    /// <summary>
    /// cmd 的 /s /c 引号规则：整条命令再包一层引号，mvn 路径单独包引号
    /// （路径里可能有空格）。最终形如：/d /s /c ""C:\...\mvn.cmd" -B …"
    /// </summary>
    public static string BuildArguments(MavenInfo maven, string goals)
        => "/d /s /c \"" + "\"" + maven.MvnCmd + "\" " + goals + "\"";

    public static Dictionary<string, string> BuildEnvironment(JdkInfo jdk, MavenInfo maven)
    {
        var path = Environment.GetEnvironmentVariable("PATH") ?? "";
        return new Dictionary<string, string>
        {
            ["PATH"] = jdk.Home + "\\bin;" + maven.Home + "\\bin;" + path,
            ["JAVA_HOME"] = jdk.Home,
            ["MAVEN_OPTS"] = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Xmx1024m",
            ["MAVEN_SKIP_RC"] = "true",
        };
    }

    public async Task StopAsync(int timeoutMs = 8000)    {
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
                // taskkill 兜底：Job Object 失效时也能带走 cmd → mvn → java 整棵树
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
