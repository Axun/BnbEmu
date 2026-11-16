using System.IO;

namespace BnbLauncher.Services;

/// <summary>
/// 命令行打包：<c>BnbLauncher.exe --pack-idd &lt;解包目录&gt; &lt;输出基名&gt; [--force]</c>
///
/// 等价于 <c>python tools/data/idd_build.py --dir &lt;解包目录&gt; --out &lt;输出基名&gt;</c>，
/// 但没有 python 也能用，且走的是启动器自己启动客户端时的那套代码。
/// 输出基名不含扩展名，会在旁边生成 .idd / .idx；同名文件已存在时需要 --force。
/// </summary>
public static class IddPackCommand
{
    public static int Run(string[] args)
    {
        SelfTest.AttachToConsole();

        var positional = args
            .SkipWhile(a => !a.Equals("--pack-idd", StringComparison.OrdinalIgnoreCase))
            .Skip(1)
            .Where(a => !a.StartsWith("--", StringComparison.Ordinal))
            .ToList();

        if (positional.Count < 2)
        {
            SelfTest.ConsoleWrite("Usage: BnbLauncher.exe --pack-idd <unpacked-dir> <output-base> [--force]");
            return 2;
        }

        var workDir = Path.GetFullPath(positional[0]);
        var outBase = Path.GetFullPath(positional[1]);
        var force = args.Any(a => a.Equals("--force", StringComparison.OrdinalIgnoreCase));

        if (!force && (File.Exists(outBase + ".idd") || File.Exists(outBase + ".idx")))
        {
            SelfTest.ConsoleWrite($"Output already exists; add --force to overwrite: {outBase}.idd / .idx");
            return 2;
        }

        try
        {
            SelfTest.ConsoleWrite($"pack: {workDir} -> {outBase}.idd / .idx");

            var progress = new InlineProgress(p => SelfTest.ConsoleWrite($"  {p.Phase}"));
            var result = IddArchiveBuilder.Build(workDir, outBase, SelfTest.ConsoleWrite, progress);

            SelfTest.ConsoleWrite($"built: {result.IddPath} ({result.IddSize} B) / {result.IdxPath} ({result.IdxSize} B)");
            SelfTest.ConsoleWrite(
                $"entries={result.Entries} reused={result.Reused} rebuilt={result.Rebuilt} " +
                $"added={result.Added} missing={result.Missing} key=0x{result.Key:X8} " +
                $"elapsed={result.Elapsed.TotalSeconds:0.0}s");
            SelfTest.ConsoleWrite("RESULT: OK");
            return 0;
        }
        catch (Exception ex)
        {
            SelfTest.ConsoleWrite("RESULT: FAILED " + ex);
            return 1;
        }
    }

    /// <summary>
    /// 同步转发的 IProgress。命令行下不能用 <see cref="Progress{T}"/>：它会捕获 WPF 的
    /// DispatcherSynchronizationContext，把回调推迟到消息泵，日志顺序会全乱。
    /// </summary>
    private sealed class InlineProgress(Action<SetupProgress> action) : IProgress<SetupProgress>
    {
        public void Report(SetupProgress value) => action(value);
    }
}
