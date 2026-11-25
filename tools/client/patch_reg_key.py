#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PopTag CA.exe 注册表根键 HKLM -> HKCU 补丁。

背景：
  客户端把全部游戏设置写在 HKLM\\SOFTWARE\\MPlay\\Crazy Arcade，且用
  RegCreateKeyExA(..., samDesired = KEY_QUERY_VALUE | KEY_SET_VALUE) 打开。
  patch_noadmin.py 把清单里的 requireAdministrator 改成 asInvoker 后，普通用户对该键
  没有 KEY_SET_VALUE 权限 -> RegCreateKeyExA 返回 ACCESS_DENIED、hKey 为 NULL，
  sub_680929 里的 `if (hKey != 0)` 整块跳过：设置读不到、也存不下（静默回落默认值）。
  又因为 exe 带显式 requestedExecutionLevel，UAC 注册表虚拟化同样不生效，没有兜底。

本补丁：
  把三处 `push 80000002h`（HKEY_LOCAL_MACHINE）改成 `push 80000001h`（HKEY_CURRENT_USER），
  子键路径字符串保持原样，最终落地为：
      HKCU\\SOFTWARE\\MPlay\\Crazy Arcade
  普通用户可读写，不再需要 UAC。

补丁点（PE ImageBase = 0x400000，本 exe 的 .text 文件偏移 == VA - 0x400000）：
  0x680B00  sub_680929  RegCreateKeyExA  设置创建/加载（主补丁点）
  0x67F531  sub_67F4E8  RegOpenKeyExA    商城公告读取（必须一起改！它复用同一句柄槽
                                         this+0x3C，只改一处会把 HKCU 句柄覆盖成 HKLM）
  0x3529D0  sub_7527B0  RegOpenKeyExA    CxSupportId 硬件指纹（可选；它本来就不该写 HKLM）

用法：
  python tools/client/patch_hkcu.py                  # 打补丁（自动备份 *.regbak）
  python tools/client/patch_hkcu.py --dry-run        # 只定位校验，不写入
  python tools/client/patch_hkcu.py --verify         # 只报告当前是否已打补丁
  python tools/client/patch_hkcu.py --migrate        # 把 HKLM 里已有设置拷到 HKCU（可单独跑）
  python tools/client/patch_hkcu.py --dump           # 打印两处注册表现有键值
  python tools/client/patch_hkcu.py --restore        # 从 *.regbak 还原
