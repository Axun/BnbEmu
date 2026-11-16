using System.Buffers.Binary;
using System.IO;
using System.IO.Compression;
using System.Text;

namespace BnbLauncher.Services;

/// <summary>.idx 里的一条记录：资源名 + 数据块位置与校验参数。</summary>
public readonly record struct IdxEntry(string Name, uint Offset, uint Size, uint Seed, uint Aux);

/// <summary>解密后的 .idx 内容。</summary>
public sealed class IdxContents
{
    public uint V0 { get; init; }
    public uint V1 { get; init; }
    public int Count { get; init; }
    public List<IdxEntry> Entries { get; init; } = [];
    public string? Error { get; init; }
}

/// <summary>
/// 泡泡堂客户端资源包（.idd / .idx）的编解码，C# 移植自 <c>tools/data/idd_extract.py</c>，
/// 格式依据 <c>reference/CA.exe.c</c> 的反编译结果：
///
/// <list type="bullet">
/// <item>sub_766F30（.idx 解密）：p0 = key ^ c0，p[i] = c[i] ^ p[i-1]，
///   尾部不足 4 字节的字节与最后一个明文 dword 异或。明文头是
///   <c>[u32 0][u32 15][u32 count]</c>，条目从 +12 起：
///   <c>[u32 nameLen][name][u32 offset][u32 size][u32 seed][u32 auxiliary]</c>。
///   因 p0 必须为 0，密文首 dword 恒等于 key，所以 key 可以直接从 idx 头读出来。</item>
/// <item>sub_766FC0（seed != 0 的数据块解密）：位置轮密钥 + 明文 CBC，常量 0x9C6C95CE。</item>
/// <item>sub_75D440（auxiliary）：CRC32(init=seed, 明文)，MSB-first poly 0x04C11DB7、无反射、无最终异或。</item>
/// <item>sub_768310（名字含 "_mm" 的块）：zlib 容器 <c>[u32 压缩长][u32 解压长][zlib 流]</c>。</item>
/// </list>
///
/// 客户端 sub_767490 用 _mbsicmp 对 .idx 做二分查找，所以条目顺序必须是
/// 大小写不敏感的字典序 —— 打包时统一按 <c>name.ToLowerInvariant()</c> 排序，写入前不能忘。
/// </summary>
public static class IddCodec
{
    public const uint MagicXor = 0x9C6C95CE;
    public const uint IdxVersion = 15;

    /// <summary>明文头长度：[u32 0][u32 15][u32 count]。</summary>
    public const int IdxHeaderSize = 12;

    private const uint CrcPoly = 0x04C11DB7;

    private static readonly uint[] CrcTable = CreateCrcTable();

    private static uint[] CreateCrcTable()
    {
        var table = new uint[256];
        for (uint i = 0; i < 256; i++)
        {
            var c = i << 24;
            for (var bit = 0; bit < 8; bit++)
            {
                c = (c & 0x80000000) != 0 ? (c << 1) ^ CrcPoly : c << 1;
            }

            table[i] = c;
        }

        return table;
    }

    // ── 校验 ────────────────────────────────────────────────────────────

    /// <summary>
    /// sub_75D440 / CCRC32::UpdateCRC：auxiliary 校验值。
    /// MSB-first（不反射）、初值给 seed、没有最终异或 —— 不是常见的 zlib crc32。
    /// </summary>
    public static uint Crc32Legacy(uint init, ReadOnlySpan<byte> data)
    {
        var c = init;
        foreach (var b in data)
        {
            c = CrcTable[((c >> 24) ^ b) & 0xFF] ^ (c << 8);
        }

        return c;
    }

    // ── .idx ────────────────────────────────────────────────────────────

    public static byte[] DecryptIdx(ReadOnlySpan<byte> data, uint key)
    {
        var n = data.Length / 4;
        var plain = new byte[data.Length];
        uint prevPlain = 0;

        for (var i = 0; i < n; i++)
        {
            var c = BinaryPrimitives.ReadUInt32LittleEndian(data.Slice(i * 4, 4));
            var p = i == 0 ? key ^ c : prevPlain ^ c;
            BinaryPrimitives.WriteUInt32LittleEndian(plain.AsSpan(i * 4, 4), p);
            prevPlain = p;
        }

        XorTail(data, plain, n, prevPlain);
        return plain;
    }

    public static byte[] EncryptIdx(ReadOnlySpan<byte> plain, uint key)
    {
        var n = plain.Length / 4;
        var cipher = new byte[plain.Length];
        uint prevPlain = 0;

        for (var i = 0; i < n; i++)
        {
            var p = BinaryPrimitives.ReadUInt32LittleEndian(plain.Slice(i * 4, 4));
            var c = i == 0 ? key ^ p : prevPlain ^ p;
            BinaryPrimitives.WriteUInt32LittleEndian(cipher.AsSpan(i * 4, 4), c);
            prevPlain = p;
        }

        XorTail(plain, cipher, n, prevPlain);
        return cipher;
    }

