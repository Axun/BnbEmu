using BnbLauncher.Models;

namespace BnbLauncher.Services;

/// <summary>
/// 命令行准备客户端目录：<c>BnbLauncher.exe --prepare-client [--force]</c>
///
/// 走的就是界面上「启动客户端」第一次点击时的同一套逻辑（拉 resource 子模块、
/// 复制 exe/dll、打包 idd/idx），只是不开窗口、不启动游戏。适合改完资源后
/// 一次性把 runtime\client 铺好，或者排查打包问题。
/// <c>--force</c> 会忽略指纹强制重新打包三个资源包。
/// </summary>
public static class ClientPrepareCommand
{
    public static async Task<int> RunAsync(string[] args)
    {
        SelfTest.AttachToConsole();

        var force = args.Any(a => a.Equals("--force", StringComparison.OrdinalIgnoreCase));
        var config = JsonStore.Load(AppPaths.ConfigPath, () => new AppConfig());
        var provisioner = new ClientProvisioner(new LogBus()) { Echo = SelfTest.ConsoleWrite };

        SelfTest.ConsoleWrite($"[client] source = {AppPaths.SourceDir}");
        SelfTest.ConsoleWrite($"[client] target = {AppPaths.ClientDir}  (force={force})");

        try
        {
            await provisioner.PrepareAsync(config, null, force, CancellationToken.None);
        }
        catch (Exception ex)
        {
            SelfTest.ConsoleWrite("[client] failed: " + ex.Message);
            SelfTest.ConsoleWrite("RESULT: FAILED");
            return 1;
        }
        finally
        {
            provisioner.Dispose();
        }

        if (!ClientProvisioner.IsPrepared)
        {
            SelfTest.ConsoleWrite("[client] client files are still incomplete");
            SelfTest.ConsoleWrite("RESULT: FAILED");
            return 1;
        }

        SelfTest.ConsoleWrite($"[client] ready: {ClientProvisioner.ArchiveNames.Length} resource packs, " +
                              $"totaling {ByteSize.Format(ClientProvisioner.ArchiveBytes())}");
        SelfTest.ConsoleWrite("RESULT: OK");
        return 0;
    }
}
