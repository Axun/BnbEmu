using System.Collections.ObjectModel;
using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;

namespace BnbLauncher.Services;

public enum LogLevel
{
    Sys,
    Info,
    Success,
    Warn,
    Error,
    Server,
}

public sealed class LogLine
{
    public LogLine(LogLevel level, string tag, string text)
    {
        Time = DateTime.Now.ToString("HH:mm:ss");
        Level = level;
        Tag = tag;
        Text = text;
        (LevelText, LevelBrush) = level switch
        {
            LogLevel.Warn => ("WARN", Ui.Brush("#B45309")),
            LogLevel.Error => ("ERR ", Ui.Brush("#B91C1C")),
            LogLevel.Success => ("OK  ", Ui.Brush("#15803D")),
            LogLevel.Sys => ("SYS ", Ui.Brush("#0F766E")),
            LogLevel.Server => ("RUN ", Ui.Brush("#1D4ED8")),
            _ => ("INFO", Ui.Brush("#9CA3AF")),
        };
        TagBrush = Ui.Brush("#0F766E");
        TextBrush = Ui.Brush(level == LogLevel.Server ? "#1F2937" : "#374151");
    }

    public string Time { get; }
    public LogLevel Level { get; }
    public string Tag { get; }
    public string Text { get; }
    public string LevelText { get; }
    public Brush LevelBrush { get; }
    public Brush TagBrush { get; }
    public Brush TextBrush { get; }
}

internal static class Ui
{
    private static readonly Dictionary<string, SolidColorBrush> Cache = [];

    public static SolidColorBrush Brush(string hex)
    {
        lock (Cache)
        {
            if (Cache.TryGetValue(hex, out var b)) return b;
            var brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex));
            brush.Freeze();
            Cache[hex] = brush;
            return brush;
        }
    }
}

/// <summary>日志中枢：内存集合（绑定 UI）+ 文件落盘。</summary>
public sealed class LogBus
{
    private const int MaxLines = 4000;
    private readonly object _fileLock = new();
    private readonly Dispatcher _dispatcher;
    private StreamWriter? _writer;
    private string _logFile = "";

    public LogBus()
    {
        _dispatcher = Application.Current?.Dispatcher ?? Dispatcher.CurrentDispatcher;
        Lines = [];
        OpenLogFile();
    }

    public ObservableCollection<LogLine> Lines { get; }

    public string LogFile => _logFile;

    public event Action<LogLine>? LineAdded;

    private void OpenLogFile()
    {
        try
        {
            Directory.CreateDirectory(AppPaths.LogsDir);
            _logFile = Path.Combine(AppPaths.LogsDir, $"launcher-{DateTime.Now:yyyyMMdd}.log");
            var stream = new FileStream(_logFile, FileMode.Append, FileAccess.Write, FileShare.ReadWrite);
            _writer = new StreamWriter(stream, new UTF8Encoding(false)) { AutoFlush = true };
        }
        catch
        {
            _writer = null;
        }
    }

    public void Write(LogLevel level, string tag, string text)
    {
        var line = new LogLine(level, tag, text);
        AppendToFile(line);
        if (_dispatcher.CheckAccess()) Append(line);
        else _dispatcher.BeginInvoke(() => Append(line), DispatcherPriority.Background);
    }

    public void Sys(string text) => Write(LogLevel.Sys, "LAUNCH", text);
    public void Info(string tag, string text) => Write(LogLevel.Info, tag, text);
    public void Success(string tag, string text) => Write(LogLevel.Success, tag, text);
    public void Warn(string tag, string text) => Write(LogLevel.Warn, tag, text);
    public void Error(string tag, string text) => Write(LogLevel.Error, tag, text);
    public void Server(string text) => Write(LogLevel.Server, "SERVER", text);

    private void Append(LogLine line)
    {
        Lines.Add(line);
        if (Lines.Count > MaxLines)
        {
            for (int i = 0; i < 500 && Lines.Count > 0; i++) Lines.RemoveAt(0);
        }
        LineAdded?.Invoke(line);
    }

    private void AppendToFile(LogLine line)
    {
        if (_writer is null) return;
        lock (_fileLock)
        {
            try
            {
                _writer.WriteLine($"{DateTime.Now:yyyy-MM-dd HH:mm:ss} [{line.LevelText.Trim()}] [{line.Tag}] {line.Text}");
            }
            catch
            {
                // 日志写失败不能影响主流程
            }
        }
    }

    public void Clear()
    {
        if (_dispatcher.CheckAccess()) Lines.Clear();
        else _dispatcher.BeginInvoke(Lines.Clear);
    }

    public string Dump() => string.Join(Environment.NewLine,
        Lines.Select(l => $"{l.Time} [{l.LevelText.Trim()}] [{l.Tag}] {l.Text}"));
}
