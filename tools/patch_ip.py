#!/usr/bin/env python3
"""PopTag 客户端服务器 IP 批量替换补丁。

把破解版客户端里硬编码的 66.135.19.7 全部替换为新 IP（默认 127.0.0.1）。
原理与补丁点详见 docs/client-server-address.md。

覆盖目标（都在 --game-dir 下，默认 F:\\Games\\PopTag）：
  - CA.exe        内置默认服务器表，ASCII 槽位 x6
  - NMCOSrv.exe   invite 回退 + LoadServerConfig From-Source 表，ASCII 槽位 x2
  - nmconew.dll   passport(47611) 会话服务器默认地址，ASCII 槽位 x1
  - multisvr.ssd  服务器列表缓存，偏移 0x2E 的 4 字节网络序 IP（端口 3838 不动）
  - NMDATA\\Server.cfg  纯文本（实际不生效，仅一致性）

用法：
  python tools/patch_ip.py                  # 应用补丁（默认 127.0.0.1）
  python tools/patch_ip.py --ip 1.2.3.4     # 指定其他 IP
  python tools/patch_ip.py --dry-run        # 预览，不写文件
  python tools/patch_ip.py --scan-only      # 全目录扫描旧 IP 残留
  python tools/patch_ip.py --restore        # 从 *.ipbak 还原原始文件

首次打补丁时自动生成 <file>.ipbak 原始备份；已存在则不覆盖（始终保留最初版本）。
二进制替换采用等长 + \\0 填充，不移动字节、不破坏指针；因此新 IP 长度不能超过旧 IP。
"""

import argparse
import shutil
import socket
import sys
from pathlib import Path

OLD_IP = "66.135.19.7"
DEFAULT_NEW_IP = "127.0.0.1"
DEFAULT_GAME_DIR = r"F:\Games\PopTag"
BAK_SUFFIX = ".ipbak"

# (相对路径, 预期 ASCII 出现次数, 说明)
ASCII_TARGETS = [
    ("CA.exe", 6, "内置默认服务器表 (.rdata 0x8BC0C8..0x8BC4B0)"),
    ("NMCOSrv.exe", 2, "invite 回退 + LoadServerConfig From-Source 表"),
    ("nmconew.dll", 1, "passport(47611) 会话服务器默认地址"),
    (r"NMDATA\Server.cfg", 1, "纯文本配置（实际不生效，仅一致性）"),
]

# multisvr.ssd 中 4 字节网络序 IP 的偏移（0x32 起是端口 3838，不动）
SSD_TARGET = ("multisvr.ssd", 0x2E)


def fail(msg: str) -> None:
    print(f"[错误] {msg}")
    sys.exit(1)


def old_ascii_token() -> bytes:
    """二进制槽位的字节模式：IP 字符串 + 结尾 NUL。"""
    return OLD_IP.encode("ascii") + b"\x00"


def new_ascii_token(new_ip: str) -> bytes:
    """等长替换：新 IP + NUL 填充到旧 token 长度。"""
    old = old_ascii_token()
    raw = new_ip.encode("ascii")
    assert len(raw) <= len(old) - 1, "调用方已校验"
    return raw + b"\x00" * (len(old) - len(raw))


