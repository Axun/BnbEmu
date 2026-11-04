# -*- coding: utf-8 -*-
"""
生成并验证 fxdynmapinfo 的 JSON 单一事实源。

默认流程 (无参数直接运行):
  1. 从 S→C op 210 抓包解析出 24111 B 明文，交叉核对本地 .ssd 缓存
  2. 解析成 342 条记录，写入 src/main/resources/data/fxdynmapinfo.json
  3. 只以该 JSON 的 records 为输入，重新生成明文 / .ssd 文件 / op210 包
  4. 与四份原始二进制逐字节比对，全部一致才返回 0
  5. 重建产物写到 cap/rebuilt/ 供人工复核

用法:
  python tools/build_fxdynmapinfo.py                    # 生成 + 验证
  python tools/build_fxdynmapinfo.py --verify-only     # 只验证现有 JSON
  python tools/build_fxdynmapinfo.py --from ssd        # 改从 .ssd 文件解析
  python tools/build_fxdynmapinfo.py --no-emit         # 不写重建产物
"""
from __future__ import annotations

import argparse
import hashlib
import os
import struct
import sys

from fxdynmapinfo_codec import (
    CodecError, OPCODE, SOURCE_SCHEMA, build_packet, build_ssd,
    derive_map_ids, dump_source, load_source, parse_packet, parse_plain, parse_ssd,
    serialize_plain,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SOURCE = os.path.join(ROOT, "src/main/resources/data/fxdynmapinfo.json")
DEFAULT_PACKET = os.path.join(ROOT, "cap/chan_s2c_op210_24123B_0.bin")
DEFAULT_PRACTICE = os.path.join(ROOT, "cap/practice_s2c_op210_24121B_0.bin")
DEFAULT_PLAIN = os.path.join(ROOT, "cap/fxdynmapinfo_plain.bin")
DEFAULT_SSD = "F:/Games/PopTag/fxdynmapinfo.ssd"
DEFAULT_EMIT = os.path.join(ROOT, "cap/rebuilt")

KNOWN_MAP_IDS = {
    0: "HotRandom",
    66: "CampRandom",
    145: "Patrit14",
    172: "Cemetery",
    175: "Cemetery03",
    176: "Cemetery04",
}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def rel(path: str) -> str:
    try:
        return os.path.relpath(path, ROOT).replace("\\", "/")
    except ValueError:
        return path


def need(path: str) -> bytes:
    if not os.path.isfile(path):
        raise CodecError(f"基准文件不存在: {path}")
    with open(path, "rb") as f:
        return f.read()


class Report:
    def __init__(self):
        self.ok = True

    def check(self, label: str, actual: bytes, expected: bytes, source: str) -> bool:
        same = actual == expected
        if not same and len(actual) == len(expected):
            diff = [i for i, (a, b) in enumerate(zip(actual, expected)) if a != b]
            detail = f"首个差异 @{diff[0]:#x}, 共 {len(diff)} 字节" if diff else ""
        else:
            detail = f"长度 {len(actual)} vs {len(expected)}"
        print(f"  [{'PASS' if same else 'FAIL'}] {label}")
        print(f"         {rel(source)}  期望 {len(expected):6d} B sha256={sha(expected)[:16]}")
        print(f"         {'重建':<10} 实得 {len(actual):6d} B sha256={sha(actual)[:16]}"
              + ("" if same else "  " + detail))
        if not same:
            self.ok = False
        return same

    def assert_true(self, label: str, cond: bool, detail: str = "") -> bool:
        print(f"  [{'PASS' if cond else 'FAIL'}] {label}" + (f"  {detail}" if detail else ""))
        if not cond:
            self.ok = False
        return cond


def generate(args) -> tuple[bytes, dict]:
    packet = need(args.packet)
    opcode, plain_from_packet = parse_packet(packet)
    print(f"=== 1. 解析来源 ===")
    print(f"  {rel(args.packet)}  opcode={opcode}  明文 {len(plain_from_packet)} B  sha256={sha(plain_from_packet)[:16]}")
    if opcode != OPCODE:
        raise CodecError(f"opcode 应为 {OPCODE}, 实得 {opcode}")

    ssd = need(args.ssd)
    plain_from_ssd = parse_ssd(ssd)
    print(f"  {rel(args.ssd)}  明文 {len(plain_from_ssd)} B  sha256={sha(plain_from_ssd)[:16]}")
    if plain_from_packet != plain_from_ssd:
        raise CodecError("op210 包内明文与本地 .ssd 缓存明文不一致")

    plain = plain_from_packet
    if args.source_from == "ssd":
        plain = plain_from_ssd

    records = parse_plain(plain)
    print(f"  解析出 {len(records)} 条记录，逐条往返校验通过，无剩余字节")

    meta = {
        "origin": "S->C op 210 (CDynamicMapMan) / 客户端 fxdynmapinfo.ssd 同源",
        "packet": rel(args.packet),
        "ssd_cache": args.ssd,
        "record_count": len(records),
        "plain_size": len(plain),
        "plain_sha256": sha(plain),
        "ssd_checksum": hex(struct.unpack_from("<I", ssd, 4)[0]),
        "ssd_sha256": sha(ssd),
        "packet_sha256": sha(packet),
        "note": "source 段仅记录基准指纹，序列化器不读取；唯一输入是 records 数组（顺序即 mapId 下标）",
    }
    text = dump_source(records, meta)
    os.makedirs(os.path.dirname(args.source), exist_ok=True)
    with open(args.source, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"\n=== 2. 生成 JSON 单一事实源 ===")
    print(f"  {rel(args.source)}  schema={SOURCE_SCHEMA}  {len(text.encode('utf-8'))} B  {len(records)} 条")
    return plain, meta


def verify(args, report: Report) -> None:
    doc = load_source(args.source)
    records = doc["records"]
    print(f"\n=== 3. 从 JSON 重新生成二进制并逐字节比对 ===")
    print(f"  输入 {rel(args.source)}  {len(records)} 条记录")

    report.assert_true("records 双向往返 (parse_plain . serialize_plain == 原记录)",
                       parse_plain(serialize_plain(records)) == records,
                       f"{len(records)} 条")

    plain = serialize_plain(records)
    ssd = build_ssd(plain)
    packet = build_packet(plain)

    report.check("明文 24111 B", plain, need(args.plain), args.plain)
    report.check("本地 .ssd 缓存", ssd, need(args.ssd), args.ssd)
    report.check("S→C op 210 完整包", packet, need(args.packet), args.packet)
    report.check("op 210 包体 (去 opcode)", packet[2:], need(args.practice), args.practice)

    print(f"\n=== 4. 派生数据一致性 ===")
    ids = derive_map_ids(records)
    report.assert_true("flag==0 记录数 = 193", len(ids) == 193, f"实得 {len(ids)}")
    for map_id, name in KNOWN_MAP_IDS.items():
        got = ids.get(map_id, {}).get("name")
        report.assert_true(f"mapId {map_id} -> {name}", got == name, f"实得 {got!r}")
    for map_id, theme in ((175, 28), (176, 29)):
        got = ids.get(map_id, {}).get("theme_id")
        report.assert_true(f"mapId {map_id} themeId = {theme} (抓包 op85/op92)", got == theme,
                           f"实得 {got!r}")

    report.assert_true("mapId 连续且无空洞", set(ids) == set(range(len(ids))),
                       f"0..{len(ids) - 1}")

    if not args.no_emit:
        os.makedirs(args.emit_dir, exist_ok=True)
        for name, data in (("fxdynmapinfo_plain.bin", plain),
                           ("fxdynmapinfo.ssd", ssd),
                           ("s2c_op210.bin", packet)):
            with open(os.path.join(args.emit_dir, name), "wb") as f:
                f.write(data)
        print(f"\n  重建产物已写入 {rel(args.emit_dir)}/ (3 个文件)")


def main() -> int:
    ap = argparse.ArgumentParser(description="生成/验证 fxdynmapinfo JSON 单一事实源")
    ap.add_argument("--source", default=DEFAULT_SOURCE, help="JSON 单一事实源路径")
    ap.add_argument("--packet", default=DEFAULT_PACKET, help="S→C op210 抓包基准")
    ap.add_argument("--practice", default=DEFAULT_PRACTICE, help="op210 包体基准 (去 opcode)")
    ap.add_argument("--plain", default=DEFAULT_PLAIN, help="解压明文基准")
    ap.add_argument("--ssd", default=DEFAULT_SSD, help="本地 .ssd 缓存基准")
    ap.add_argument("--from", dest="source_from", choices=("packet", "ssd"), default="packet",
                    help="records 的解析来源，默认 op210 抓包")
    ap.add_argument("--verify-only", action="store_true", help="不重新生成 JSON，只验证")
    ap.add_argument("--no-emit", action="store_true", help="不写重建产物")
    ap.add_argument("--emit-dir", default=DEFAULT_EMIT, help="重建产物目录")
    args = ap.parse_args()

    try:
        if not args.verify_only:
            generate(args)
        report = Report()
        verify(args, report)
        print(f"\n=== 结果: {'全部通过' if report.ok else '存在失败项'} ===")
        return 0 if report.ok else 1
    except (CodecError, OSError, ValueError) as e:
        print(f"\n错误: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
