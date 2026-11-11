# -*- coding: utf-8 -*-
"""
从解包目录重新制作 IDX/IDD（idd_extract.py 的逆操作）。

    python tools/idd_build.py --dir work/FxMap --out work/FxMap_rebuilt
    -> 生成 work/FxMap_rebuilt.idd / work/FxMap_rebuilt.idx

目录里的 _idd_manifest.json 就是可编辑的 IDX 源：
  · 改某个 .bin 内容 -> 自动重新加密/压缩/算 aux CRC、重排 offset
  · 改 manifest 里的 name / seed / mode -> 按新值重建 idx（自动按大小写不敏感字典序排列，
    满足客户端 sub_767490 的 _mbsicmp 二分查找；原包本身也均遵循此顺序）
  · 目录里直接放新文件（如 Map/MyMap.bin）-> 自动作为明文条目追加，seed=0
  · 想让某条目加密：把该条目 seed 改成非 0 且 mode 改成 "seeded"，aux 会自动重算
  · mode: raw=明文块 / seeded=sub_766FC0 加密块 / mm=zlib 容器(目录里是明文) / mmraw=zlib 容器(目录里是原容器)

未改动的条目会直接复用原 .idd 里的密文/压缩字节；因此「原样解包→原样打包」逐字节一致。
改过的条目会重新加密并重算 auxiliary = CRC32(seed, 明文)，保证真端 sub_75D440 校验通过。
"""
import argparse
import json
import os
import sys

from idd_extract import (MANIFEST_NAME, SCHEMA, build_idx, crc32_legacy, decrypt_block,
                         decrypt_idx, parse_idx, restore_name, sha256, unwrap_mm,
                         write_entry_bytes)


def num(value, default=0):
    if value is None:
        return default
    if isinstance(value, int):
        return value & 0xFFFFFFFF
    return int(str(value), 0) & 0xFFFFFFFF


def discover_new_files(workdir, known):
    """找出 manifest 之外的新增文件，返回相对路径列表（用 / 分隔）。"""
    found = []
    for root, _dirs, files in os.walk(workdir):
        for fn in files:
            rel = os.path.relpath(os.path.join(root, fn), workdir).replace(os.sep, '/')
            if rel == MANIFEST_NAME or rel.lower().endswith(('.idd', '.idx')):
                continue
            if rel not in known:
                found.append(rel)
    return sorted(found, key=str.lower)


