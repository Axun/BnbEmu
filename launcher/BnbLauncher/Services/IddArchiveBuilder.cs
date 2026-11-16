using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace BnbLauncher.Services;

/// <summary>
/// 把解包目录重新打包成 .idd + .idx —— <c>tools/data/idd_build.py</c> 的 C# 实现。
///
/// 目录里的 <c>_idd_manifest.json</c> 就是可编辑的 IDX 源：
/// <list type="bullet">
/// <item>改某个 .bin 内容 → 自动重新加密/压缩、重算 aux CRC、重排 offset；</item>
/// <item>manifest 里的 name / seed / mode 就是打包依据，条目统一按大小写不敏感的字典序重排
///   （客户端 sub_767490 用 _mbsicmp 二分查找，顺序不能错）；</item>
/// <item>目录里新出现的文件（manifest 未记录）→ 作为明文条目追加，seed=0；</item>
/// <item>mode：<c>raw</c> 明文块 / <c>seeded</c> 加密块 / <c>mm</c> zlib 容器（目录里存明文）/
///   <c>mmraw</c> zlib 容器（目录里存原容器）。</item>
/// </list>
///
/// 未改动的条目优先复用上一次产物（或 manifest 记录的原始 .idd）里的块字节，
/// 所以「原样解包 → 原样打包」逐字节一致；改过的条目重新加密并重算 aux，
/// 保证真端 sub_75D440 的校验过得去。
///
/// 与 python 版的差异：不交互、不回写 manifest（resource 是 git 子模块，改脏了不好），
/// 缺失文件的条目直接跳过并记一条日志；写完会回读校验一遍。
/// </summary>
public static class IddArchiveBuilder
{
    public const string ManifestName = "_idd_manifest.json";
    public const string ManifestSchema = "bnbemu/idd-workspace@1";

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
    };

    /// <summary>一次打包的结果。</summary>
    public sealed class Result
    {
        public string IddPath { get; init; } = "";
        public string IdxPath { get; init; } = "";
        public long IddSize { get; init; }
        public long IdxSize { get; init; }
        public uint Key { get; init; }
        public int Entries { get; init; }
        public int Reused { get; init; }
        public int Rebuilt { get; init; }
        public int Added { get; init; }
        public int Missing { get; init; }
        public TimeSpan Elapsed { get; init; }

        public string Summary =>
            $"{Entries} entries (reused {Reused} / rebuilt {Rebuilt}" +
            (Added > 0 ? $" / added {Added}" : "") +
            (Missing > 0 ? $" / skipped {Missing} missing" : "") + ")";
    }

    /// <summary>
    /// 打包 <paramref name="workDir"/> 目录到 <c>outputBase.idd/.idx</c>。
    /// 输出已存在时先写 .tmp 再整体替换，中途失败不会留下半个包。
    /// </summary>
    public static Result Build(string workDir, string outputBase, Action<string>? log = null,
        IProgress<SetupProgress>? progress = null, CancellationToken ct = default)
    {
        var sw = Stopwatch.StartNew();
        workDir = Path.GetFullPath(workDir);
        outputBase = Path.GetFullPath(outputBase);

        var manifestPath = Path.Combine(workDir, ManifestName);
        if (!File.Exists(manifestPath))
        {
            throw new FileNotFoundException($"Missing {ManifestName}; unpack the archive with tools/data/idd_extract.py first", manifestPath);
        }

        var manifest = JsonSerializer.Deserialize<IddManifestDto>(File.ReadAllText(manifestPath), JsonOptions)
                       ?? throw new InvalidDataException($"Failed to parse {ManifestName}");
        if (!string.Equals(manifest.Schema, ManifestSchema, StringComparison.Ordinal))
        {
            throw new InvalidDataException($"Manifest schema mismatch: {manifest.Schema}");
        }

        var archiveName = string.IsNullOrEmpty(manifest.Archive) ? Path.GetFileName(outputBase) : manifest.Archive;
        var key = ParseHex(manifest.Source?.Key);
        var (v0, v1) = manifest.Source?.Header is { Count: >= 2 } h ? (h[0], h[1]) : (0u, IddCodec.IdxVersion);

        // ── 1. 收集条目（manifest + 目录里新出现的文件） ──────────────────
        var known = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var entries = new List<BuildEntry>(manifest.Entries.Count);
        foreach (var dto in manifest.Entries)
        {
            var file = Normalize(dto.File);
            if (file.Length == 0) continue;
            known.Add(file);
            entries.Add(new BuildEntry
            {
                Name = dto.Name ?? "",
                File = file,
                Mode = string.IsNullOrEmpty(dto.Mode) ? "raw" : dto.Mode,
                Seed = ParseHex(dto.Seed),
                Aux = ParseHex(dto.Aux),
                Sha256 = string.IsNullOrEmpty(dto.Sha256) ? null : dto.Sha256,
                OriginMode = dto.Origin?.Mode,
                OriginSeed = ParseHex(dto.Origin?.Seed),
                OriginAux = ParseHex(dto.Origin?.Aux),
                OriginOffset = dto.Origin?.Offset ?? 0,
                OriginSize = dto.Origin?.Size ?? 0,
                HasOrigin = dto.Origin is not null,
            });
        }

        var added = 0;
        foreach (var rel in DiscoverNewFiles(workDir, known))
        {
            var name = RestoreName(rel);
            entries.Add(new BuildEntry { Name = name, File = rel, Mode = "raw", IsNew = true });
            added++;
            log?.Invoke($"New entry: {rel} → {name}");
        }

        // ── 2. 过滤缺失文件、检查 mode/seed 组合 ────────────────────────
        var missing = 0;
        var present = new List<BuildEntry>(entries.Count);
        foreach (var e in entries)
        {
            ct.ThrowIfCancellationRequested();

            var path = Path.Combine(workDir, e.File.Replace('/', Path.DirectorySeparatorChar));
            if (!File.Exists(path))
            {
                missing++;
                continue;
            }

            e.Path = path;
            if (e.Mode is not ("raw" or "seeded" or "mm" or "mmraw"))
            {
                throw new InvalidDataException($"Unknown mode {e.Mode}: {e.Name}");
            }

            if (e.Mode != "seeded" && e.Seed != 0)
            {
                log?.Invoke($"Warning: {e.Name} has mode={e.Mode} but seed!=0; the client will decrypt this block using the seed");
            }
            else if (e.Mode == "seeded" && e.Seed == 0)
            {
                log?.Invoke($"Warning: {e.Name} has mode=seeded but seed=0 (treated as plaintext, aux is ignored)");
            }

            present.Add(e);
        }

        if (missing > 0)
        {
            log?.Invoke($"{missing} entries point to missing files and were skipped (manifest unchanged)");
        }

        // 客户端二分查找要求按小写字典序；顺序同时保证 .idd 块偏移单调递增。
        // 必须用稳定排序：EmoTE 里有 Icon/ItemIDDeco_C053 与 Icon/ItemIDdeco_C053 这种
        // 只差一个字母大小写的同名资源，小写后排序键相等，谁在前由原始（manifest）顺序决定，
        // 和 python 的 sorted(key=name.lower()) 一致。
        present = [.. present.OrderBy(e => e.SortKey, StringComparer.Ordinal)];

        // ── 3. 输出位置（先清理上次的临时文件） ──────────────────────────
        var iddPath = outputBase + ".idd";
        var idxPath = outputBase + ".idx";
        var iddTmp = iddPath + ".tmp";
        var idxTmp = idxPath + ".tmp";

        Directory.CreateDirectory(Path.GetDirectoryName(outputBase)!);
        TryDelete(iddTmp);
        TryDelete(idxTmp);

        // ── 4. 准备块复用源 ──────────────────────────────────────────────
        // 优先复用上一次的产物（就在启动器目录里，机器无关）；没有历史产物时
        // 才去用 manifest 记的原始 .idd（那是解包机器上的路径，用户机上通常不存在）。
        var reuse = ReuseSource.OpenPrevious(iddPath, idxPath, log)
                    ?? ReuseSource.OpenOriginal(manifest.Source?.Idd, manifest.Source?.IddSize, present, log);

        var reused = 0;
        var rebuilt = 0;
        var lastBucket = -1;
        var total = present.Count;

        try
        {
            // ── 5. 写 .idd（先写 .tmp，成功后再替换） ────────────────────
            using (var outFs = new FileStream(iddTmp, FileMode.Create, FileAccess.Write, FileShare.None,
                       1 << 20, FileOptions.SequentialScan))
            {
                for (var i = 0; i < total; i++)
                {
                    ct.ThrowIfCancellationRequested();

                    var e = present[i];
                    var data = File.ReadAllBytes(e.Path);

                    byte[] block;
                    if (reuse is not null && reuse.TryTake(e, data, out var cached))
                    {
                        block = cached;
                        reused++;
                    }
                    else
                    {
                        block = Encode(e, data);
                        // 重建的加密码块 aux 必须是新明文的 CRC，否则真端校验不过
                        if (e.Mode == "seeded" && e.Seed != 0)
                        {
                            e.Aux = IddCodec.Crc32Legacy(e.Seed, data);
                        }

                        rebuilt++;
                    }

                    e.Offset = (uint)outFs.Position;
                    e.Size = (uint)block.Length;
                    outFs.Write(block);

                    var bucket = total == 0 ? 100 : (int)((i + 1) * 100L / total);
                    if (bucket != lastBucket)
                    {
                        lastBucket = bucket;
                        progress?.Report(new SetupProgress($"Packing {archiveName} ({i + 1}/{total})", bucket));
                    }
                }
            }

            // ── 6. 写 .idx ───────────────────────────────────────────────
            var idxBytes = IddCodec.BuildIdx(
                present.Select(e => new IdxEntry(e.Name, e.Offset, e.Size, e.Seed, e.Aux)), key, v0, v1);
            File.WriteAllBytes(idxTmp, idxBytes);

            // ── 7. 回读校验（idx 结构 + 每个块的尺寸/CRC） ────────────────
            Verify(iddTmp, idxBytes, present, v0, v1, log);
        }
        catch
        {
            reuse?.Dispose();
            TryDelete(iddTmp);
            TryDelete(idxTmp);
            throw;
        }

        // 复用源可能正开着旧的 .idd，必须先关掉才能替换
        reuse?.Dispose();
        File.Move(iddTmp, iddPath, overwrite: true);
        File.Move(idxTmp, idxPath, overwrite: true);

        progress?.Report(new SetupProgress($"Packing {archiveName} done", 100));

        return new Result
        {
            IddPath = iddPath,
            IdxPath = idxPath,
            IddSize = new FileInfo(iddPath).Length,
            IdxSize = new FileInfo(idxPath).Length,
            Key = key,
            Entries = present.Count,
            Reused = reused,
            Rebuilt = rebuilt,
            Added = added,
            Missing = missing,
            Elapsed = sw.Elapsed,
        };
    }

    /// <summary>
    /// 源目录指纹：所有文件（相对路径 + 大小 + 修改时间）滚动哈希。
    /// 用来判断「上次打包之后源目录有没有变过」，没变就跳过整个打包。
    /// </summary>
    public static string ComputeStamp(string workDir)
    {
        var lines = new List<string>(4096);
        foreach (var path in Directory.EnumerateFiles(workDir, "*", SearchOption.AllDirectories))
        {
            var rel = Path.GetRelativePath(workDir, path).Replace('\\', '/');
            if (rel.EndsWith(".idd", StringComparison.OrdinalIgnoreCase) ||
                rel.EndsWith(".idx", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            var info = new FileInfo(path);
            lines.Add($"{rel.ToLowerInvariant()}|{info.Length}|{info.LastWriteTimeUtc.Ticks}");
        }

        lines.Sort(StringComparer.Ordinal);

        ulong hash = 14695981039346656037UL; // FNV-1a 64
        foreach (var line in lines)
        {
            foreach (var ch in line)
            {
                hash = (hash ^ ch) * 1099511628211UL;
            }

            hash = (hash ^ '\n') * 1099511628211UL;
        }

        return $"{hash:x16}-{lines.Count}";
    }

    // ── 条目编码 ────────────────────────────────────────────────────────

    private static byte[] Encode(BuildEntry e, byte[] data) => e.Mode switch
    {
        "seeded" => IddCodec.EncryptBlock(data, e.Seed),
        "mm" => IddCodec.WrapMm(data),
        _ => data, // raw / mmraw 原样落盘
    };

    // ── 回读校验 ────────────────────────────────────────────────────────

    /// <summary>
    /// 按真端逻辑把刚写好的包读回来核对：idx 头/顺序/条目参数逐条比对，
    /// 再做块级检查（raw 比长度、seeded 解密后比长度并重算 CRC、mm 解压比长度）。
    /// raw 块没必要逐字节比 —— 那是我们亲手写进去的同一份字节，比长度足够抓错位。
    /// </summary>
    private static void Verify(string iddPath, byte[] idxBytes, List<BuildEntry> expected,
        uint v0, uint v1, Action<string>? log)
    {
        var problems = new List<string>();
        var parsed = IddCodec.ParseIdx(IddCodec.DecryptIdx(idxBytes, IddCodec.ReadIdxKey(idxBytes)));

        if (parsed.Error is not null)
        {
            problems.Add("Failed to parse idx: " + parsed.Error);
        }
        else if (parsed.V0 != v0 || parsed.V1 != v1 || parsed.Count != expected.Count)
        {
            problems.Add($"IDX header mismatch: ({parsed.V0},{parsed.V1},{parsed.Count}), expected ({v0},{v1},{expected.Count})");
        }
        else
        {
            var crcOk = 0;
            var checkedBlocks = 0;
            using var fs = new FileStream(iddPath, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 20,
                FileOptions.SequentialScan);

            for (var i = 0; i < expected.Count; i++)
            {
                var want = expected[i];
                var got = parsed.Entries[i];

                if (i > 0 && string.CompareOrdinal(parsed.Entries[i - 1].Name.ToLowerInvariant(),
                        got.Name.ToLowerInvariant()) > 0)
                {
                    problems.Add($"Wrong order at {i}: {parsed.Entries[i - 1].Name} > {got.Name}");
                    continue;
                }

                if (!string.Equals(got.Name, want.Name, StringComparison.Ordinal) ||
                    got.Offset != want.Offset || got.Size != want.Size ||
                    got.Seed != want.Seed || got.Aux != want.Aux)
                {
                    problems.Add($"IDX entry mismatch at {i}: {got.Name}");
                    continue;
                }

                var fileLength = new FileInfo(want.Path).Length;

                if (want.Mode is "raw" or "mmraw")
                {
                    if (got.Size != fileLength) problems.Add($"Block length mismatch: {got.Name}");
                    continue;
                }

                var raw = new byte[got.Size];
                fs.Position = got.Offset;
                if (fs.Read(raw, 0, raw.Length) != raw.Length)
                {
                    problems.Add($"Short read at {i}: {got.Name}");
                    continue;
                }

                checkedBlocks++;

                if (want.Mode == "seeded")
                {
                    var plain = IddCodec.DecryptBlock(raw, got.Seed);
                    if (plain.Length != fileLength)
                    {
                        problems.Add($"Decrypted length mismatch: {got.Name}");
                    }
                    else if (got.Seed != 0 && got.Aux != 0)
                    {
                        if (IddCodec.Crc32Legacy(got.Seed, plain) == got.Aux) crcOk++;
                        else problems.Add($"CRC mismatch: {got.Name}");
                    }
                }
                else
                {
                    try
                    {
                        if (IddCodec.UnwrapMm(raw).Length != fileLength)
                        {
                            problems.Add($"Uncompressed length mismatch: {got.Name}");
                        }
                    }
                    catch (Exception ex)
                    {
                        problems.Add($"zlib decompression failed: {got.Name} ({ex.Message})");
                    }
                }
            }

            log?.Invoke($"Verification passed: {expected.Count} entries, {checkedBlocks} blocks checked (CRC {crcOk})");
        }

        if (problems.Count == 0) return;

        foreach (var p in problems.Take(10)) log?.Invoke("Verification failed: " + p);
        throw new InvalidDataException($"Packing verification failed ({problems.Count} issues)");
    }

    // ── 目录扫描 / 命名 ────────────────────────────────────────────────

    private static List<string> DiscoverNewFiles(string workDir, HashSet<string> known)
    {
        var found = new List<string>();
        foreach (var path in Directory.EnumerateFiles(workDir, "*", SearchOption.AllDirectories))
        {
            var rel = Path.GetRelativePath(workDir, path).Replace('\\', '/');
            if (string.Equals(rel, ManifestName, StringComparison.OrdinalIgnoreCase)) continue;
            if (rel.EndsWith(".idd", StringComparison.OrdinalIgnoreCase) ||
                rel.EndsWith(".idx", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            if (!known.Contains(rel)) found.Add(rel);
        }

        found.Sort(StringComparer.Ordinal);
        return found;
    }

    /// <summary>新增文件反推资源名（manifest 里已有的条目一律以 manifest 的 name 为准）。</summary>
    private static string RestoreName(string rel)
    {
        if (rel.EndsWith(".bin", StringComparison.OrdinalIgnoreCase)) rel = rel[..^4];

        var parts = rel.Split('/');
        for (var i = 0; i < parts.Length; i++)
        {
            if (!parts[i].StartsWith('~')) continue;
            try
            {
                parts[i] = Encoding.UTF8.GetString(Convert.FromHexString(parts[i][1..]));
            }
            catch
            {
                // 不是转义段就原样保留
            }
        }

        return string.Join('/', parts);
    }

    private static string Normalize(string? rel)
        => string.IsNullOrEmpty(rel) ? "" : rel.Replace('\\', '/').TrimStart('/');

    private static uint ParseHex(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return 0;
        var s = value.Trim();
        if (s.StartsWith("0x", StringComparison.OrdinalIgnoreCase)) s = s[2..];
        return uint.TryParse(s, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var v) ? v : 0;
    }

    private static uint ParseHex(JsonElement? value)
    {
        if (value is not { } e) return 0;
        return e.ValueKind switch
        {
            JsonValueKind.Number => e.TryGetUInt32(out var n) ? n : 0,
            JsonValueKind.String => ParseHex(e.GetString()),
            _ => 0,
        };
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch
        {
            // 清理是尽力而为
        }
    }

    // ── 内部类型 ────────────────────────────────────────────────────────

    private sealed class BuildEntry
    {
        public string Name = "";
        public string File = "";
        public string Path = "";
        public string Mode = "raw";
        public uint Seed;
        public uint Aux;
        public string? Sha256;
        public bool IsNew;
        public string? OriginMode;
        public uint OriginSeed;
        public uint OriginAux;
        public long OriginOffset;
        public long OriginSize;
        public bool HasOrigin;

        public uint Offset;
        public uint Size;

        private string? _sortKey;

        /// <summary>排序键：小写化只算一次，排序比较用序数比较（等价于 python 的 name.lower()）。</summary>
        public string SortKey => _sortKey ??= Name.ToLowerInvariant();

        /// <summary>
        /// 与 manifest 记录的提取状态完全一致（内容 sha256、mode、seed、aux 都没动过）。
        /// 只有这种条目才能直接拿旧块字节复用 —— 和 idd_build.py 的 unchanged 判断一致。
        /// </summary>
        public bool IsUnchanged(byte[] data)
            => !IsNew && HasOrigin
               && Sha256 is not null
               && string.Equals(Sha256, IddCodec.Sha256Hex(data), StringComparison.OrdinalIgnoreCase)
               && string.Equals(Mode, OriginMode, StringComparison.Ordinal)
               && Seed == OriginSeed
               && Aux == OriginAux;
    }

    /// <summary>块复用源：上一次的产物，或 manifest 记的原始 .idd。</summary>
    private sealed class ReuseSource : IDisposable
    {
        private FileStream? _fs;
        private long _length;
        // 必须大小写敏感：Icon/ItemIDDeco_C053 与 Icon/ItemIDdeco_C053 是两个不同资源
        private readonly Dictionary<string, IdxEntry> _index = new(StringComparer.Ordinal);

        /// <summary>上一次的产物：块布局直接从它的 .idx 读回来。</summary>
        public static ReuseSource? OpenPrevious(string iddPath, string idxPath, Action<string>? log)
        {
            if (!File.Exists(iddPath) || !File.Exists(idxPath)) return null;

            try
            {
                var idxRaw = File.ReadAllBytes(idxPath);
                var parsed = IddCodec.ParseIdx(IddCodec.DecryptIdx(idxRaw, IddCodec.ReadIdxKey(idxRaw)));
                if (parsed.Error is not null)
                {
                    log?.Invoke($"Could not read the previous {Path.GetFileName(idxPath)} ({parsed.Error}); ignoring the old output");
                    return null;
                }

                var src = new ReuseSource();
                foreach (var e in parsed.Entries) src._index[e.Name] = e;

                var fs = new FileStream(iddPath, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 20,
                    FileOptions.SequentialScan);
                src._fs = fs;
                src._length = fs.Length;
                return src;
            }
            catch (Exception ex)
            {
                log?.Invoke($"Failed to read the previous output ({ex.Message}); rebuilding everything");
                return null;
            }
        }

        /// <summary>
        /// 原始 .idd（manifest 里 source.idd，解包机器上的路径）：布局用 origin 偏移。
        /// 文件大小必须与 manifest 记的 idd_size 一致，否则偏移全错（原包被重新打包过），
        /// 这时宁可整包重建 —— 与 idd_build.py 的判断一致。
        /// </summary>
        public static ReuseSource? OpenOriginal(string? iddPath, long? expectedSize, List<BuildEntry> entries,
            Action<string>? log)
        {
            if (string.IsNullOrEmpty(iddPath) || !File.Exists(iddPath)) return null;

            try
            {
                var actualSize = new FileInfo(iddPath).Length;
                if (expectedSize is > 0 && actualSize != expectedSize)
                {
                    log?.Invoke($"The original archive size changed ({actualSize} != {expectedSize}); skipping block reuse and rebuilding everything");
                    return null;
                }

                var src = new ReuseSource();
                foreach (var e in entries)
                {
                    if (!e.HasOrigin || e.OriginSize <= 0) continue;
                    src._index[e.Name] =
                        new IdxEntry(e.Name, (uint)e.OriginOffset, (uint)e.OriginSize, e.OriginSeed, e.OriginAux);
                }

                var fs = new FileStream(iddPath, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 20,
                    FileOptions.SequentialScan);
                src._fs = fs;
                src._length = fs.Length;
                log?.Invoke($"Reusing original archive: {iddPath}");
                return src;
            }
            catch (Exception ex)
            {
                log?.Invoke($"Failed to open the original archive ({ex.Message}); rebuilding everything");
                return null;
            }
        }

        /// <summary>能复用就吐出一份块字节；否则返回 false，由调用方重新编码。</summary>
        public bool TryTake(BuildEntry e, byte[] data, out byte[] block)
        {
            block = [];
            if (_fs is null) return false;
            if (!e.IsUnchanged(data)) return false;
            if (!_index.TryGetValue(e.Name, out var prev)) return false;
            if (prev.Seed != e.Seed || prev.Aux != e.Aux) return false;
            if (prev.Size == 0 || prev.Offset + (long)prev.Size > _length) return false;

            var buf = new byte[prev.Size];
            _fs.Position = prev.Offset;
            var read = 0;
            while (read < buf.Length)
            {
                var n = _fs.Read(buf, read, buf.Length - read);
                if (n <= 0) return false;
                read += n;
            }

            block = buf;
            return true;
        }

        public void Dispose()
        {
            _fs?.Dispose();
            _fs = null;
            _index.Clear();
        }
    }

    // ── manifest DTO ───────────────────────────────────────────────────

    private sealed class IddManifestDto
    {
        [JsonPropertyName("schema")] public string? Schema { get; set; }
        [JsonPropertyName("archive")] public string? Archive { get; set; }
        [JsonPropertyName("source")] public IddSourceDto? Source { get; set; }
        [JsonPropertyName("keep_compressed")] public bool KeepCompressed { get; set; }
        [JsonPropertyName("entries")] public List<IddEntryDto> Entries { get; set; } = [];
    }

    private sealed class IddSourceDto
    {
        [JsonPropertyName("idx")] public string? Idx { get; set; }
        [JsonPropertyName("idd")] public string? Idd { get; set; }
        [JsonPropertyName("key")] public string? Key { get; set; }
        [JsonPropertyName("header")] public List<uint>? Header { get; set; }
        [JsonPropertyName("count")] public int Count { get; set; }
        [JsonPropertyName("idd_size")] public long? IddSize { get; set; }
    }

    private sealed class IddEntryDto
    {
        [JsonPropertyName("index")] public int Index { get; set; }
        [JsonPropertyName("name")] public string? Name { get; set; }
        [JsonPropertyName("file")] public string? File { get; set; }
        [JsonPropertyName("mode")] public string? Mode { get; set; }
        [JsonPropertyName("offset")] public long Offset { get; set; }
        [JsonPropertyName("size")] public long Size { get; set; }
        [JsonPropertyName("seed")] public JsonElement? Seed { get; set; }
        [JsonPropertyName("aux")] public JsonElement? Aux { get; set; }
        [JsonPropertyName("sha256")] public string? Sha256 { get; set; }
        [JsonPropertyName("origin")] public IddOriginDto? Origin { get; set; }
    }

    private sealed class IddOriginDto
    {
        [JsonPropertyName("offset")] public long Offset { get; set; }
        [JsonPropertyName("size")] public long Size { get; set; }
        [JsonPropertyName("seed")] public JsonElement? Seed { get; set; }
        [JsonPropertyName("aux")] public JsonElement? Aux { get; set; }
        [JsonPropertyName("mode")] public string? Mode { get; set; }
    }
}