    /// <summary>按真端逻辑解析已解密的 idx；解析失败时返回带 Error 的结果而不是抛异常。</summary>
    public static IdxContents ParseIdx(byte[] plain)
    {
        if (plain.Length < IdxHeaderSize)
        {
            return new IdxContents { Error = "idx too short" };
        }

        var v0 = BinaryPrimitives.ReadUInt32LittleEndian(plain);
        var v1 = BinaryPrimitives.ReadUInt32LittleEndian(plain.AsSpan(4));
        var count = BinaryPrimitives.ReadUInt32LittleEndian(plain.AsSpan(8));

        var at = IdxHeaderSize;
        var entries = new List<IdxEntry>((int)Math.Min(count, 100_000));

        for (var i = 0; i < count; i++)
        {
            if (at + 4 > plain.Length)
            {
                return new IdxContents { Entries = entries, Error = $"truncated@{i}" };
            }

            var nameLen = BinaryPrimitives.ReadUInt32LittleEndian(plain.AsSpan(at));
            at += 4;
            if (nameLen == 0 || nameLen >= 0x80 || at + nameLen + 16 > plain.Length)
            {
                return new IdxContents { Entries = entries, Error = $"bad-name-len@{i}({nameLen})" };
            }

            var name = Encoding.Latin1.GetString(plain, at, (int)nameLen);
            at += (int)nameLen;
            var offset = BinaryPrimitives.ReadUInt32LittleEndian(plain.AsSpan(at));
            var size = BinaryPrimitives.ReadUInt32LittleEndian(plain.AsSpan(at + 4));
            var seed = BinaryPrimitives.ReadUInt32LittleEndian(plain.AsSpan(at + 8));
            var aux = BinaryPrimitives.ReadUInt32LittleEndian(plain.AsSpan(at + 12));
            at += 16;

            entries.Add(new IdxEntry(name, offset, size, seed, aux));
        }

        return new IdxContents { V0 = v0, V1 = v1, Count = (int)count, Entries = entries };
    }

    /// <summary>
    /// 构造并加密 .idx。排序与客户端 sub_767490 的 _mbsicmp 二分查找保持一致：
    /// 按 name 的小写形式做序数比较（latin1 资源名等价于按码点比较，与 python 版结果相同）。
    /// </summary>
    public static byte[] BuildIdx(IEnumerable<IdxEntry> entries, uint key, uint v0 = 0,
        uint v1 = IdxVersion)
    {
        var ordered = entries
            .OrderBy(e => e.Name.ToLowerInvariant(), StringComparer.Ordinal)
            .ToList();

        using var ms = new MemoryStream(IdxHeaderSize + ordered.Count * 48);
        WriteU32(ms, v0);
        WriteU32(ms, v1);
        WriteU32(ms, (uint)ordered.Count);

        foreach (var e in ordered)
        {
            var name = Encoding.Latin1.GetBytes(e.Name);
            WriteU32(ms, (uint)name.Length);
            ms.Write(name);
            WriteU32(ms, e.Offset);
            WriteU32(ms, e.Size);
            WriteU32(ms, e.Seed);
            WriteU32(ms, e.Aux);
        }

        return EncryptIdx(ms.GetBuffer().AsSpan(0, (int)ms.Length), key);
    }

    private static void WriteU32(Stream stream, uint value)
    {
        Span<byte> buf = stackalloc byte[4];
        BinaryPrimitives.WriteUInt32LittleEndian(buf, value);
        stream.Write(buf);
    }

    // ── 数据块 ──────────────────────────────────────────────────────────

    /// <summary>sub_766FC0：seed != 0 的块解密。第 0 个 dword 用 seed，其余用位置轮密钥。</summary>
    public static byte[] DecryptBlock(ReadOnlySpan<byte> data, uint seed)
    {
        var n = data.Length / 4;
        var plain = new byte[data.Length];
        uint prevPlain = 0;

        for (var i = 0; i < n; i++)
        {
            var c = BinaryPrimitives.ReadUInt32LittleEndian(data.Slice(i * 4, 4));
            var p = i == 0
                ? c ^ seed ^ MagicXor
                : c ^ RoundKey(i) ^ prevPlain ^ MagicXor;
            BinaryPrimitives.WriteUInt32LittleEndian(plain.AsSpan(i * 4, 4), p);
            prevPlain = p;
        }

        XorTail(data, plain, n, prevPlain);
        return plain;
    }