def check_new_ip(new_ip: str) -> None:
    try:
        if socket.inet_aton(new_ip) != socket.inet_aton(socket.inet_ntoa(socket.inet_aton(new_ip))):
            fail(f"IP 格式不是标准点分十进制: {new_ip}")
    except OSError:
        fail(f"IP 格式非法: {new_ip}")
    if len(new_ip.encode("ascii")) > len(OLD_IP):
        fail(
            f"新 IP {new_ip}（{len(new_ip)} 字符）比旧 IP {OLD_IP}（{len(OLD_IP)} 字符）长，"
            f"无法等长原地替换；请改用更短的地址（如 127.0.0.1、192.168.x.x）。"
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


def patch_ascii(path: Path, new_ip: str, dry: bool) -> int:
    data = path.read_bytes()
    old, new = old_ascii_token(), new_ascii_token(new_ip)
    count = data.count(old)
    if count == 0:
        if new_ip.encode("ascii") in data:
            print(f"  [跳过] 已是 {new_ip}，无需修改")
            return 0
        print(f"  [警告] 未找到 {OLD_IP!r}（含 NUL）——文件可能与本文档描述不符")
        return 0
    if not dry:
        make_backup(path)
        path.write_bytes(data.replace(old, new))
    print(f"  替换 {count} 处 ASCII 槽位")
    return count


def patch_ssd(path: Path, new_ip: str, dry: bool) -> int:
    data = path.read_bytes()
    _, off = SSD_TARGET
    old_bin = socket.inet_aton(OLD_IP)
    if data[off : off + 4] == old_bin:
        if not dry:
            make_backup(path)
            b = bytearray(data)
            b[off : off + 4] = socket.inet_aton(new_ip)
            path.write_bytes(bytes(b))
        print(f"  替换偏移 {off:#x} 处 4 字节网络序 IP（端口 3838 保留）")
        return 1
    if socket.inet_aton(new_ip) in data:
        print(f"  [跳过] 已是 {new_ip}，无需修改")
        return 0
    print(f"  [警告] 偏移 {off:#x} 处不是 {OLD_IP} 的网络序字节，multisvr.ssd 可能已被 op1 覆盖或格式有变")
    return 0


def patch_text(path: Path, new_ip: str, dry: bool) -> int:
    data = path.read_bytes()
    count = data.count(OLD_IP.encode("ascii"))
    if count == 0:
        print(f"  [跳过] 未找到 {OLD_IP}")
        return 0
    if not dry:
        make_backup(path)
        path.write_bytes(data.replace(OLD_IP.encode("ascii"), new_ip.encode("ascii")))
    print(f"  替换 {count} 处（文本直替，文件长度变化无影响）")
    return count


def apply(game_dir: Path, new_ip: str, dry: bool) -> None:
    print(f"游戏目录: {game_dir}")
    print(f"目标 IP : {OLD_IP} -> {new_ip}")
    print(f"模式    : {'DRY-RUN（不写入）' if dry else '写入'}")
    print()
    for rel, expect, desc in ASCII_TARGETS:
        p = game_dir / rel
        print(f"[{rel}] {desc}（预期 {expect} 处）")
        if not p.exists():
            fail(f"找不到 {p}")
        n = patch_ascii(p, new_ip, dry) if p.suffix != ".cfg" else patch_text(p, new_ip, dry)
        if 0 < n != expect:
            print(f"  [警告] 实际 {n} 处 != 预期 {expect} 处（全文扫描未见其他消费者，通常无害）")
    rel, _ = SSD_TARGET
    p = game_dir / rel
    print(f"[{rel}] 服务器列表缓存")
    if p.exists():
        patch_ssd(p, new_ip, dry)
    else:
        print("  [提示] 不存在（首次登录后才会生成，届时如残留旧 IP 可再跑一次本脚本）")
    print()
    if dry:
        print("DRY-RUN 结束，未写入任何文件。去掉 --dry-run 正式应用。")
    else:
        print("完成。校验残留：")
        leftovers = scan(game_dir, quiet=True)
        if leftovers:
            print("  仍包含旧 IP 的文件（.ipbak 备份除外）:")
            for f in leftovers:
                print(f"    {f}")
            print("  ↑ 若为上面目标之外的文件，请人工确认后再处理。")
        else:
            print("  无残留，全部替换成功。")


def scan(game_dir: Path, quiet: bool = False) -> list[Path]:
    """全目录扫描旧 IP 残留（ASCII 或 multisvr.ssd 的网络序字节），跳过备份。"""
    hits: list[Path] = []
    old_ascii = OLD_IP.encode("ascii")
    old_bin = socket.inet_aton(OLD_IP)
    for p in sorted(game_dir.rglob("*")):
        if not p.is_file() or p.name.endswith(BAK_SUFFIX):
            continue
        try:
            data = p.read_bytes()
        except OSError:
            continue
        if old_ascii in data or old_bin in data:
            hits.append(p)
            if not quiet:
                marks = []
                if old_ascii in data:
                    marks.append(f"ASCII x{data.count(old_ascii)}")
                if old_bin in data:
                    marks.append(f"网络序 x{data.count(old_bin)}")
                print(f"  {p.relative_to(game_dir)}  ({', '.join(marks)})")
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
    ap.add_argument("--ip", default=DEFAULT_NEW_IP, help=f"新 IP（默认 {DEFAULT_NEW_IP}）")
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
        print(f"扫描 {game_dir} 中的 {OLD_IP} 残留（跳过 *{BAK_SUFFIX}）:")
        hits = scan(game_dir)
        print(f"\n共 {len(hits)} 个文件。")
        return

    check_new_ip(args.ip)
    apply(game_dir, args.ip, args.dry_run)


if __name__ == "__main__":
    main()
