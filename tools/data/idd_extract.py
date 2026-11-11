# -*- coding: utf-8 -*-
"""
IDX/IDD 解包到目录（2010 原版 CA.exe / 古早真端逻辑，国服最新客户端同样适用）。

    python tools/idd_extract.py --idx FxMap.idx --idd FxMap.idd --out work/FxMap
    python tools/idd_extract.py --idx Fx.idx --idd Fx.idd --out work/Fx --keep-compressed

产物：目录内按资源名建子目录（如 Map/Aqua01.bin），外加 _idd_manifest.json。
manifest 是可编辑的 IDX 源：name、seed、aux、mode 可改，idd_build.py 会按大小写不敏感字典序重建
（客户端 sub_767490 用 _mbsicmp 对 .idx 做二分查找，顺序必须按 name.lower() 排列）。

格式依据（reference/CA.exe.c）：
  · sub_766F30  .idx 解密：p0 = key ^ c0，p[i] = c[i] ^ p[i-1]，尾部字节与最后明文 dword 异或。
    明文头 [u32 0][u32 15][u32 count]，条目从 +12 起：
    [u32 nameLen][name][u32 offset][u32 size][u32 seed][u32 auxiliary]
    因 p0 必须为 0，密文首 dword 恒等于 key —— 默认直接从 idx 头取。
  · sub_766FC0  seed != 0 的数据块解密：位置轮密钥 + 明文 CBC，常量 0x9C6C95CE。
  · sub_75D440  auxiliary = CRC32(init=seed, 明文)（MSB-first poly 0x04C11DB7，无反射/无最终异或）。
  · sub_768310  名字含 "_mm" 的块是 zlib 容器 [u32 压缩长][u32 解压长][zlib 流]，
    默认解出明文；--keep-compressed 保留原容器（便于逐字节无损 round-trip）。

本文件同时是 idd_build.py 的编解码库（encrypt_* / build_idx 都在这里）。
"""
import argparse
import hashlib
import json
import os
import struct
import sys

MAGIC_XOR = 0x9C6C95CE
MANIFEST_NAME = '_idd_manifest.json'
SCHEMA = 'bnbemu/idd-workspace@1'
IDX_VERSION = 15

