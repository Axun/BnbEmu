#!/usr/bin/env python3
"""PopTag 客户端「AI 阵亡也上报」补丁（死亡音 0x2A 的前提）。

背景（逆向见 docs/agents-notes-battle.md 第 4.2 条）
--------------------------------------------------
客户端 CGOGameBomber 的本地阵亡函数 `0x0046DAE4` 反汇编：

    push 0x20                          ; 46DAE4
    mov  eax, 0x8006F1
    call ...
    lea  ecx, [esi+0x2AC4]             ; 阵亡倒计时 (+10948)
    push 0
    call 0x0044B4D1                    ; 清零倒计时
    mov  ecx, esi
    call 0x0045FBD3                    ; IsLocalPlayerIndex_0or1: 0 <= [+0x4D0] < 2
    test eax, eax                      ; 46DB06
    je   0x0046DB5C                    ; 46DB08  74 52  ← 不通过就整个跳过发包
    ... 构造 C→S op154 action=5 ...

`+0x4D0` 是 `m_selfidx`（调试串 0x892838 "m_selfidx = "）：AI bomber 恒为 -1，
于是**电脑对手阵亡时客户端一个包都不发** —— 服务端既拿不到阵亡时刻，也推不出死亡名单
（op146 state=5），而全 exe 里死亡音 0x2A 只在 op146 的 case 4/5 收尾播放
（`sub_4DEDAD(..., 42, ...)`，仅 `0x0054DDD1` 两处）。结果就是「杀 AI 没声音」。

实战日志也印证：AI 被炸那一秒只有 `op112 action=0x40`（碰到水泡），随后 op154 只剩
action 1/4，**从未出现过 action=2（被困）或 5（阵亡）**。

补丁
----
把 `0x0046DB08` 的 `74 52`（je）替换成 `90 90`（nop nop）：

  · 闸门不再跳过 → 每个 bomber（含 AI）阵亡都发 C→S op154 action=5；
  · 服务端 `Room.onBomberDie` 收到后广播 S→C op159 + op146 state=5
    → 客户端 `0x54E0B8` 摘除 + 刷 UI + 播 0x2A，**每个 AI 死都有一声，且时机精确**；
  · 对人类玩家行为零变化（他们本来就过闸门）。

副作用：与人类完全同构（阵亡上报 + 159 + 名单），这正是官方客户端的等价行为；
单人练习、僵尸模式等所有含 AI 的模式一并生效；不触碰协议、不改校验。

用法
----
  python tools/patch_ai_die.py                 # 应用（首次自动备份 CA.exe.diebak）
  python tools/patch_ai_die.py --dry-run       # 只看改哪几个字节
  python tools/patch_ai_die.py --verify        # 校验当前状态
  python tools/patch_ai_die.py --restore       # 从 CA.exe.diebak 还原

⚠️ 打补丁前请关闭游戏客户端（文件被占用时写入会失败）。
"""

import argparse
import shutil
import struct
import sys
from pathlib import Path

DEFAULT_EXE = r"F:\Games\PopTag\CA.exe"
BAK_SUFFIX = ".diebak"

# 补丁点：虚拟地址 / 原始字节 / 补丁后字节 / 说明
PATCH_VA = 0x0046DB08
ORIG = bytes([0x74, 0x52])   # je 0x0046DB5C
PATCHED = bytes([0x90, 0x90])  # nop nop


def load(path: Path):
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
    ap = argparse.ArgumentParser(description="给 CA.exe 打「AI 阵亡也上报」补丁")
    ap.add_argument("--exe", default=DEFAULT_EXE, help=f"CA.exe 路径（默认 {DEFAULT_EXE}）")
    ap.add_argument("--dry-run", action="store_true", help="只预览，不写文件")
    ap.add_argument("--verify", action="store_true", help="只校验当前状态")
    ap.add_argument("--restore", action="store_true", help="从 .diebak 还原")
    args = ap.parse_args()

    exe = Path(args.exe)
    if not exe.is_file():
        print(f"[x] 找不到 {exe}")
        return 1
    bak = exe.with_suffix(exe.suffix + BAK_SUFFIX)

    if args.restore:
        if not bak.is_file():
            print(f"[x] 没有备份 {bak}，无法还原")
            return 1
        shutil.copy2(bak, exe)
        print(f"[+] 已从 {bak.name} 还原 {exe.name}")
        return 0

    data, image_base, sections = load(exe)
    off = va_to_off(PATCH_VA, image_base, sections)
    if off is None:
        print(f"[x] 0x{PATCH_VA:08X} 不在任何节内，PE 布局异常")
        return 1
    cur = bytes(data[off:off + 2])

    state = "patched" if cur == PATCHED else "original" if cur == ORIG else "unknown"
    print(f"[i] {exe}")
    print(f"    imagebase = 0x{image_base:08X}")
    print(f"    0x{PATCH_VA:08X} -> 文件偏移 0x{off:X}，当前字节 {cur.hex(' ')} ({state})")

    if args.verify or args.dry_run:
        if state == "unknown":
            print("[!] 既不是原始字节也不是补丁字节 —— 客户端版本/已装其它补丁，未做任何修改")
            return 2
        print("[i] 无需修改" if state == "patched" else "[i] 尚未打补丁")
        return 0

    if state == "unknown":
        print("[!] 字节不匹配（客户端版本不符或已装其它补丁），拒绝修改")
        return 2
    if state == "patched":
        print("[i] 已经是补丁状态，无需重复处理")
        return 0

    if not bak.is_file():
        shutil.copy2(exe, bak)
        print(f"[+] 已备份 -> {bak.name}")
    else:
        print(f"[i] 已存在备份 {bak.name}（保留最初版本，不覆盖）")

    data[off:off + 2] = PATCHED
    exe.write_bytes(data)
    print(f"[+] 已打补丁: {ORIG.hex(' ')} -> {PATCHED.hex(' ')}")
    print("    AI 阵亡现在会发 C→S op154 action=5，服务端据此广播 op159 + op146 state=5，")
    print("    客户端播 0x2A 死亡音（每个 AI 死都有一声）。")
    print("    回滚: python tools/patch_ai_die.py --restore")
    return 0


if __name__ == "__main__":
    sys.exit(main())