def verify_output(idd_path, idx_path, key, entries, workdir):
    """按真端逻辑重新读回新包，逐块与目录文件比对。"""
    dec = decrypt_idx(open(idx_path, 'rb').read(), key)
    v0, v1, count, parsed, err = parse_idx(dec)
    if err or v0 != 0 or v1 != 15 or count != len(entries):
        return ['idx header/parse: %s hdr=(%s,%s,%s) count=%s' % (err, v0, v1, count, len(parsed))]
    problems = []
    crc_ok = 0
    with open(idd_path, 'rb') as fh:
        for i, e in enumerate(parsed):
            name, off, size, seed, aux = e
            if i > 0 and parsed[i - 1][0].lower() > name.lower():
                problems.append('unsorted@%d %s > %s' % (i, parsed[i - 1][0], name))
                continue
            want = entries[i]
            if (name != want['name'] or off != want['offset'] or size != want['size']
                    or seed != want['seed'] or aux != want['aux']):
                problems.append('idx mismatch@%d %s' % (i, name))
                continue
            fh.seek(off)
            raw = fh.read(size)
            mode = want['mode']
            if mode == 'seeded':
                plain = decrypt_block(raw, seed)
                if aux and crc32_legacy(seed, plain) != aux:
                    problems.append('CRC fail %s' % name)
                else:
                    crc_ok += 1
            elif mode == 'mm':
                try:
                    plain = unwrap_mm(raw)
                except Exception as exc:
                    problems.append('zlib fail %s: %s' % (name, exc))
                    continue
            else:
                plain = raw
            src = os.path.join(workdir, want['file'].replace('/', os.sep))
            if not os.path.exists(src) or open(src, 'rb').read() != plain:
                problems.append('content mismatch %s' % name)
    print('verify: %d entries, CRC ok=%d, problems=%d' % (len(parsed), crc_ok, len(problems)))
    return problems


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--dir', required=True, help='idd_extract.py 产出的工作目录')
    ap.add_argument('--out', default=None, help='输出基名（不含扩展名），默认 <dir>_rebuilt')
    ap.add_argument('--key', default=None, help='覆盖 idx 加密 key，默认用 manifest 记录的')
    ap.add_argument('--no-verify', action='store_true', help='跳过回读校验')
    ap.add_argument('--force', action='store_true', help='允许覆盖已存在的输出 idd/idx')
    args = ap.parse_args()

    workdir = os.path.abspath(args.dir)
    manifest_path = os.path.join(workdir, MANIFEST_NAME)
    if not os.path.exists(manifest_path):
        sys.exit('缺少 %s，请先用 idd_extract.py 解包' % MANIFEST_NAME)
    manifest = json.load(open(manifest_path, encoding='utf-8'))
    if manifest.get('schema') != SCHEMA:
        sys.exit('manifest schema 不匹配: %r' % manifest.get('schema'))
    src = manifest['source']
    key = num(args.key if args.key else src['key'])

    entries = [dict(e) for e in manifest['entries']]
    known = {e['file'] for e in entries}
    for rel in discover_new_files(workdir, known):
        entries.append({
            'index': len(entries), 'name': restore_name(rel), 'file': rel, 'mode': 'raw',
            'offset': 0, 'size': 0, 'seed': '0x00000000', 'aux': '0x00000000',
            'sha256': '', 'origin': None, 'new': True,
        })
        print('new entry:', rel, '->', entries[-1]['name'])

    for e in entries:
        e['seed'] = num(e.get('seed'))
        e['aux'] = num(e.get('aux'))
        path = os.path.join(workdir, e['file'].replace('/', os.sep))
        if not os.path.exists(path):
            sys.exit('文件缺失: %s' % e['file'])
        e['_path'] = path
        e['_data'] = open(path, 'rb').read()
        if e['mode'] not in ('raw', 'seeded', 'mm', 'mmraw'):
            sys.exit('未知 mode %r: %s' % (e['mode'], e['name']))
        if e['mode'] != 'seeded' and e['seed']:
            print('  WARN: %s mode=%s 但 seed!=0，客户端会按 seed 解密该块' % (e['name'], e['mode']))
        if e['mode'] == 'seeded' and not e['seed']:
            print('  WARN: %s mode=seeded 但 seed=0（等同明文，aux 会被忽略）' % e['name'])

    # 客户端 sub_767490 用 _mbsicmp 对 .idx 做二分查找；写入前统一按小写字典序排序，
    # 同时保证 .idd 块偏移单调递增。
    entries.sort(key=lambda e: e['name'].lower())

    # 原 idd 用于复用未改动条目的原始密文/压缩字节（大小不一致说明原文件已被替换，不能按旧偏移复用）
    ori_path = src.get('idd', '')
    expected_ori_size = src.get('idd_size')
    ori_fh = None
    ori_size = 0
    if os.path.exists(ori_path):
        actual_size = os.path.getsize(ori_path)
        if expected_ori_size is not None and actual_size != expected_ori_size:
            print('  WARN: 原 idd 大小已变更 (%d != %d)，跳过块复用，改为按工作目录文件重建'
                  % (actual_size, expected_ori_size))
        else:
            ori_fh = open(ori_path, 'rb')
            ori_size = actual_size
    ori_valid = ori_fh is not None

    out_base = os.path.abspath(args.out) if args.out else workdir + '_rebuilt'
    idd_out, idx_out = out_base + '.idd', out_base + '.idx'
    os.makedirs(os.path.dirname(out_base), exist_ok=True)
    if not args.force and (os.path.exists(idd_out) or os.path.exists(idx_out)):
        sys.exit('输出已存在，加 --force 覆盖: %s / %s' % (idd_out, idx_out))

    reused = rebuilt = 0
    with open(idd_out, 'wb') as out_fh:
        for e in entries:
            data, origin = e['_data'], e.get('origin') or {}
            unchanged = (sha256(data) == e.get('sha256')
                         and e['mode'] == origin.get('mode')
                         and e['seed'] == num(origin.get('seed'), -1)
                         and e['aux'] == num(origin.get('aux'), -1))
            off0, size0 = origin.get('offset'), origin.get('size')
            can_reuse = (unchanged and ori_fh is not None and isinstance(off0, int)
                         and isinstance(size0, int) and 0 <= off0 and off0 + size0 <= ori_size)
            if can_reuse:
                ori_fh.seek(off0)
                block = ori_fh.read(size0)
                reused += 1
            else:
                block = write_entry_bytes(e, data)
                if e['mode'] == 'seeded' and e['seed']:
                    e['aux'] = crc32_legacy(e['seed'], data)
                rebuilt += 1
            e['offset'] = out_fh.tell()
            e['size'] = len(block)
            out_fh.write(block)
    if ori_fh:
        ori_fh.close()

    idx_bytes = build_idx(entries, key, *(src.get('header') or [0, 15]))
    with open(idx_out, 'wb') as fp:
        fp.write(idx_bytes)

    print('built: %s (%d B) / %s (%d B)' %
          (idd_out, os.path.getsize(idd_out), idx_out, os.path.getsize(idx_out)))
    print('entries=%d reused=%d rebuilt=%d key=0x%08X' % (len(entries), reused, rebuilt, key))

    problems = [] if args.no_verify else verify_output(idd_out, idx_out, key, entries, workdir)
    for p in problems[:10]:
        print('  ERROR:', p)
    if problems:
        sys.exit('VERIFY FAILED (%d)' % len(problems))

    # 完全未改动时，回包应与原文件逐字节一致
    if (rebuilt == 0 and not any(e.get('new') for e in entries)
            and ori_valid and os.path.exists(src.get('idx', ''))):
        same_idx = open(src['idx'], 'rb').read() == open(idx_out, 'rb').read()
        same_idd = (ori_size == os.path.getsize(idd_out)
                    and open(ori_path, 'rb').read() == open(idd_out, 'rb').read())
        print('round-trip: idx byte-identical=%s, idd byte-identical=%s' % (same_idx, same_idd))
    print('RESULT: OK')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
