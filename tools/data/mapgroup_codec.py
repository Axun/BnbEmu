# -*- coding: utf-8 -*-
"""
MapGroup.bin <-> JSON 双向编解码（fx/Attr/MapGroup.bin，明文分组表）。

文件结构（小端 u32，str = u32 字节长度 + 原始字节，无结束符）:

    u32  groupCount
    每组:
        str  groupName
        u32  mapCount
        mapCount x str  mapName

真端文件 663 B = 3 组: PraticeMapGroup(45 张练习图)、LadderMapGroup(6)、
ReBirthMapGroup(2)。注意 "Pratice" 是原文件拼写（不要顺手改成 Practice），
ReBirthMapGroup 的 "Tomb?" / "Cemetery?" 里 '?' 是客户端的通配前缀，均按原样保留。

字符串默认按 latin1 处理（字节 <-> U+0000..U+00FF 一一对应），保证任意字节无损往返；
JSON 文件本身是 UTF-8。若原文件用其它多字节编码，decode 时加 --encoding cp949/gbk/...，
编码名会写进 JSON 的 wire.string_encoding，encode/check 时自动沿用（命令行可覆盖）。

用法（两个位置参数顺序恒为 [bin] [json]，省略则用默认路径）:
    python tools/mapgroup_codec.py decode                      # bin -> json
    python tools/mapgroup_codec.py encode                      # json -> bin（覆盖）
    python tools/mapgroup_codec.py check                       # 只读比对
    python tools/mapgroup_codec.py decode <in.bin> <out.json>
    python tools/mapgroup_codec.py encode <out.bin> <in.json>
    python tools/mapgroup_codec.py check  <in.bin> <in.json>

默认 bin  = F:/Games/PopTag/extracted/fx/Attr/MapGroup.bin
默认 json = 本脚本同目录的 mapgroup.json

encode 会覆盖目标 bin：若原文件存在且内容不同，先备份成 <bin>.bak（--no-backup 跳过），
写完后重新读回逐字节校验；内容本来就一致时不改写、不备份。

注意：extracted/ 下是解密提取出来的明文。服务端不消费这份数据（见 MapCatalog.kt:
真端只在「练习房限定练习图」与「重生玩法标记」两处读 Attr/MapGroup）；若要让客户端
真正生效，还需按提取的逆流程把 bin 放回资源包，本脚本只负责 bin <-> json 这一段。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import struct
import sys

SCHEMA = "bnbemu/mapgroup@1"
DEFAULT_BIN = r"F:\Games\PopTag\extracted\fx\Attr\MapGroup.bin"
DEFAULT_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mapgroup.json")
GROUP_FIELDS = ("name", "maps")
MAX_U32 = 0xFFFFFFFF


class CodecError(ValueError):
    pass


# ---------------------------------------------------------------------------
# 二进制读写
# ---------------------------------------------------------------------------

def _read_u32(buf: bytes, pos: int) -> tuple[int, int]:
    if pos + 4 > len(buf):
        raise CodecError(f"u32 越界 pos={pos} len={len(buf)}")
    return struct.unpack_from("<I", buf, pos)[0], pos + 4


def _read_str(buf: bytes, pos: int, encoding: str) -> tuple[str, int]:
    n, pos = _read_u32(buf, pos)
    if n > len(buf) - pos:
        raise CodecError(f"字符串越界 pos={pos} len={n} 剩余={len(buf) - pos}")
    raw = buf[pos:pos + n]
    try:
        return raw.decode(encoding), pos + n
    except UnicodeDecodeError as e:
        raise CodecError(
            f"字符串按 {encoding} 解码失败 (pos={pos}, bytes={raw.hex()}): {e}; "
            f"试试 --encoding"
        ) from e


def _str_bytes(s: str, encoding: str) -> bytes:
    try:
        raw = s.encode(encoding)
    except UnicodeEncodeError as e:
        raise CodecError(f"字符串 {s!r} 无法按 {encoding} 编码（可换 --encoding）") from e
    if len(raw) > MAX_U32:
        raise CodecError("字符串超过 u32 长度上限")
    return struct.pack("<I", len(raw)) + raw


def validate_groups(groups, encoding: str) -> None:
    """严格校验：组名/地图名都必须是能按 encoding 还原成字节的字符串。"""
    if not isinstance(groups, list):
        raise CodecError("groups 必须是数组")
    if len(groups) > MAX_U32:
        raise CodecError(f"组数 {len(groups)} 超过 u32 上限")
    for i, g in enumerate(groups):
        if not isinstance(g, dict):
            raise CodecError(f"groups[{i}] 不是对象")
        missing = [k for k in GROUP_FIELDS if k not in g]
        extra = [k for k in g if k not in GROUP_FIELDS]
        if missing or extra:
            raise CodecError(f"groups[{i}] 字段不符: 缺 {missing}, 多 {extra}")
        if not isinstance(g["name"], str):
            raise CodecError(f"groups[{i}].name 不是字符串")
        _str_bytes(g["name"], encoding)
        if not isinstance(g["maps"], list):
            raise CodecError(f"groups[{i}].maps 不是数组")
        if len(g["maps"]) > MAX_U32:
            raise CodecError(f"groups[{i}].maps 超过 u32 上限")
        for j, m in enumerate(g["maps"]):
            if not isinstance(m, str):
                raise CodecError(f"groups[{i}].maps[{j}] 不是字符串")
            _str_bytes(m, encoding)


def _group_bytes(g: dict, encoding: str) -> bytes:
    out = bytearray(_str_bytes(g["name"], encoding))
    out += struct.pack("<I", len(g["maps"]))
    for m in g["maps"]:
        out += _str_bytes(m, encoding)
    return bytes(out)


def build_bin(groups, encoding: str = "latin1") -> bytes:
    """groups -> MapGroup.bin；两个计数字段一律由数组长度派生。"""
    validate_groups(groups, encoding)
    out = bytearray(struct.pack("<I", len(groups)))
    for g in groups:
        out += _group_bytes(g, encoding)
    return bytes(out)


def parse_bin(data: bytes, encoding: str = "latin1"):
    """MapGroup.bin -> groups，逐组做往返校验，且不允许尾部残留字节。"""
    count, pos = _read_u32(data, 0)
    groups = []
    for i in range(count):
        start = pos
        try:
            name, pos = _read_str(data, pos, encoding)
            map_count, pos = _read_u32(data, pos)
            maps = []
            for _ in range(map_count):
                m, pos = _read_str(data, pos, encoding)
                maps.append(m)
        except CodecError as e:
            raise CodecError(f"第 {i} 组解析失败 (offset={start}): {e}") from e
        g = {"name": name, "maps": maps}
        if _group_bytes(g, encoding) != data[start:pos]:
            raise CodecError(f"第 {i} 组往返校验失败 (offset={start})")
        groups.append(g)
    if pos != len(data):
        raise CodecError(f"解析结束位置 {pos} != 文件长度 {len(data)}"
                         f"（尾部还有 {len(data) - pos} 字节未知数据）")
    # 整体再校验一次：确认 count / 组 / 地图名全部归位
    if build_bin(groups, encoding) != data:
        raise CodecError("整体往返校验失败")
    return groups


# ---------------------------------------------------------------------------
# JSON 读写（source / wire 只是元数据，序列化器不依赖它们决定内容）
# ---------------------------------------------------------------------------

def dump_json(groups, *, bin_path: str, bin_data: bytes, encoding: str) -> str:
    """生成紧凑 JSON：每个地图名独占一行，便于审阅与 diff。"""
    validate_groups(groups, encoding)
    source = {
        "bin": bin_path,
        "size": len(bin_data),
        "sha256": hashlib.sha256(bin_data).hexdigest(),
    }
    wire = {
        "layout": "u32 groupCount + N x (str name + u32 mapCount + mapCount x str)",
        "str": "u32 LE byte-length + raw bytes (no terminator)",
        "endian": "little",
        "string_encoding": encoding,
    }

    def block(key: str, value: dict) -> str:
        text = json.dumps(value, ensure_ascii=False, indent=2).replace("\n", "\n  ")
        return f'  "{key}": {text},'

    lines = [
        "{",
        f'  "schema": {json.dumps(SCHEMA)},',
        block("source", source),
        block("wire", wire),
        '  "groups": [',
    ]
    for gi, g in enumerate(groups):
        lines.append("    {")
        lines.append(f'      "name": {json.dumps(g["name"], ensure_ascii=False)},')
        lines.append('      "maps": [')
        for mi, m in enumerate(g["maps"]):
            comma = "," if mi < len(g["maps"]) - 1 else ""
            lines.append("        " + json.dumps(m, ensure_ascii=False) + comma)
        lines.append("      ]")
        lines.append("    }" + ("," if gi < len(groups) - 1 else ""))
    lines += ["  ]", "}", ""]
    return "\n".join(lines)


def load_json(path: str):
    with open(path, "r", encoding="utf-8") as f:
        doc = json.load(f)
    if not isinstance(doc, dict) or doc.get("schema") != SCHEMA:
        raise CodecError(f"JSON schema 不符（期望 {SCHEMA!r}）: {path}")
    wire = doc.get("wire") or {}
    if not isinstance(wire, dict):
        raise CodecError("wire 必须是对象")
    encoding = wire.get("string_encoding", "latin1")
    if not isinstance(encoding, str):
        raise CodecError("wire.string_encoding 必须是字符串")
    groups = doc.get("groups")
    validate_groups(groups, encoding)
    return doc, groups, encoding


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _open_bin(path: str) -> bytes:
    if not os.path.isfile(path):
        raise CodecError(f"找不到 bin: {path}")
    with open(path, "rb") as f:
        return f.read()


def _encoding(args, json_encoding: str = "latin1") -> str:
    return args.encoding or json_encoding


def cmd_decode(args) -> int:
    data = _open_bin(args.bin)
    encoding = _encoding(args)
    groups = parse_bin(data, encoding)
    text = dump_json(groups, bin_path=args.bin, bin_data=data, encoding=encoding)
    parent = os.path.dirname(os.path.abspath(args.json))
    if not os.path.isdir(parent):
        raise CodecError(f"JSON 输出目录不存在: {parent}")
    with open(args.json, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"[decode] {args.bin} ({len(data)} B) -> {args.json}")
    for g in groups:
        print(f"  {g['name']}: {len(g['maps'])} 张图")
    print(f"  共 {len(groups)} 组；逐字节往返校验通过，无尾部残留")
    return 0


def cmd_encode(args) -> int:
    doc, groups, json_encoding = load_json(args.json)
    encoding = _encoding(args, json_encoding)
    if args.encoding and args.encoding != json_encoding:
        print(f"[warn] 命令行编码 {args.encoding} 覆盖了 JSON 记录的 {json_encoding}")
    validate_groups(groups, encoding)  # 命令行覆盖编码后重新校验
    out = build_bin(groups, encoding)

    if os.path.isfile(args.bin):
        old = _open_bin(args.bin)
        source = doc.get("source")
        expect = source.get("sha256") if isinstance(source, dict) else None
        if expect and hashlib.sha256(old).hexdigest() != expect:
            print(f"[warn] 目标 bin 的 sha256 与 JSON source.sha256 不一致，可能已被外部改动")
        if old == out:
            print(f"[encode] {args.bin} 内容本来就一致，无需改写")
            return 0
        if not args.no_backup:
            backup = args.bin + ".bak"
            shutil.copy2(args.bin, backup)
            print(f"[backup] {backup}")
    else:
        parent = os.path.dirname(os.path.abspath(args.bin))
        if not os.path.isdir(parent):
            raise CodecError(f"bin 输出目录不存在: {parent}")

    with open(args.bin, "wb") as f:
        f.write(out)
    if _open_bin(args.bin) != out:
        raise CodecError("写回后读取校验失败")
    print(f"[encode] {args.json} -> {args.bin} ({len(out)} B, {len(groups)} 组)，写回逐字节校验通过")
    return 0


def cmd_check(args) -> int:
    data = _open_bin(args.bin)
    _, groups, json_encoding = load_json(args.json)
    encoding = _encoding(args, json_encoding)
    parse_bin(data, encoding)  # 顺带确认 bin 自身结构自洽
    out = build_bin(groups, encoding)
    if out == data:
        print(f"[check] 一致：{args.json} 生成的 {len(out)} B 与 {args.bin} 逐字节相同"
              f"（{len(groups)} 组）")
        return 0
    n = min(len(out), len(data))
    i = next((k for k in range(n) if out[k] != data[k]), n)
    print(f"[check] 不一致：bin={len(data)} B, json->{len(out)} B, "
          f"首个差异 offset={i} (bin=0x{data[i:i + 1].hex()}, json=0x{out[i:i + 1].hex()})")
    return 1


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="MapGroup.bin <-> JSON 双向编解码",
        epilog=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("action", choices=("decode", "encode", "check"),
                    help="decode: bin->json; encode: json->bin; check: 只读逐字节比对")
    ap.add_argument("bin", nargs="?", default=DEFAULT_BIN, help=f"bin 路径 (默认 {DEFAULT_BIN})")
    ap.add_argument("json", nargs="?", default=DEFAULT_JSON, help=f"json 路径 (默认 {DEFAULT_JSON})")
    ap.add_argument("--encoding", default=None,
                    help="字符串编码，默认 latin1；decode 写入 JSON，encode/check 覆盖 JSON 内记录值")
    ap.add_argument("--no-backup", action="store_true", help="encode 覆盖 bin 时不生成 .bak 备份")
    args = ap.parse_args(argv)
    try:
        return {"decode": cmd_decode, "encode": cmd_encode, "check": cmd_check}[args.action](args)
    except (CodecError, OSError, UnicodeError, json.JSONDecodeError) as e:
        print(f"[error] {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
