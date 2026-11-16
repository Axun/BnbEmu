using System.Diagnostics;
using System.Text;

namespace BnbLauncher.Services;

/// <summary>给 UI 的进度反馈：Percent &lt; 0 表示不确定进度（转圈）。</summary>
public readonly record struct SetupProgress(string Phase, double Percent)
{
    public static SetupProgress Indeterminate(string phase) => new(phase, -1);
}

public static class ProcessUtil
{
    public static async Task<(int ExitCode, string Output)> RunAsync(string fileName, string arguments,
        string? workingDirectory = null, IDictionary<string, string>? environment = null,
        TimeSpan? timeout = null, CancellationToken ct = default)
    {
        var psi = new ProcessStartInfo
        {
            FileName = fileName,
            Arguments = arguments,
            WorkingDirectory = workingDirectory ?? Environment.CurrentDirectory,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        if (environment is not null)
        {
            foreach (var (k, v) in environment) psi.Environment[k] = v;
        }

        using var proc = new Process { StartInfo = psi };
        proc.Start();
        var stdout = proc.StandardOutput.ReadToEndAsync(ct);
        var stderr = proc.StandardError.ReadToEndAsync(ct);

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        if (timeout is not null) cts.CancelAfter(timeout.Value);

        try
        {
            await proc.WaitForExitAsync(cts.Token);
        }
        catch (OperationCanceledException)
        {
            try { proc.Kill(entireProcessTree: true); } catch { }
            return (-1, "Timed out");
        }

        var text = (await stdout) + (await stderr);
        return (proc.ExitCode, text.Trim());
    }
}
