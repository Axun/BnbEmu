#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PopTag 客户端「禁用被水泡困住时叠加的 Ntc_Help2 提示音」补丁。

背景
----
`0x00464289`（sub_464289，角色进入 Faint / 被水泡困住状态）收尾反汇编：

    004644D4: 6A 10                push 0x10
    004644D6: E8 C7 C0 25 00       call 0x006C05A2      ; CNewConfig 特性位 0x10
    004644DB: 85 C0                test eax, eax
    004644DD: 59                   pop ecx
    004644DE: 74 1B                je   0x004644FB
    004644E0: 39 9E DC 2A 00 00    cmp [esi+0x2ADC], ebx  ; this+10972（构造参数 a7）
    004644E6: 74 13                je   0x004644FB
    004644E8: 8B 0D 48 3C 99 00    mov ecx, [0x993C48]     ; 音效管理器
    004644EE: 53                   push ebx                 ; a4 = 0
    004644EF: 6A 01                push 1                   ; a3 = 1
    004644F1: 68 14 01 00 00       push 0x114               ; a2 = 276 = Ntc_Help2
    004644F6: E8 B2 A8 07 00       call 0x004DEDAD          ; ★ 播放 Ntc_Help2
    004644FB: 8B 06                mov eax, [esi]            ; 后续逻辑
    ...
    00464513: 74 04 / 6A 25 / EB 02 / 6A 4D
              ; v19 ? 37 (Cha_CoupleLockedIn) : 77 (Cha_LockedIn)
    0046451B: E8 8D A8 07 00       call 0x004DEDAD

`sub_6C05A2(16)` → `sub_6C04F0(16)` 读 CNewConfig 单例 +4 掩码，该字段运行时为 0，
而 sub_6C04F0 在 0 时默认取 16 → 恒真。于是只要角色 +0x2ADC 非 0，被泡困住时
就会先播 Ntc_Help2，紧接着再播 Cha_CoupleLockedIn(37) 或 Cha_LockedIn(77)，
听感上就是无关的提示音与困住音叠加。两个 if 完全独立，情侣模式必然叠加。

补丁（两种等价做法，默认 nop）
------------------------------
nop : 把 0x004644E8 起 19 字节（mov ecx + 3×push + call）整段替换为 0x90。
      两个条件跳转本来就指向 0x004644FB（正好是 NOP 区之后的第一条指令），
      栈完全平衡；37/77 分支原样保留。
jmp : 只把 0x004644DE 的 `74 1B`(je) 改成 `EB 1B`(jmp)，永远跳过这段调用。
      只改 1 字节、同样栈平衡，但条件判断仍会白算一次。

⚠️ 不能只 NOP 掉 0x004644F6 那 5 字节 call：sub_4DEDAD 是 thiscall，
由被调方 `ret 12` 清理 3 个 push；留着 3 个 push 会让栈多 12 字节导致崩溃。

用法
----
  python tools/client/patch_no_help2.py                 # 应用（首次自动备份 CA.exe.help2bak）
  python tools/client/patch_no_help2.py --method jmp   # 用 1 字节 jmp 方案
  python tools/client/patch_no_help2.py --dry-run      # 只预览偏移与字节，不写入
  python tools/client/patch_no_help2.py --verify       # 校验当前补丁状态
  python tools/client/patch_no_help2.py --restore      # 从 CA.exe.help2bak 还原

