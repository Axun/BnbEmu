#!/usr/bin/env python3
"""把 CA.exe 里「我的角色」读取点的 3 位掩码改成 5 位（允许 8..31）。

背景
----
CA.exe 0x006E50F0 = `sub_6E50F0`，客户端「本机玩家当前角色」的唯一读取点：

    add  ecx, 0x25          ; slot + 37
    imul ecx, ecx, 0xc      ; 12 * (slot + 37) = 12*slot + 444
    add  ecx, esi           ; CUDI + 444 + 12*slot
    call 0x44862D           ; 解出存进去的那个字节（op 20 第 12 栏写的值）
    movzx eax, al
    and  eax, 0x80000007    ; ← 只留低 3 位；编译器为 `(signed char)v & 7` 生成的符号扩展惯用法
    jns  ...
    dec  eax
    or   eax, 0xfffffff8
    inc  eax
    ret  4

因为 `movzx` 之后 bit31 恒为 0，`jns` 永远成立，实际效果就是 `al & 7`：
bomber id 25 → 1，于是名片/纸娃娃画成 id 1，op 73 也上报 1。

把这个 7 改成 0x1F（32 条 CBomberAttr 记录的全部下标）后：
  · 0..7   基础角色：不变（仍走「角色槽 → 卡」表，蓝妹妹 4→10 照旧）
  · 8, 9   C08/C09：修复（原来会显示 0/1）
  · 10..19 进阶角色：不变（客户端写入时已被 GetBaseCharacterBySkin 折回基础 4/7/…）
  · 20..31 N01..C24：修复（原来全部按 mod 8 显示）

为什么不用 `& 0xFF`：`sub_672024` 对 >7 的值原样返回，调用方直接
`412 * id + CBomberAttrMan`，不做上界检查；表只有 32 条，越界就是读坏内存。

用法
----
    python tools/patch_char_mask.py                 # 默认 dry-run，只读
    python tools/patch_char_mask.py --apply         # 备份后写入（F:\\Games\\PopTag\\CA.exe）
    python tools/patch_char_mask.py --apply --exe D:/other/CA.exe
    python tools/patch_char_mask.py --revert        # 从 .bak 还原
"""
from __future__ import annotations

import argparse
import shutil
import sys
import time
from pathlib import Path

# VA 0x6E510F 处的 `and eax, 0x80000007`，文件偏移 = VA - 0x400000（.text 与文件同偏移）
PATCH_OFFSET = 0x2E5110
ORIGINAL = 0x07
PATCHED = 0x1F
# 掩码前后各取几字节做指纹，避免补丁打到别的版本上
CONTEXT_BEFORE = bytes.fromhex("0f b6 c0 25")   # movzx eax, al / and eax, imm32 的 opcode
CONTEXT_AFTER = bytes.fromhex("00 00 80 79")    # imm32 高位 + jns

DEFAULT_EXE = Path(r"F:\Games\PopTag\CA.exe")


def check(data: bytearray, exe: Path) -> None:
    start = PATCH_OFFSET - len(CONTEXT_BEFORE)
    if data[start:PATCH_OFFSET] != CONTEXT_BEFORE or data[PATCH_OFFSET + 1:PATCH_OFFSET + 1 + len(CONTEXT_AFTER)] != CONTEXT_AFTER:
        raise SystemExit(
            f"[!] {exe} 的指纹不匹配，可能不是同一版本，已中止。\n"
            f"    期望 {CONTEXT_BEFORE.hex(' ')} [{data[PATCH_OFFSET]:02x}] {CONTEXT_AFTER.hex(' ')}\n"
            f"    实际 {data[start:PATCH_OFFSET + 5].hex(' ')}"
        )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--exe", type=Path, default=DEFAULT_EXE, help="CA.exe 路径")
    ap.add_argument("--apply", action="store_true", help="真正写入（默认只检查）")
    ap.add_argument("--revert", action="store_true", help="从 .bak 还原")
    args = ap.parse_args()

    exe: Path = args.exe
    if not exe.is_file():
        print(f"[!] 找不到 {exe}")
        return 2

    if args.revert:
        bak = exe.with_suffix(exe.suffix + ".bak")
        if not bak.is_file():
            print(f"[!] 没有备份文件 {bak}")
            return 2
        shutil.copy2(bak, exe)
        print(f"[ok] 已从 {bak.name} 还原 {exe}")
        return 0

    data = bytearray(exe.read_bytes())
    check(data, exe)

    cur = data[PATCH_OFFSET]
    print(f"文件      : {exe}")
    print(f"补丁位置  : 0x{PATCH_OFFSET:X}  (VA 0x6E510F + 1)")
    print(f"当前字节  : 0x{cur:02X}  (and eax, 0x800000{cur:02X})")
    print(f"目标字节  : 0x{PATCHED:02X}  (and eax, 0x800000{PATCHED:02X})")

    if cur == PATCHED:
        print("[=] 已经是打过补丁的状态，无需修改")
        return 0
    if cur != ORIGINAL:
        print(f"[!] 期望原始字节 0x{ORIGINAL:02X}，实际 0x{cur:02X}，已中止")
        return 2
    if not args.apply:
        print("[dry-run] 未写入。确认无误后加 --apply")
        return 0

    bak = exe.with_suffix(exe.suffix + f".bak")
    if not bak.is_file():
        shutil.copy2(exe, bak)
        print(f"[ok] 已备份到 {bak}")
    else:
        print(f"[=] 备份已存在，保留不覆盖：{bak}")

    data[PATCH_OFFSET] = PATCHED
    exe.write_bytes(data)

    verify = exe.read_bytes()
    ok = verify[PATCH_OFFSET] == PATCHED
    print(f"[{'ok' if ok else '!!'}] 写入后校验：0x{verify[PATCH_OFFSET]:02X}  ({time.strftime('%H:%M:%S')})")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
