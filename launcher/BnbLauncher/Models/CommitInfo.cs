using System.Globalization;

namespace BnbLauncher.Models;

/// <summary>界面展示用的提交信息。</summary>
public sealed class CommitInfo
{
    public CommitInfo(string sha, string message, string author, DateTimeOffset? date)
    {
        Sha = sha;
        ShortSha = sha.Length > 7 ? sha[..7] : sha;
        Message = message;
        Author = author;
        Date = date;
    }

    public string Sha { get; }
    public string ShortSha { get; }
    public string Message { get; }
    public string Author { get; }
    public DateTimeOffset? Date { get; }

    /// <summary>只有 sha、没有提交对象时用（例如还没克隆，只能 ls-remote 到远端 tip）。</summary>
    public static CommitInfo FromSha(string sha) => new(sha, "", "", null);

    public string Title
    {
        get
        {
            var line = Message.Replace("\r", "").Split('\n')[0].Trim();
            return string.IsNullOrEmpty(line) ? "(no commit message)" : line;
        }
    }

    public string DateText => Date is null
        ? "Unknown time"
        : Date.Value.ToLocalTime().ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture);

    public string RelativeText => TimeText.Relative(Date);
}

public static class TimeText
{
    /// <summary>把时间转成「刚刚 / 3 分钟前 / 2 天前 / 2026-10-01」。</summary>
    public static string Relative(DateTimeOffset? value)
    {
        if (value is null) return "unknown";
        var local = value.Value.ToLocalTime();
        var delta = DateTimeOffset.Now - local;

        if (delta.TotalSeconds < 0) delta = TimeSpan.Zero;
        if (delta.TotalMinutes < 1) return "just now";
        if (delta.TotalMinutes < 60) return $"{(int)delta.TotalMinutes} min ago";
        if (delta.TotalHours < 24) return $"{(int)delta.TotalHours} h ago";
        if (delta.TotalDays < 7) return $"{(int)delta.TotalDays} d ago";
        if (delta.TotalDays < 30) return $"{(int)(delta.TotalDays / 7)} w ago";
        return local.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
    }

    public static string Size(long bytes)
    {
        if (bytes <= 0) return "0 B";
        string[] units = ["B", "KB", "MB", "GB"];
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < units.Length - 1) { v /= 1024; i++; }
        return i == 0 ? $"{bytes} B" : $"{v:0.0} {units[i]}";
    }
}
