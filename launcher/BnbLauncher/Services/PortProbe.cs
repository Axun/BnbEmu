using System.Net;
using System.Net.Sockets;

namespace BnbLauncher.Services;

public static class PortProbe
{
    /// <summary>端口是否可以监听（被占用返回 false）。</summary>
    public static bool IsFree(int port)
    {
        try
        {
            var listener = new TcpListener(IPAddress.Any, port);
            listener.Start();
            listener.Stop();
            return true;
        }
        catch (SocketException)
        {
            return false;
        }
        catch
        {
            return true;
        }
    }

    public static int[] BusyPorts(params int[] ports) => ports.Where(p => !IsFree(p)).ToArray();
}