    /// <summary>decrypt_block 的逆（真端只有解密，这里按解密式反解；idd_build.py 同款）。</summary>
    public static byte[] EncryptBlock(ReadOnlySpan<byte> plain, uint seed)
    {
        var n = plain.Length / 4;
        var cipher = new byte[plain.Length];
        uint prevPlain = 0;

        for (var i = 0; i < n; i++)
        {
            var p = BinaryPrimitives.ReadUInt32LittleEndian(plain.Slice(i * 4, 4));
            var c = i == 0
                ? p ^ seed ^ MagicXor
                : p ^ RoundKey(i) ^ prevPlain ^ MagicXor;
            BinaryPrimitives.WriteUInt32LittleEndian(cipher.AsSpan(i * 4, 4), c);
            prevPlain = p;
        }

        XorTail(plain, cipher, n, prevPlain);
        return cipher;
    }

    /// <summary>
    /// 位置轮密钥：<c>((i + 0x3C6) + 0x41E) * (i + 0x3C6) * i * i</c> 取低 32 位。
    /// 每个因子都是 32 位量，乘积远超 ulong，按 2^64 回绕后再掩到 32 位结果不变
    /// （2^32 整除 2^64），所以这里用 unchecked ulong 乘就够了。
    /// </summary>
    private static uint RoundKey(int i)
    {
        unchecked
        {
            var esi = (ulong)(uint)(i + 0x3C6);
            var v = (esi + 0x41E) * esi * (ulong)i * (ulong)i;
            return (uint)(v & 0xFFFFFFFF);
        }
    }

    /// <summary>尾部不足 4 字节的部分与最后一个明文 dword 异或（加解密共用）。</summary>
    private static void XorTail(ReadOnlySpan<byte> src, Span<byte> dst, int nDwords, uint lastPlain)
    {
        for (var j = 0; j < src.Length - nDwords * 4; j++)
        {
            dst[nDwords * 4 + j] = (byte)(src[nDwords * 4 + j] ^ (byte)(lastPlain >> (8 * j)));
        }
    }

    // ── _mm zlib 容器 ───────────────────────────────────────────────────

    /// <summary>解开 <c>[u32 压缩长][u32 解压长][zlib 流]</c>。</summary>
    public static byte[] UnwrapMm(ReadOnlySpan<byte> container)
    {
        if (container.Length < 8) throw new InvalidDataException("_mm container is too short");

        var srcLen = BinaryPrimitives.ReadUInt32LittleEndian(container);
        var dstLen = BinaryPrimitives.ReadUInt32LittleEndian(container[4..]);
        if (8 + (long)srcLen > container.Length)
        {
            throw new InvalidDataException($"_mm compressed length out of range ({srcLen} > {container.Length - 8})");
        }

        var plain = new byte[dstLen];
        using var input = new MemoryStream(container[8..(int)(8 + srcLen)].ToArray());
        using var zlib = new ZLibStream(input, CompressionMode.Decompress);
        var read = 0;
        while (read < plain.Length)
        {
            var n = zlib.Read(plain, read, plain.Length - read);
            if (n <= 0) break;
            read += n;
        }

        if (read != plain.Length)
        {
            throw new InvalidDataException($"zlib size mismatch {read} != {dstLen}");
        }

        return plain;
    }

    /// <summary>重新压成 _mm 容器；客户端用 zlib uncompress 解，任意合法流都可。</summary>
    public static byte[] WrapMm(ReadOnlySpan<byte> plain)
    {
        using var comp = new MemoryStream();
        // 真端只认 zlib 格式（带 2 字节头 + adler32），ZLibStream 正好是这个格式；
        // SmallestSize 对应 zlib 的 level 9，和 python 版 zlib.compress(plain, 9) 对齐。
        using (var zlib = new ZLibStream(comp, CompressionLevel.SmallestSize, leaveOpen: true))
        {
            zlib.Write(plain);
        }

        var stream = comp.ToArray();
        var result = new byte[8 + stream.Length];
        BinaryPrimitives.WriteUInt32LittleEndian(result, (uint)stream.Length);
        BinaryPrimitives.WriteUInt32LittleEndian(result.AsSpan(4), (uint)plain.Length);
        stream.CopyTo(result, 8);
        return result;
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    /// <summary>读 idx 头里的 key：密文首 dword 恒等于 key（因为 p0 必须是 0）。</summary>
    public static uint ReadIdxKey(ReadOnlySpan<byte> idxRaw)
        => idxRaw.Length < 4 ? 0u : BinaryPrimitives.ReadUInt32LittleEndian(idxRaw);

    public static string Sha256Hex(ReadOnlySpan<byte> data)
        => Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(data)).ToLowerInvariant();
}
