using System.IO;
using System.IO.Compression;

namespace BnbLauncher.Services;

public static class ZipHelper
{
    /// <summary>
    /// 解压 zip 到 targetDir；stripSingleRoot=true 时去掉压缩包里的顶层目录
    /// （GitHub 源码包 / JDK / Maven 都多套一层目录）。
    /// </summary>
    public static async Task ExtractAsync(string zipPath, string targetDir, bool stripSingleRoot,
        IProgress<double>? progress = null, CancellationToken ct = default)
    {
        await Task.Run(() =>
        {
            Directory.CreateDirectory(targetDir);
            var targetFull = Path.GetFullPath(targetDir + Path.DirectorySeparatorChar);

            using var zip = ZipFile.OpenRead(zipPath);
            var entries = zip.Entries;

            string? rootPrefix = null;
            if (stripSingleRoot && entries.Count > 0)
            {
                var first = entries[0].FullName;
                var slash = first.IndexOf('/');
                if (slash > 0)
                {
                    var candidate = first[..(slash + 1)];
                    if (entries.All(e => e.FullName.StartsWith(candidate, StringComparison.Ordinal)))
                        rootPrefix = candidate;
                }
            }

            long done = 0;
            var total = Math.Max(1, entries.Count);
            foreach (var entry in entries)
            {
                ct.ThrowIfCancellationRequested();

                var name = entry.FullName.Replace('\\', '/');
                if (rootPrefix is not null)
                {
                    if (!name.StartsWith(rootPrefix, StringComparison.Ordinal)) continue;
                    name = name[rootPrefix.Length..];
                }
                if (name.Length == 0) continue;

                // 只允许普通文件，阻断 ../ 逃逸（zip slip）
                var full = Path.GetFullPath(Path.Combine(targetFull, name));
                if (!full.StartsWith(targetFull, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException("Archive contains an invalid path: " + entry.FullName);

                if (name.EndsWith('/'))
                {
                    Directory.CreateDirectory(full);
                }
                else
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(full)!);
                    entry.ExtractToFile(full, overwrite: true);
                }

                done++;
                if (done % 64 == 0) progress?.Report(done * 100d / total);
            }

            progress?.Report(100);
        }, ct);
    }

    /// <summary>压缩包内的顶层目录名（未 strip 时用于定位）。</summary>
    public static string? PeekSingleRoot(string zipPath)
    {
        using var zip = ZipFile.OpenRead(zipPath);
        var first = zip.Entries.FirstOrDefault()?.FullName;
        var slash = first?.IndexOf('/') ?? -1;
        return slash > 0 ? first![..slash] : null;
    }

    public static void TryDeleteDirectory(string path)
    {
        try
        {
            if (Directory.Exists(path)) Directory.Delete(path, recursive: true);
        }
        catch
        {
            // 文件被占用时留到下次清理
        }
    }

    public static void TryDeleteFile(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch
        {
            // 文件被占用时留到下次清理
        }
    }

    /// <summary>装成功后丢弃下载包及 .part 残片（JDK 那份 194MB，留着白占地方）；失败路径不调用。</summary>
    public static void DiscardArchive(string zipPath)
    {
        TryDeleteFile(zipPath);
        TryDeleteFile(zipPath + ".part");
    }
}
