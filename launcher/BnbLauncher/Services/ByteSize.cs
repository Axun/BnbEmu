using System.Globalization;

namespace BnbLauncher.Services;

/// <summary>把字节数写成「173 MB」这种人看得懂的形式。</summary>
public static class ByteSize
{
    private static readonly string[] Units = ["B", "KB", "MB", "GB", "TB"];

    public static string Format(long bytes)
    {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes.ToString(CultureInfo.InvariantCulture) + " B";

        double value = bytes;
        var unit = 0;
        while (value >= 1024 && unit < Units.Length - 1)
        {
            value /= 1024;
            unit++;
        }

        var digits = value >= 100 ? 0 : value >= 10 ? 1 : 2;
        return value.ToString("F" + digits, CultureInfo.InvariantCulture) + " " + Units[unit];
    }
}
