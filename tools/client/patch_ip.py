#!/usr/bin/env python3
"""PopTag 客户端服务器 IP 批量替换补丁。

把客户端里硬编码的服务器地址（默认按 127.0.0.1 查找，即当前客户端现状）替换成新地址，
实际使用中用 --ip 指定目标（如局域网 192.168.50.4）。原理与补丁点详见
docs/client/client-server-address.md。

覆盖目标（都在 --game-dir 下，默认 F:\\Games\\PopTag）：
  - CA.exe        内置默认服务器表，ASCII 槽位 x6
  - NMCOSrv.exe   invite 回退 + LoadServerConfig From-Source 表，ASCII 槽位 x2
  - nmconew.dll   passport(47611) 会话服务器默认地址，ASCII 槽位 x1
                  + NMCO 服务器地址表，UTF-16LE 槽位 x8（2026-08-30 补：
                    passport 实际消费的是这张 UTF-16 表；此前只补 ASCII 导致
                    打过补丁后 47611 仍连远端、账号在远端校验）
  - nmcogame.dll  NMCO 服务器地址表另一份拷贝，UTF-16LE 槽位 x8（同日补）

槽位长度：二进制里是原版的定长 IPv4 槽——ASCII 16 字节、UTF-16LE 32 字节。
破解者把原版 15 字符的地址写短成旧 IP 后，槽尾那截本来就是零填充，所以新地址比旧的短只是
"用不完"，比旧的长则可以直接吃掉这些零：只要槽内剩余零够放下「新地址 + 终结符」就能原地写入，
文件长度不变、不移动字节、不破坏指针/对齐。因此 15 字符以内的任意 IPv4 都能打进去，不再要求
"不能比旧 IP 长"；只有紧贴下一个字符串的窄槽（NMCOSrv.exe 偏移 441152，后面就是 "local"）
放不下时会跳过该点位并提示（该点位只影响 NMCO invite 的连接类型日志）。

用法：
  python tools/client/patch_ip.py --ip 192.168.50.4                      # 127.0.0.1 -> 192.168.50.4
  python tools/client/patch_ip.py --ip 127.0.0.1 --old-ip 192.168.50.4   # 改回本机
  python tools/client/patch_ip.py --ip 1.2.3.4 --dry-run                 # 预览，不写文件
  python tools/client/patch_ip.py --scan-only                            # 全目录扫描旧 IP 残留
  python tools/client/patch_ip.py --restore                              # 从 *.ipbak 还原原始文件

首次打补丁时自动生成 <file>.ipbak 原始备份；已存在则不覆盖（始终保留最初版本）。
"""

import argparse
import shutil
import socket
import sys
from pathlib import Path

# 客户端二进制里当前硬编码的地址。破解版原值是 66.135.19.7，现已全部补成 127.0.0.1；
# 想从原版状态直接补丁时用 --old-ip 66.135.19.7 指定。
DEFAULT_OLD_IP = "127.0.0.1"
DEFAULT_NEW_IP = "127.0.0.1"
DEFAULT_GAME_DIR = r"F:\Games\PopTag"
BAK_SUFFIX = ".ipbak"
SCAN_MAX_BYTES = 64 * 1024 * 1024  # 超过此大小的素材包不参与残留扫描

# (相对路径, 预期 ASCII 出现次数, 说明, 文件缺失是否可接受)
ASCII_TARGETS = [
    ("CA.exe", 6, "内置默认服务器表 (.rdata 0x8BC0C8..0x8BC4B0)", False),
    ("NMCOSrv.exe", 2, "invite 回退 + LoadServerConfig From-Source 表", False),
    ("nmconew.dll", 1, "passport(47611) 会话服务器默认地址", False),
]