"""

from __future__ import annotations

import argparse
import shutil
import struct
import sys
from dataclasses import dataclass
from pathlib import Path

# 控制台中文输出兜底
if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")

DEFAULT_EXE_PATH = r"F:\Games\PopTag\CA.exe"
BAK_SUFFIX = ".regbak"
IMAGE_BASE = 0x400000

MPLAY_PATH = r"SOFTWARE\MPlay\Crazy Arcade"
WINVER_PATH = r"SOFTWARE\Microsoft\Windows\CurrentVersion"

HKEY_LM = 0x80000002  # HKEY_LOCAL_MACHINE
HKEY_CU = 0x80000001  # HKEY_CURRENT_USER


@dataclass(frozen=True)
class Site:
    va: int          # 期望的虚拟地址，用于定位窗口和自检
    desc: str        # 人类可读说明
    pattern: str     # 带 ?? 通配的字节串；根键那一字节也留空，便于识别"已打过"
    key_path: str    # ?? 处 imm32 必须指向这个子键字符串


SITES = (
    Site(
        va=0x680B00,
        desc="RegCreateKeyExA 设置创建/加载（sub_680929）",
        pattern="68 ?? ?? ?? ?? 68 ?? 00 00 80",
        key_path=MPLAY_PATH,
    ),
    Site(
        va=0x67F531,
        desc="RegOpenKeyExA 商城公告读取（sub_67F4E8）",
        pattern="68 ?? ?? ?? ?? 68 ?? 00 00 80",
        key_path=MPLAY_PATH,
    ),
    Site(
        va=0x7529D0,
        desc="RegOpenKeyExA CxSupportId 硬件指纹（sub_7527B0，可选）",
        pattern="68 ?? ?? ?? ?? 89 53 1c 68 ?? 00 00 80",
        key_path=WINVER_PATH,
    ),
)


def fail(msg: str) -> "NoReturn":
    print(f"[错误] {msg}")
    sys.exit(1)


# --------------------------------------------------------------------------- PE

def load_sections(data: bytes) -> list[tuple[str, int, int, int]]:
    """返回 [(名字, VA, 大小, 文件偏移)]，并校验 PE32 / ImageBase。"""
    if len(data) < 0x40 or data[:2] != b"MZ":
        fail("不是有效的 MZ/PE 文件")
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        fail("PE 头签名不符")
    nsec, = struct.unpack_from("<H", data, pe + 6)
    opt_size, = struct.unpack_from("<H", data, pe + 20)
    opt = pe + 24
    magic, = struct.unpack_from("<H", data, opt)
    if magic != 0x10B:
        fail(f"不是 PE32（magic=0x{magic:X}），本补丁只支持 32 位 CA.exe")
    image_base, = struct.unpack_from("<I", data, opt + 28)
    if image_base != IMAGE_BASE:
        fail(f"ImageBase 是 0x{image_base:X}，与预期的 0x{IMAGE_BASE:X} 不一致，拒绝打补丁")

    secs = []
    hdr = opt + opt_size
    for i in range(nsec):
        s = hdr + 40 * i
        name = data[s:s + 8].rstrip(b"\0").decode("ascii", "replace")
        vsize, va, rsize, rptr = struct.unpack_from("<IIII", data, s + 8)
        secs.append((name, image_base + va, max(vsize, rsize), rptr))
    return secs


def va2off(secs: list[tuple[str, int, int, int]], va: int) -> int | None:
    for _name, sva, size, raw in secs:
        if sva <= va < sva + size:
            return raw + (va - sva)
    return None


# ----------------------------------------------------------------- 字节串匹配

def parse_pattern(text: str) -> list[int | None]:
    return [None if tok == "??" else int(tok, 16) for tok in text.split()]


def scan_window(data: bytes, pat: list[int | None], lo: int, hi: int) -> list[int]:
    n = len(pat)
    lo = max(0, lo)
    hi = min(len(data) - n, hi)
    hits = []
    for i in range(lo, hi + 1):
        if all(p is None or data[i + j] == p for j, p in enumerate(pat)):
            hits.append(i)
    return hits


def read_cstr(data: bytes, off: int) -> str:
    end = data.find(b"\0", off)
    if end < 0:
        end = off + 256
    return data[off:end].decode("latin-1")


def locate(secs: list[tuple[str, int, int, int]], data: bytes, site: Site) -> tuple[int, int]:
    """返回 (匹配起点文件偏移, 根键 immediate 首字节偏移)。"""
    pat = parse_pattern(site.pattern)
    base_off = va2off(secs, site.va)
    if base_off is None:
        fail(f"{site.desc}: VA 0x{site.va:X} 不在任何节内")
    hits = scan_window(data, pat, base_off - 64, base_off + 64)
    if not hits:
        fail(
            f"{site.desc}: 在 0x{site.va:X} 附近找不到特征字节 {site.pattern!r}，"
            f"客户端版本可能不符"
        )

    # 用 ?? 处的 imm32 反查子键字符串，排除同形状的误命中
    valid: list[int] = []
    for h in hits:
        imm, = struct.unpack_from("<I", data, h + 1)
        s_off = va2off(secs, imm)
        if s_off is None:
            continue
        if read_cstr(data, s_off) == site.key_path:
            valid.append(h)
    if not valid:
        fail(f"{site.desc}: 命中的指令没有引用 {site.key_path!r}，拒绝打补丁")
    if len(valid) > 1:
        # 取离期望 VA 最近的一处
        valid.sort(key=lambda h: abs(h - base_off))
        print(f"  [提示] {site.desc}: 窗口内命中 {len(valid)} 处，取最近的 0x{valid[0] + IMAGE_BASE:X}")
    start = valid[0]
    return start, start + len(pat) - 4


# ---------------------------------------------------------------------- 迁移

def migrate_registry(force: bool = False) -> None:
    try:
        import winreg
    except ImportError:
        fail("当前 Python 不支持 winreg（仅 Windows 可用）")

    wow32 = getattr(winreg, "KEY_WOW64_32KEY", 0)  # 32 位客户端，必须读 WOW6432Node 视图
    try:
        src = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, MPLAY_PATH, 0, winreg.KEY_READ | wow32)
    except FileNotFoundError:
        print(f"HKLM\\{MPLAY_PATH} 不存在，没有可迁移的旧设置。")
        return
    except OSError as exc:
        print(f"[提示] 打开 HKLM\\{MPLAY_PATH} 失败（{exc}），跳过迁移。")
        return

    values: list[tuple[str, object, int]] = []
    i = 0
    while True:
        try:
            values.append(winreg.EnumValue(src, i))
        except OSError:
            break
        i += 1
    winreg.CloseKey(src)

    if not values:
        print(f"HKLM\\{MPLAY_PATH} 是空的，没有可迁移的值。")
        return

    try:
        dst = winreg.CreateKeyEx(
            winreg.HKEY_CURRENT_USER, MPLAY_PATH, 0, winreg.KEY_SET_VALUE | wow32
        )
    except OSError as exc:
        fail(f"创建 HKCU\\{MPLAY_PATH} 失败: {exc}")

    existing = set()
    j = 0
    while True:
        try:
            existing.add(winreg.EnumValue(dst, j)[0])
        except OSError:
            break
        j += 1

    copied = skipped = 0
    for name, value, vtype in values:
        if name in existing and not force:
            skipped += 1
            continue
        winreg.SetValueEx(dst, name, 0, vtype, value)
        copied += 1
    winreg.CloseKey(dst)

    print(
        f"迁移完成: 写入 HKCU\\{MPLAY_PATH} {copied} 个值"
        + (f"，跳过已存在 {skipped} 个（--force 可覆盖）" if skipped else "")
    )


def dump_registry() -> None:
    try:
        import winreg
    except ImportError:
        fail("当前 Python 不支持 winreg（仅 Windows 可用）")

    wow32 = getattr(winreg, "KEY_WOW64_32KEY", 0)
    for root, root_name in ((winreg.HKEY_LOCAL_MACHINE, "HKLM"), (winreg.HKEY_CURRENT_USER, "HKCU")):
        print(f"[{root_name}\\{MPLAY_PATH}]")
        try:
            key = winreg.OpenKey(root, MPLAY_PATH, 0, winreg.KEY_READ | wow32)
        except FileNotFoundError:
            print("  (不存在)")
            continue
        except OSError as exc:
            print(f"  (打不开: {exc})")
            continue
        i = 0
        count = 0
        while True:
            try:
                name, value, _type = winreg.EnumValue(key, i)
            except OSError:
                break
            count += 1
            if count <= 12:
                shown = value if not isinstance(value, bytes) else f"<{len(value)} bytes>"
                print(f"  {name} = {shown!r}")
            i += 1
        if count > 12:
            print(f"  ... 共 {count} 个值")
        elif count == 0:
            print("  (空)")
        winreg.CloseKey(key)


# ---------------------------------------------------------------------- 主流程

def inspect(data: bytes, secs) -> list[tuple[Site, int, int, int]]:
    """返回 [(site, 匹配起点, 根键字节偏移, 当前根键值)]。"""
    rows = []
    for site in SITES:
        start, imm_off = locate(secs, data, site)
        rows.append((site, start, imm_off, data[imm_off]))
    return rows


def verify(exe: Path) -> int:
    data = exe.read_bytes()
    secs = load_sections(data)
    print(f"文件: {exe}  ({len(data):,} 字节)")
    rows = inspect(data, secs)
    print()
    patched = 0
    for site, start, imm_off, value in rows:
        root = {0x02: "HKLM", 0x01: "HKCU"}.get(value, f"0x{value:02X}?")
        state = "已改 HKCU" if value == 0x01 else ("仍是 HKLM" if value == 0x02 else "异常")
        patched += value == 0x01
        print(
            f"  [{'OK' if value == 0x01 else '  '}] {site.desc}\n"
            f"        VA 0x{start + IMAGE_BASE:X}  文件偏移 0x{imm_off:X}  push {root} ({state})"
        )
    print()
    if patched == len(rows):
        print("结论: 三处均为 HKEY_CURRENT_USER，补丁已生效。")
    elif patched == 0:
        print("结论: 未打补丁，设置仍写 HKLM\\" + MPLAY_PATH + "。")
    else:
        print(f"结论: 部分打补丁（{patched}/{len(rows)}）——请重跑本脚本，两处 MPlay 必须同时改。")
    return 0 if patched == len(rows) else 1


def apply_patch(exe: Path, dry: bool) -> None:
    data = bytearray(exe.read_bytes())
    secs = load_sections(data)

    print(f"文件: {exe}  ({len(data):,} 字节)")
    print(f"模式: {'DRY-RUN（不写入）' if dry else '写入'}")
    print()

    plan = []
    for site, start, imm_off, value in inspect(data, secs):
        if value == 0x01:
            print(f"  [跳过] {site.desc}: 已是 HKEY_CURRENT_USER")
            continue
        if value != 0x02:
            fail(f"{site.desc}: 根键字节是 0x{value:02X}，既非 HKLM 也非 HKCU，拒绝修改")
        print(f"  [待改] {site.desc}")
        print(f"         VA 0x{start + IMAGE_BASE:X}  文件偏移 0x{imm_off:X}  push 80000002h -> 80000001h")
        plan.append((site, imm_off))

    if not plan:
        print("\n无需改动（三处均已是 HKCU）。")
        return

    if dry:
        print(f"\nDRY-RUN 结束，可打补丁 {len(plan)} 处，未写入文件。")
        return

    for _site, imm_off in plan:
        data[imm_off] = 0x01

    bak = exe.with_name(exe.name + BAK_SUFFIX)
    if not bak.exists():
        shutil.copy2(exe, bak)
        print(f"\n已创建原始备份: {bak.name}")
    else:
        print(f"\n保留已有备份: {bak.name}")

    try:
        exe.write_bytes(data)
    except PermissionError:
        fail(f"{exe.name} 正被占用（游戏是否在运行？）——请退出游戏后重试；备份已生成，可用 --restore 还原")

    print(f"[成功] 已修改 {len(plan)} 处，设置目录改为 HKCU\\{MPLAY_PATH}")
    print("       下一步: 用 --verify 复核；老设置可用 --migrate 从 HKLM 拷一次。")


def restore(exe: Path) -> None:
    bak = exe.with_name(exe.name + BAK_SUFFIX)
    if not bak.exists():
        fail(f"未找到备份: {bak}")
    shutil.copy2(bak, exe)
    print(f"[成功] 已从 {bak.name} 还原 {exe.name}。")


def main() -> None:
    ap = argparse.ArgumentParser(
        description="PopTag CA.exe 注册表根键 HKLM -> HKCU 补丁（普通用户可写设置）"
    )
    ap.add_argument("--exe", default=DEFAULT_EXE_PATH, help=f"CA.exe 路径（默认: {DEFAULT_EXE_PATH}）")
    ap.add_argument("--dry-run", action="store_true", help="只定位校验，不写入")
    ap.add_argument("--verify", action="store_true", help="只报告当前补丁状态")
    ap.add_argument("--restore", action="store_true", help="从 *.regbak 还原原始文件")
    ap.add_argument("--migrate", action="store_true", help="把 HKLM 旧设置拷贝到 HKCU")
    ap.add_argument("--dump", action="store_true", help="打印 HKLM/HKCU 两处的键值")
    ap.add_argument("--force", action="store_true", help="配合 --migrate：覆盖 HKCU 已存在的值")
    args = ap.parse_args()

    exe = Path(args.exe)

    if args.dump:
        dump_registry()
        return
    if args.migrate:
        migrate_registry(args.force)
        return

    if not exe.is_file():
        fail(f"目标文件不存在: {exe}")

    if args.restore:
        restore(exe)
    elif args.verify:
        sys.exit(verify(exe))
    else:
        apply_patch(exe, args.dry_run)


if __name__ == "__main__":
    main()
