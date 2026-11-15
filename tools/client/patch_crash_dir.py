#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PopTag 客户端崩溃日志/Dump 输出目录重定向补丁。

背景
----
CA.exe 内置 3 处崩溃现场输出逻辑，均通过 `GetWindowsDirectoryA(Buffer, 0x104)`
获取目标目录，再用 `sprintf(FileName, "%s\\%s...", Buffer, ...)` 拼接路径：
  1. sub_689A36 (0x00689AC0): 写入 %WinDir%\\CA_Crash_info.nfo
  2. sub_6C4459 (0x006C4664): 写入 %WinDir%\\Crash_CA.dmp.log
  3. sub_6C4A83 (0x006C4B01): 写入 %WinDir%\\Crash_CA.dmp

在现代 Windows 下普通用户无权写入 C:\\Windows\\，导致崩溃时文件创建失败；
同时客户端还存在读取并 DeleteFileA 清理 %WinDir% 下这 3 个文件的上报逻辑
（sub_689916 / sub_6C4944 / sub_6C53B5）。

补丁
----
保留各自函数内 `lea eax, [Buffer]`，将压参和 `call GetWindowsDirectoryA` 原地替换为：
  mov word ptr [eax], 0x002E   ; 66 C7 00 2E 00 -> 将 Buffer 设为 "."
  nop ...

效果：
  - 崩溃后直接将 CA_Crash_info.nfo / Crash_CA.dmp.log / Crash_CA.dmp
    写入当前工作目录（WinMain 启动时已 SetCurrentDirectoryA 到 CA.exe 所在目录）；
  - 仅修改上述 3 处写入点，不改动全局 GetWindowsDirectoryA，也不改动清理/上报逻辑
    （不会在下次启动时误删游戏目录中的崩溃文件）。

用法
----
  python tools/client/patch_crash_dir.py             # 应用补丁（首次自动备份 CA.exe.crashbak）
  python tools/client/patch_crash_dir.py --dry-run   # 仅预览偏移与字节，不写入
  python tools/client/patch_crash_dir.py --verify    # 校验当前补丁状态
  python tools/client/patch_crash_dir.py --restore   # 从 CA.exe.crashbak 还原

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
BAK_SUFFIX = ".crashbak"

# 补丁点列表: (名称, 起始VA, 原始字节, 补丁字节, 说明)
PATCHES = [
    (
        "sub_689A36 (CA_Crash_info.nfo)",
        0x00689AC0,
        bytes.fromhex("53 8D 85 34 01 00 00 50 FF 15 90 91 88 00"),
        bytes.fromhex("90 8D 85 34 01 00 00 66 C7 00 2E 00 90 90"),
        "push ebx + lea eax,[Buffer] + push eax + call GetWindowsDirectoryA -> Buffer = '.'",
    ),
    (
        "sub_6C4459 (Crash_CA.dmp.log)",
        0x006C4664,
        bytes.fromhex("68 04 01 00 00 8D 45 00 50 FF 15 90 91 88 00"),
        bytes.fromhex("90 90 90 90 90 8D 45 00 66 C7 00 2E 00 90 90"),
        "push 104h + lea eax,[Buffer] + push eax + call GetWindowsDirectoryA -> Buffer = '.'",
    ),
    (
        "sub_6C4A83 (Crash_CA.dmp)",
        0x006C4B01,
        bytes.fromhex("68 04 01 00 00 8D 85 80 01 00 00 50 FF 15 90 91 88 00"),
        bytes.fromhex("90 90 90 90 90 8D 85 80 01 00 00 66 C7 00 2E 00 90 90"),
        "push 104h + lea eax,[Buffer] + push eax + call GetWindowsDirectoryA -> Buffer = '.'",
    ),
]


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
    ap = argparse.ArgumentParser(description="将 CA.exe 崩溃日志/Dump 写入目录重定向到当前游戏目录 (.)")
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
    print(f"[i] {exe} (imagebase = 0x{image_base:08X})")

    resolved = []
    states = []
    for name, va, orig, patched, _desc in PATCHES:
        assert len(orig) == len(patched), f"{name} 补丁字节长度不一致"
        off = va_to_off(va, image_base, sections)
        if off is None:
            print(f"[x] {name}: 0x{va:08X} 不在任何节内，PE 布局异常")
            return 1
        cur = bytes(data[off:off + len(orig)])
        state = "patched" if cur == patched else "original" if cur == orig else "unknown"
        print(f"    {name:32s} VA 0x{va:08X} -> off 0x{off:X} ({state})")
        if state == "unknown":
            print(f"      当前: {cur.hex(' ')}")
            print(f"      预期: {orig.hex(' ')}")
        resolved.append((name, off, orig, patched))
        states.append(state)

    if "unknown" in states:
        print("[!] 存在字节不匹配的补丁点（客户端版本不符或已装冲突补丁），拒绝修改")
        return 2

    if all(s == "patched" for s in states):
        print("[i] 全部 3 处均已打补丁，无需重复处理")
        return 0

    if args.verify or args.dry_run:
        print("[i] 尚未打补丁（或部分未打），预览/校验结束，未写入任何修改")
        return 0

    if not bak.is_file():
        shutil.copy2(exe, bak)
        print(f"[+] 已备份 -> {bak.name}")
    else:
        print(f"[i] 已存在备份 {bak.name}（保留最初版本，不覆盖）")

    for name, off, orig, patched in resolved:
        if bytes(data[off:off + len(orig)]) != patched:
            data[off:off + len(orig)] = patched
            print(f"[+] 已修补 {name}: {orig.hex(' ')} -> {patched.hex(' ')}")

    exe.write_bytes(data)
    print("[+] 完成！崩溃后将在游戏目录下生成 CA_Crash_info.nfo / Crash_CA.dmp.log / Crash_CA.dmp")
    print(f"    回滚命令: python tools/client/patch_crash_dir.py --restore --exe \"{exe}\"")
    return 0


if __name__ == "__main__":
    sys.exit(main())