# (相对路径, 预期 UTF-16LE 出现次数, 说明)
# 2026-08-30 补: 全目录扫描发现旧 IP 以 UTF-16LE 形式存于两张 NMCO 服务器地址表
# （nmconew.dll @1328332 起、nmcogame.dll @241988 起，各 8 槽、槽宽 32 字节、NUL 结尾），
# passport(47611) 实际消费 nmconew.dll 这张表 —— 详见 docs/client/client-server-address.md
# 注: 破解版表里还夹着几个自带的 127.0.0.1 测试槽（nmcogame.dll 共 15 处），一并替换；
#     预期值只作下限校验，多出来的不报警。
UTF16_TARGETS = [
    ("nmconew.dll", 8, "passport(47611) NMCO UTF-16 服务器地址表"),
    ("nmcogame.dll", 8, "NMCO UTF-16 服务器地址表（另一份拷贝）"),
]


def fail(msg: str) -> None:
    print(f"[错误] {msg}")
    sys.exit(1)


def check_ip(ip: str, what: str) -> None:
    """严格校验标准点分十进制 IPv4（不接受 127.1 这类简写和带前导零的写法）。"""
    parts = ip.split(".")
    ok = len(parts) == 4 and all(
        p.isdigit() and p.isascii() and int(p) <= 255 and (p == "0" or p[0] != "0") for p in parts
    )
    if not ok:
        fail(f"{what} 不是标准点分十进制 IPv4: {ip!r}")


def find_offsets(data: bytes, needle: bytes) -> list[int]:
    offs: list[int] = []
    start = 0
    while True:
        i = data.find(needle, start)
        if i < 0:
            return offs
        offs.append(i)
        start = i + len(needle)


def patch_slots(
    data: bytes, old_token: bytes, new_raw: bytes, term: int
) -> tuple[bytes, int, list[tuple[int, int]]]:
    """把所有 old_token 槽位原地改写成 new_raw + 零终结符。

    槽位定长（term=1 为 ASCII、2 为 UTF-16LE 的终结符宽度），旧地址后面的零填充可以被新地址
    吃掉：需要多长就向槽尾借多少字节，借的必须是零，否则说明紧贴下一个字符串，该点位放不下。
    返回 (新数据, 成功处数, [(偏移, 还差的字节数), ...])。
    """
    buf = bytearray(data)
    patched = 0
    narrow: list[tuple[int, int]] = []
    for off in reversed(find_offsets(data, old_token)):
        need = len(new_raw) + term  # 值 + 终结符
        extra = need - len(old_token)  # >0 表示要从槽尾零填充借字节
        if extra > 0:
            gap = data[off + len(old_token) : off + len(old_token) + extra]
            if gap != b"\x00" * extra:
                narrow.append((off, extra))
                continue
            region = need
        else:
            region = len(old_token)  # 新值更短：余下的零仍充当填充/终结符
        buf[off : off + region] = new_raw + b"\x00" * (region - len(new_raw))
        patched += 1
    return bytes(buf), patched, narrow


def report_narrow(narrow: list[tuple[int, int]], new_ip: str, old_ip: str) -> None:
    for off, short in narrow:
        print(
            f"  [跳过] 偏移 {off}（{off:#x}）后紧跟其他字符串，槽内零填充还差 {short} 字节，"
            f"放不下 {new_ip}（{len(new_ip)} 字符）；该点位保留 {old_ip}"
        )


def backup_path(p: Path) -> Path:
    return p.with_name(p.name + BAK_SUFFIX)


def make_backup(p: Path) -> None:
    bak = backup_path(p)
    if not bak.exists():  # 只在首次创建，永远保留最初版本
        shutil.copy2(p, bak)
        print(f"  备份: {bak.name}")
    else:
        print(f"  备份已存在，保留最初版本: {bak.name}")


def patch_ascii(path: Path, old_ip: str, new_ip: str, dry: bool) -> tuple[int, int]:
    data = path.read_bytes()
    old = old_ip.encode("ascii") + b"\x00"
    new = new_ip.encode("ascii")
    count = data.count(old)
    if count == 0:
        if new + b"\x00" in data:
            print(f"  [跳过] 已是 {new_ip}，无需修改")
        else:
            print(f"  [警告] 未找到 {old_ip!r}（含 NUL）——文件可能与本文档描述不符")
        return 0, 0
    patched_bytes, n, narrow = patch_slots(data, old, new, 1)
    report_narrow(narrow, new_ip, old_ip)
    if n and not dry:
        make_backup(path)
        path.write_bytes(patched_bytes)
    print(f"  替换 {n} 处 ASCII 槽位" + (f"（另 {len(narrow)} 处空间不足跳过）" if narrow else ""))
    return n, len(narrow)


