#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PopTag 客户端「解除练习/任务模式排除 Camp 主题 (theme_id == 120)」补丁。

背景
----
客户端 `CDynamicMapMan::sub_52FF0C`（`0x0052FF0C`）反汇编：

    0052FF2A: 83 7D 0C 10       cmp dword ptr [ebp+0Ch], 10h  ; a3 == 16
    0052FF2E: 74 06             je  0052FF36
    0052FF30: 83 7D 0C 0F       cmp dword ptr [ebp+0Ch], 0Fh  ; a3 == 15 (练习模式)
    0052FF34: 75 11             jne 0052FF47
    0052FF36: FF 75 08          push dword ptr [ebp+8]
    0052FF39: 8D 4E 18          lea ecx, [esi+18h]
    0052FF3C: E8 E8 F4 FF FF    call 0052F429                 ; 取地图记录
    0052FF41: 80 78 29 78       cmp byte ptr [eax+29h], 78h   ; *(record + 41) == 120 (Camp)
    0052FF45: 74 DF             je  0052FF26                  ; <-- 跳回 xor eax, eax 返回 false
    0052FF47: 33 C0             xor eax, eax
    0052FF49: 40                inc eax                       ; 返回 true

`sub_53121E` 在每次列表筛选都会调用 `sub_52FF0C(this, idx, sessionType)`，导致 `fxdynmapinfo.ssd`
里本来带有练习标记（`v33 == 1`）的 Camp 主题条目（`theme_id == 120`）在练习模式下全部被硬编码排除。

补丁
----
先校验 `0x0052FF41` 处 `cmp byte ptr [eax+29h], 78h`（`80 78 29 78`）指令完整，
再将 `0x0052FF45` 处的 `74 DF`（`je 0052FF26`）替换为 `90 90`（`nop nop`）：
  · 保留越界检查与函数返回值/栈平衡；
  · 仅解除 `sessionType == 15 / 16` 对 `theme_id == 120`（Camp）的硬编码排除。
  · 注：地图最终能否出现在练习列表，仍受 `fxdynmapinfo.ssd` 对应记录的 `tail1`、`v33`、等级等过滤控制。

用法
----
  python tools/client/patch_practice_camp.py             # 应用补丁（首次自动备份 CA.exe.campbak）
  python tools/client/patch_practice_camp.py --dry-run   # 仅预览偏移与字节，不写入
  python tools/client/patch_practice_camp.py --verify    # 校验当前补丁状态
  python tools/client/patch_practice_camp.py --restore   # 从 CA.exe.campbak 还原

⚠️ 打补丁前请关闭游戏客户端（文件被占用时写入会失败）。
"""

import argparse
import shutil
import struct
import sys
from pathlib import Path

if sys.stdout.encoding != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")

DEFAULT_EXE = r"F:\Games\PopTag\CA.exe"
BAK_SUFFIX = ".campbak"

CMP_VA = 0x0052FF41
CMP_PREFIX = bytes.fromhex("80 78 29 78")  # cmp byte ptr [eax+29h], 78h

PATCH_VA = 0x0052FF45
ORIG = bytes.fromhex("74 DF")              # je 0x0052FF26
PATCHED = bytes.fromhex("90 90")           # nop nop


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


def main() -> int:
    ap = argparse.ArgumentParser(description="给 CA.exe 打补丁：NOP 掉练习/任务模式对 Camp 主题 (120) 的排除")
    ap.add_argument("--exe", default=DEFAULT_EXE, help=f"CA.exe 路径（默认 {DEFAULT_EXE}）")
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
    cmp_off = va_to_off(CMP_VA, image_base, sections)
    off = va_to_off(PATCH_VA, image_base, sections)
    if cmp_off is None or off is None:
        print(f"[x] 0x{PATCH_VA:08X} 不在任何节内，PE 布局异常")
        return 1

    prefix = bytes(data[cmp_off:cmp_off + len(CMP_PREFIX)])
    cur = bytes(data[off:off + len(ORIG)])
    if prefix != CMP_PREFIX:
        print(f"[x] 0x{CMP_VA:08X} 前置指令不匹配: {prefix.hex(' ')} != {CMP_PREFIX.hex(' ')}")
        return 2

    state = "patched" if cur == PATCHED else "original" if cur == ORIG else "unknown"
    print(f"[i] {exe}")
    print(f"    imagebase = 0x{image_base:08X}")
    print(f"    0x{CMP_VA:08X} -> 0x{cmp_off:X} ({prefix.hex(' ')}) [cmp 校验通过]")
    print(f"    0x{PATCH_VA:08X} -> 0x{off:X} ({cur.hex(' ')}) [{state}]")

    if state == "unknown":
        print(f"[!] 字节不匹配（预期 {ORIG.hex(' ')} 或 {PATCHED.hex(' ')}），拒绝修改")
        return 2

    if args.verify or args.dry_run:
        print("[i] 已经是补丁状态，无需修改" if state == "patched" else "[i] 尚未打补丁（预览/校验未写入）")
        return 0

    if state == "patched":
        print("[i] 已经是补丁状态，无需重复处理")
        return 0

    if not bak.is_file():
        shutil.copy2(exe, bak)
        print(f"[+] 已备份 -> {bak.name}")
    else:
        print(f"[i] 已存在备份 {bak.name}（保留最初版本，不覆盖）")

    data[off:off + len(PATCHED)] = PATCHED
    exe.write_bytes(data)
    print(f"[+] 已打补丁: {ORIG.hex(' ')} -> {PATCHED.hex(' ')}")
    print(f"    回滚命令: python tools/client/patch_practice_camp.py --restore --exe \"{exe}\"")
    return 0


if __name__ == "__main__":
    sys.exit(main())