⚠️ 打补丁前请关闭游戏客户端（文件被占用时写入会失败）。
"""

import argparse
import os
import shutil
import struct
import sys
from pathlib import Path

if sys.stdout.encoding != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")

DEFAULT_EXE = r"F:\Games\PopTag\CA.exe"
BAK_SUFFIX = ".help2bak"

# 前置锚点：条件判断（sub_6C05A2(16) + cmp [esi+0x2ADC]）
ANCHOR_PRE_VA = 0x004644D4
ANCHOR_PRE = bytes.fromhex(
    "6A 10 E8 C7 C0 25 00 85 C0 59 74 1B 39 9E DC 2A 00 00 74 13"
)

# 后置锚点：37 / 77 分支，确认没被别的补丁动过
ANCHOR_POST_VA = 0x00464513
ANCHOR_POST = bytes.fromhex("74 04 6A 25 EB 02 6A 4D")

# 方案 nop：整段 19 字节
NOP_VA = 0x004644E8
NOP_ORIG = bytes.fromhex(
    "8B 0D 48 3C 99 00"      # mov ecx, [0x993C48]
    "53"                      # push ebx
    "6A 01"                   # push 1
    "68 14 01 00 00"          # push 0x114 (276)
    "E8 B2 A8 07 00"          # call 0x004DEDAD
)
NOP_PATCHED = b"\x90" * len(NOP_ORIG)

# 方案 jmp：1 字节 74 -> EB（je -> jmp，rel8 0x1B 不变）
JMP_VA = 0x004644DE
JMP_ORIG = bytes.fromhex("74 1B")
JMP_PATCHED = bytes.fromhex("EB 1B")

METHODS = ("nop", "jmp")


def load_pe(path: Path):
    data = bytearray(path.read_bytes())
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        raise SystemExit(f"{path} 不是有效的 PE 文件")
    nsec = struct.unpack_from("<H", data, pe + 6)[0]
    optsz = struct.unpack_from("<H", data, pe + 20)[0]
    opt = pe + 24
    image_base = struct.unpack_from("<I", data, opt + 28)[0]
    sec = opt + optsz
    sections = []
    for i in range(nsec):
        o = sec + i * 40
        name = data[o:o + 8].rstrip(b"\0").decode(errors="replace")
        vsz, va, rsz, ro = struct.unpack_from("<IIII", data, o + 8)
        sections.append((name, va, max(vsz, rsz), ro))
    return data, image_base, sections


def va_to_off(va: int, image_base: int, sections) -> int | None:
    rva = va - image_base
    for _name, sva, size, raw in sections:
        if sva <= rva < sva + size:
            return raw + (rva - sva)
    return None


def read_at(data, off, size):
    return bytes(data[off:off + size])


def atomic_write(exe: Path, data: bytearray) -> None:
    """先写同目录临时文件再原子替换，避免写入中途失败损坏 CA.exe。"""
    tmp = exe.with_name(exe.name + ".patchtmp")
    try:
        tmp.write_bytes(data)
        os.replace(tmp, exe)
    finally:
        if tmp.is_file():
            tmp.unlink()


def main() -> int:
    ap = argparse.ArgumentParser(
        description="给 CA.exe 打补丁：禁用被水泡困住时叠加的 Ntc_Help2 提示音"
    )
    ap.add_argument("--exe", default=DEFAULT_EXE, help=f"CA.exe 路径（默认 {DEFAULT_EXE}）")
    ap.add_argument("--method", choices=METHODS, default="nop",
                    help="nop=整段 19 字节改 0x90（默认）；jmp=je 改 jmp（1 字节）")
    ap.add_argument("--dry-run", action="store_true", help="只预览偏移与状态，不写入文件")
    ap.add_argument("--verify", action="store_true", help="只校验当前补丁状态")
    ap.add_argument("--restore", action="store_true", help=f"从 *{BAK_SUFFIX} 备份还原")
    args = ap.parse_args()

    exe = Path(args.exe)
    if not exe.is_file():
        print(f"[x] 找不到 {exe}")
        return 1
    bak = exe.with_name(exe.name + BAK_SUFFIX)

    if args.restore:
        if not bak.is_file():
            print(f"[x] 没有备份 {bak}，无法还原")
            return 1
        shutil.copy2(bak, exe)
        print(f"[+] 已从 {bak.name} 还原 {exe.name}")
        return 0

    data, image_base, sections = load_pe(exe)
    offsets = {k: va_to_off(v, image_base, sections) for k, v in (
        ("anchor_pre", ANCHOR_PRE_VA),
        ("anchor_post", ANCHOR_POST_VA),
        ("nop", NOP_VA),
        ("jmp", JMP_VA),
    )}
    if any(o is None for o in offsets.values()):
        print("[x] 补丁地址不在任何节内，PE 布局异常")
        return 1

    # 锚点校验：确认客户端版本与 37/77 分支完好
    for key, expected in (("anchor_pre", ANCHOR_PRE), ("anchor_post", ANCHOR_POST)):
        got = read_at(data, offsets[key], len(expected))
        if got != expected:
            va = ANCHOR_PRE_VA if key == "anchor_pre" else ANCHOR_POST_VA
            print(f"[x] 0x{va:08X} 锚点不匹配: {got.hex(' ')} != {expected.hex(' ')}")
            print("    （客户端版本不同或已被其它补丁改动，拒绝写入）")
            return 2

    nop_bytes = read_at(data, offsets["nop"], len(NOP_ORIG))
    jmp_bytes = read_at(data, offsets["jmp"], len(JMP_ORIG))

    if nop_bytes == NOP_PATCHED and jmp_bytes == JMP_ORIG:
        state = "nop"
    elif jmp_bytes == JMP_PATCHED and nop_bytes == NOP_ORIG:
        state = "jmp"
    elif nop_bytes == NOP_ORIG and jmp_bytes == JMP_ORIG:
        state = "original"
    else:
        state = "unknown"

    print(f"[i] {exe}")
    print(f"    imagebase = 0x{image_base:08X}")
    print(f"    0x{ANCHOR_PRE_VA:08X} -> 0x{offsets['anchor_pre']:X}  [条件判断锚点 OK]")
    print(f"    0x{ANCHOR_POST_VA:08X} -> 0x{offsets['anchor_post']:X}  [37/77 分支锚点 OK]")
    print(f"    0x{NOP_VA:08X} -> 0x{offsets['nop']:X}  ({nop_bytes.hex(' ')})")
    print(f"    0x{JMP_VA:08X} -> 0x{offsets['jmp']:X}  ({jmp_bytes.hex(' ')})")
    print(f"    当前状态: {state}")

    if state == "unknown":
        print("[!] 字节不匹配（既非原版也非任一补丁状态），拒绝修改")
        return 2

    if args.verify or args.dry_run:
        print("[i] 预览/校验完成，未写入" if args.dry_run else "[i] 校验完成，未写入")
        return 0

    if state == args.method:
        print(f"[i] 已经是 {args.method} 补丁状态，无需重复处理")
        return 0
    if state != "original":
        print(f"[!] 当前已是 {state} 方案；要切换到 {args.method} 请先 --restore 再重新应用")
        return 2

    if not bak.is_file():
        shutil.copy2(exe, bak)
        print(f"[+] 已备份 -> {bak.name}")
    else:
        print(f"[i] 已存在备份 {bak.name}（保留最初版本，不覆盖）")

    if args.method == "nop":
        off, orig, patched = offsets["nop"], NOP_ORIG, NOP_PATCHED
    else:
        off, orig, patched = offsets["jmp"], JMP_ORIG, JMP_PATCHED
    data[off:off + len(patched)] = patched
    try:
        atomic_write(exe, data)
    except OSError as e:
        print(f"[x] 写入失败（游戏客户端可能正在运行，请先关闭）: {e}")
        return 1
    print(f"[+] 已打补丁 [{args.method}]: {orig.hex(' ')} -> {patched.hex(' ')}")
    print("    效果: 被泡困住只播 Cha_CoupleLockedIn(37) / Cha_LockedIn(77)，不再叠加 Ntc_Help2(276)")
    print(f"    回滚命令: python tools/client/patch_no_help2.py --restore --exe \"{exe}\"")
    return 0


if __name__ == "__main__":
    sys.exit(main())