def patch_utf16(path: Path, old_ip: str, new_ip: str, dry: bool) -> tuple[int, int]:
    data = path.read_bytes()
    old = old_ip.encode("utf-16-le")  # 不含终结符，槽尾原有的零继续充当终结符
    new = new_ip.encode("utf-16-le")
    count = data.count(old)
    if count == 0:
        if new + b"\x00\x00" in data:
            print(f"  [跳过] UTF-16 表已是 {new_ip}，无需修改")
        else:
            print(f"  [警告] 未找到 {old_ip!r} 的 UTF-16LE 形式——文件可能与本文档描述不符")
        return 0, 0
    patched_bytes, n, narrow = patch_slots(data, old, new, 2)
    report_narrow(narrow, new_ip, old_ip)
    if n and not dry:
        make_backup(path)
        path.write_bytes(patched_bytes)
    print(f"  替换 {n} 处 UTF-16LE 槽位" + (f"（另 {len(narrow)} 处空间不足跳过）" if narrow else ""))
    return n, len(narrow)


def patch_text(path: Path, old_ip: str, new_ip: str, dry: bool) -> int:
    data = path.read_bytes()
    count = data.count(old_ip.encode("ascii"))
    if count == 0:
        print(f"  [跳过] 未找到 {old_ip}")
        return 0
    if not dry:
        make_backup(path)
        path.write_bytes(data.replace(old_ip.encode("ascii"), new_ip.encode("ascii")))
    print(f"  替换 {count} 处（文本直替，文件长度变化无影响）")
    return count


def apply(game_dir: Path, old_ip: str, new_ip: str, dry: bool) -> None:
    print(f"游戏目录: {game_dir}")
    print(f"替换    : {old_ip} -> {new_ip}")
    print(f"模式    : {'DRY-RUN（不写入）' if dry else '写入'}")
    print()
    narrow_total = 0
    for rel, expect, desc, optional in ASCII_TARGETS:
        p = game_dir / rel
        print(f"[{rel}] {desc}（预期 {expect} 处）")
        if not p.exists():
            if optional:
                print("  [提示] 文件不存在，跳过（该项只为一致性，不影响连接）")
                continue
            fail(f"找不到 {p}")
        if p.suffix == ".cfg":
            n = patch_text(p, old_ip, new_ip, dry)
        else:
            n, narrow = patch_ascii(p, old_ip, new_ip, dry)
            narrow_total += narrow
            if 0 < n + narrow < expect:  # 找到的点位（含空间不足跳过的）仍少于预期才值得提醒
                print(f"  [警告] 实际 {n + narrow} 处 < 预期 {expect} 处（全文扫描未见其他消费者，通常无害）")
    for rel, expect, desc in UTF16_TARGETS:
        p = game_dir / rel
        print(f"[{rel}] {desc}（预期 ≥{expect} 处）")
        if not p.exists():
            fail(f"找不到 {p}")
        n, narrow = patch_utf16(p, old_ip, new_ip, dry)
        narrow_total += narrow
        if 0 < n + narrow < expect:
            print(f"  [警告] 实际 {n + narrow} 处 < 预期 {expect} 处（全文扫描未见其他消费者，通常无害）")
    if dry:
        print("DRY-RUN 结束，未写入任何文件。去掉 --dry-run 正式应用。")
        return
    print("完成。校验残留：")
    leftovers = scan(game_dir, old_ip, quiet=True)
    if leftovers:
        print("  仍包含旧 IP 的文件（.ipbak 备份除外）:")
        for f in leftovers:
            print(f"    {f}")
        if narrow_total:
            print(f"  ↑ 上面提示过空间不足的 {narrow_total} 个窄槽会留在原状，通常无影响；其余请人工确认。")
        else:
            print("  ↑ 若为上面目标之外的文件，请人工确认后再处理。")
    else:
        print("  无残留，全部替换成功。")