_CRC_TABLE = []
for _i in range(256):
    _c = _i << 24
    for _ in range(8):
        _c = ((_c << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if _c & 0x80000000 else (_c << 1) & 0xFFFFFFFF
    _CRC_TABLE.append(_c)


def u32(buf, off):
    return struct.unpack_from('<I', buf, off)[0]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def crc32_legacy(init, data):
    """sub_75D440 / CCRC32::UpdateCRC：auxiliary 校验值。"""
    c = init & 0xFFFFFFFF
    for b in data:
        c = (_CRC_TABLE[((c >> 24) ^ b) & 0xFF] ^ (c << 8)) & 0xFFFFFFFF
    return c


def _tail(src, out, n_dwords, last_plain):
    """尾部不足 4B 的字节与最后一个【明文】dword 异或（加解密共用）。"""
    for j in range(len(src) - n_dwords * 4):
        out[n_dwords * 4 + j] = src[n_dwords * 4 + j] ^ ((last_plain >> (8 * j)) & 0xFF)


def decrypt_idx(data, key):
    n = len(data) // 4
    out = bytearray(len(data))
    prev_plain = 0
    for i in range(n):
        c = u32(data, i * 4)
        p = (key ^ c) if i == 0 else (prev_plain ^ c)
        struct.pack_into('<I', out, i * 4, p & 0xFFFFFFFF)
        prev_plain = p
    _tail(data, out, n, prev_plain)
    return bytes(out)


def encrypt_idx(plain, key):
    n = len(plain) // 4
    out = bytearray(len(plain))
    prev_plain = 0
    for i in range(n):
        p = u32(plain, i * 4)
        c = (key ^ p) if i == 0 else (prev_plain ^ p)
        struct.pack_into('<I', out, i * 4, c & 0xFFFFFFFF)
        prev_plain = p
    _tail(plain, out, n, prev_plain)
    return bytes(out)


def _round_key(i):
    esi = (i + 0x3C6) & 0xFFFFFFFF
    return (((esi + 0x41E) & 0xFFFFFFFF) * esi * i * i) & 0xFFFFFFFF


def decrypt_block(data, seed):
    n = len(data) // 4
    out = bytearray(len(data))
    prev_plain = 0
    for i in range(n):
        c = u32(data, i * 4)
        p = (c ^ seed ^ MAGIC_XOR) if i == 0 else (c ^ _round_key(i) ^ prev_plain ^ MAGIC_XOR)
        p &= 0xFFFFFFFF
        struct.pack_into('<I', out, i * 4, p)
        prev_plain = p
    _tail(data, out, n, prev_plain)
    return bytes(out)


def encrypt_block(plain, seed):
    """decrypt_block 的逆（客户端真端无加密函数，这里按解密式反解）。"""
    n = len(plain) // 4
    out = bytearray(len(plain))
    prev_plain = 0
    for i in range(n):
        p = u32(plain, i * 4)
        c = (p ^ seed ^ MAGIC_XOR) if i == 0 else (p ^ _round_key(i) ^ prev_plain ^ MAGIC_XOR)
        c &= 0xFFFFFFFF
        struct.pack_into('<I', out, i * 4, c)
        prev_plain = p
    _tail(plain, out, n, prev_plain)
    return bytes(out)


def parse_idx(dec):
    """返回 (v0, v1, count, entries, error)；entry = (name, offset, size, seed, aux)。"""
    if len(dec) < 12:
        return 0, 0, 0, [], 'idx too short'
    v0, v1, count = u32(dec, 0), u32(dec, 4), u32(dec, 8)
    at, entries = 12, []
    for i in range(count):
        if at + 4 > len(dec):
            return v0, v1, count, entries, 'truncated@%d' % i
        nl = u32(dec, at)
        at += 4
        if nl == 0 or nl >= 0x80 or at + nl + 16 > len(dec):
            return v0, v1, count, entries, 'bad-name-len@%d(%d)' % (i, nl)
        name = dec[at:at + nl].decode('latin1')
        at += nl
        off, size, seed, aux = struct.unpack_from('<4I', dec, at)
        at += 16
        entries.append((name, off, size, seed, aux))
    return v0, v1, count, entries, None


def build_idx(entries, key, v0=0, v1=IDX_VERSION):
    """按大小写不敏感字典序（与客户端 sub_767490 的 _mbsicmp 二分查找一致）构造并加密 .idx。"""
    ordered = sorted(entries, key=lambda e: e['name'].lower())
    plain = bytearray(struct.pack('<3I', v0, v1, len(ordered)))
    for e in ordered:
        name = e['name'].encode('latin1')
        plain += struct.pack('<I', len(name)) + name
        plain += struct.pack('<4I', e['offset'], e['size'], e['seed'], e['aux'])
    return encrypt_idx(bytes(plain), key)


def unwrap_mm(data):
    """解开 [u32 压缩长][u32 解压长][zlib 流]，返回明文。"""
    src_len, dst_len = u32(data, 0), u32(data, 4)
    import zlib
    out = zlib.decompress(data[8:8 + src_len])
    if len(out) != dst_len:
        raise ValueError('zlib size mismatch %d != %d' % (len(out), dst_len))
    return out


def wrap_mm(plain):
    """重新压缩成 _mm 容器；客户端用 zlib uncompress，任意合法流均可。"""
    import zlib
    comp = zlib.compress(plain, 9)
    return struct.pack('<2I', len(comp), len(plain)) + comp


# ---- Windows 文件名安全编码：只有异常段才转义，绝大多数保持可读 ----
_WIN_BAD = set('<>:"/\\|?*') | {chr(i) for i in range(32)}
_WIN_RESERVED = {'CON', 'PRN', 'AUX', 'NUL'} | {'COM%d' % i for i in range(1, 10)} \
                | {'LPT%d' % i for i in range(1, 10)}


def _safe_segment(s):
    if (s and not s.endswith((' ', '.')) and s.split('.')[0].upper() not in _WIN_RESERVED
            and all(ch not in _WIN_BAD for ch in s)):
        return s
    return '~' + s.encode('utf-8').hex()


def safe_relpath(name, index, used):
    rel = '/'.join(_safe_segment(p) for p in name.split('/')) + '.bin'
    if rel.casefold() in used:            # Windows 大小写不敏感，防覆盖
        rel = rel[:-4] + '__%d.bin' % index
    used.add(rel.casefold())
    return rel.replace('/', os.sep)


def restore_name(rel):
    """新增文件反推资源名（manifest 里已有的条目一律以 manifest 的 name 为准）。"""
    rel = rel.replace('\\', '/')
    if rel.endswith('.bin'):
        rel = rel[:-4]
    parts = []
    for p in rel.split('/'):
        if p.startswith('~'):
            try:
                parts.append(bytes.fromhex(p[1:]).decode('utf-8'))
            except ValueError:
                parts.append(p)
        else:
            parts.append(p)
    return '/'.join(parts)


def entry_mode(entry, keep_compressed):
    _name, _off, _size, seed, _aux = entry
    if seed:
        return 'seeded'
    if '_mm' in _name:
        return 'mmraw' if keep_compressed else 'mm'
    return 'raw'


def read_entry_bytes(fh, entry, keep_compressed):
    """按真端逻辑读出一条资源，返回 (mode, 写到目录的字节)。"""
    name, off, size, seed, _aux = entry
    fh.seek(off)
    raw = fh.read(size)
    if len(raw) != size:
        raise IOError('%s: short read %d/%d' % (name, len(raw), size))
    mode = entry_mode(entry, keep_compressed)
    if mode == 'seeded':
        return mode, decrypt_block(raw, seed)
    if mode == 'mm':
        return mode, unwrap_mm(raw)
    return mode, raw                      # raw / mmraw 原样落盘


def write_entry_bytes(entry, data):
    """重打包时把目录里的文件还原成 idd 里的块字节。"""
    mode = entry['mode']
    if mode == 'seeded':
        return encrypt_block(data, entry['seed'])
    if mode == 'mm':
        return wrap_mm(data)
    return data                          # raw / mmraw


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--idx', required=True, help='输入 .idx')
    ap.add_argument('--idd', required=True, help='输入 .idd')
    ap.add_argument('--out', required=True, help='输出目录')
    ap.add_argument('--key', default=None, help='覆盖 .idx 解密 key（默认取 idx 首 dword），如 0x719CAB30')
    ap.add_argument('--keep-compressed', action='store_true',
                    help='_mm 块保留 zlib 容器原文（默认解成明文，方便修改）')
    ap.add_argument('--verify', action='store_true', help='解包时逐块校验 CRC / zlib 长度')
    ap.add_argument('--force', action='store_true', help='允许覆盖已有工作目录里的同名文件')
    args = ap.parse_args()

    idx_path, idd_path = os.path.abspath(args.idx), os.path.abspath(args.idd)
    idx_raw = open(idx_path, 'rb').read()
    key = int(args.key, 0) if args.key else u32(idx_raw, 0)
    dec = decrypt_idx(idx_raw, key)
    v0, v1, count, entries, err = parse_idx(dec)
    if err or v0 != 0 or v1 != IDX_VERSION or len(entries) != count:
        sys.exit('IDX 解析失败: hdr=(%d,%d,%d) parsed=%d err=%s' % (v0, v1, count, len(entries), err))

    out = os.path.abspath(args.out)
    manifest_path = os.path.join(out, MANIFEST_NAME)
    if os.path.exists(manifest_path) and not args.force:
        sys.exit('目录已有 %s，加 --force 才覆盖' % MANIFEST_NAME)
    os.makedirs(out, exist_ok=True)

    used = set()
    manifest = {
        'schema': SCHEMA,
        'archive': os.path.splitext(os.path.basename(idx_path))[0],
        'source': {
            'idx': idx_path, 'idd': idd_path, 'key': '0x%08X' % key,
            'header': [v0, v1], 'count': count,
            'idx_sha256': sha256(idx_raw), 'idd_size': os.path.getsize(idd_path),
        },
        'keep_compressed': args.keep_compressed,
        'entries': [],
    }
    crc_ok = crc_bad = mm_ok = 0
    with open(idd_path, 'rb') as fh:
        for i, e in enumerate(entries):
            name, off, size, seed, aux = e
            mode, data = read_entry_bytes(fh, e, args.keep_compressed)
            rel = safe_relpath(name, i, used)
            path = os.path.join(out, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, 'wb') as fp:
                fp.write(data)
            if args.verify:
                if mode == 'seeded' and aux:
                    if crc32_legacy(seed, data) == aux:
                        crc_ok += 1
                    else:
                        crc_bad += 1
                        print('  CRC FAIL:', name)
                if mode == 'mm':
                    mm_ok += 1
            manifest['entries'].append({
                'index': i, 'name': name, 'file': rel.replace(os.sep, '/'), 'mode': mode,
                'offset': off, 'size': size,
                'seed': '0x%08X' % seed, 'aux': '0x%08X' % aux,
                'sha256': sha256(data),
                'origin': {'offset': off, 'size': size, 'seed': '0x%08X' % seed,
                           'aux': '0x%08X' % aux, 'mode': mode},
            })
    with open(manifest_path, 'w', encoding='utf-8') as fp:
        json.dump(manifest, fp, ensure_ascii=False, indent=2)

    print('archive=%s key=%s entries=%d -> %s' % (manifest['archive'], manifest['source']['key'], count, out))
    from collections import Counter
    print('modes:', dict(Counter(e['mode'] for e in manifest['entries'])))
    if args.verify:
        print('verify: seededCRC=%d/%d ok, fail=%d, _mm decompressed=%d'
              % (crc_ok, crc_ok + crc_bad, crc_bad, mm_ok))
    print('manifest:', manifest_path)
    return 1 if crc_bad else 0


if __name__ == '__main__':
    raise SystemExit(main())