def scan(game_dir: Path, old_ip: str, quiet: bool = False) -> list[Path]:
    """全目录扫描旧 IP 残留（ASCII / UTF-16LE，另对 *.ssd 检查网络序 4 字节），跳过备份。

    网络序 4 字节只在 ssi 封装（*.ssd）里才是地址，普通素材包里 `7f 00 00 01` 之类纯属巧合，
    故不做全文件匹配；>64MiB 的素材包（.idd 等）不可能存 IP 字符串，直接跳过以省时间。
    """
    hits: list[Path] = []
    old_ascii = old_ip.encode("ascii")
    old_utf16 = old_ip.encode("utf-16-le")
    old_bin = socket.inet_aton(old_ip)
    skipped_big = 0
    for p in sorted(game_dir.rglob("*")):
        if not p.is_file() or p.name.endswith(BAK_SUFFIX):
            continue
        try:
            if p.stat().st_size > SCAN_MAX_BYTES:
                skipped_big += 1
                continue
            data = p.read_bytes()
        except OSError:
            continue
        marks = []
        if old_ascii in data:
            marks.append(f"ASCII x{data.count(old_ascii)}")
        if old_utf16 in data:
            marks.append(f"UTF-16LE x{data.count(old_utf16)}")
        if p.suffix.lower() == ".ssd" and old_bin in data:
            marks.append(f"网络序 x{data.count(old_bin)}")
        if marks:
            hits.append(p)
            if not quiet:
                print(f"  {p.relative_to(game_dir)}  ({', '.join(marks)})")
    if skipped_big and not quiet:
        print(f"  [提示] 跳过 {skipped_big} 个 >{SCAN_MAX_BYTES // (1024 * 1024)}MiB 的素材包（不会存 IP 字符串）")
    return hits


def restore(game_dir: Path) -> None:
    n = 0
    for bak in sorted(game_dir.rglob("*" + BAK_SUFFIX)):
        orig = bak.with_name(bak.name[: -len(BAK_SUFFIX)])
        shutil.copy2(bak, orig)
        print(f"还原 {orig.relative_to(game_dir)} <- {bak.name}")
        n += 1
    print(f"共还原 {n} 个文件。备份文件保留在原地，可再次还原。")


def main() -> None:
    ap = argparse.ArgumentParser(description="PopTag 客户端服务器 IP 批量替换补丁")
    ap.add_argument("--ip", default=DEFAULT_NEW_IP, help=f"目标 IP（默认 {DEFAULT_NEW_IP}，切换地址时必填）")
    ap.add_argument("--old-ip", default=DEFAULT_OLD_IP, help=f"客户端当前 IP（默认 {DEFAULT_OLD_IP}）")
    ap.add_argument("--game-dir", default=DEFAULT_GAME_DIR, help=f"游戏目录（默认 {DEFAULT_GAME_DIR}）")
    ap.add_argument("--dry-run", action="store_true", help="只预览不写入")
    ap.add_argument("--scan-only", action="store_true", help="只扫描旧 IP 残留")
    ap.add_argument("--restore", action="store_true", help="从 .ipbak 备份还原")
    args = ap.parse_args()

    game_dir = Path(args.game_dir)
    if not game_dir.is_dir():
        fail(f"游戏目录不存在: {game_dir}")

    if args.restore:
        restore(game_dir)
        return
    if args.scan_only:
        check_ip(args.old_ip, "旧 IP")
        print(f"扫描 {game_dir} 中的 {args.old_ip} 残留（跳过 *{BAK_SUFFIX}）:")
        hits = scan(game_dir, args.old_ip)
        print(f"\n共 {len(hits)} 个文件。")
        return

    check_ip(args.old_ip, "旧 IP")
    check_ip(args.ip, "新 IP")
    if args.old_ip == args.ip:
        print(f"旧 IP 与新 IP 都是 {args.ip}，没有需要替换的内容。")
        print("切换地址请用 --ip 指定目标，例如: python tools/client/patch_ip.py --ip 192.168.50.4")
        return
    apply(game_dir, args.old_ip, args.ip, args.dry_run)


if __name__ == "__main__":
    main()
