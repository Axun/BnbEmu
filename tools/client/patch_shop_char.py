#!/usr/bin/env python3
"""让商城（CShopStageNew）的角色预览/选角接受 0..31 全部角色 id。

背景
----
`tools/patch_char_mask.py` 只修了「本机当前角色」的读取点 `CUserDynamicInfo_GetBomber`
（`0x6E50F0`，`CUDI + 12*slot + 444 & 7` → `& 0x1F`），所以「我的角色」/名片能显示 8..31。
商城还有一套自己的白名单，进商城时会把不认识的 id 清成 0（Dao）：

  CShopStage_OnEnter (0x5D1106)
    [shop+0x2F5C] = CUserDynamicInfo_GetBomber(CUDI, slot)   ; 25
    sub_5CC4A5(..., [shop+0x2F5C], ...)                      ; 建预览对象
    CShopStage_SelectBomber(shop, [shop+0x2F5C])             ; 选角/初始化（Shop/S_DlgSelecChar）
      CUserDynamicInfo_SetBomber(CUDI, slot, id)             ; CUDI 里写的仍是真实 25
      if (CBomberAttr_IsNormalBomber(man, id))               ; 0 <= id < NormalBomberLimit
          id = sub_672024(CUserItemMan, slot, id)            ; 0..7 走角色卡映射
      else if (!CBomberAttr_IsSkinBomber(man, id))           ; 10 <= id < 10+皮肤数
          id = 0                                             ; ← 25 在这里被清成 Dao
      ...
      CShopStage_SetBomberPreview(shop, id, ...)             ; 真正换预览模型
      ...
  CShopStage_SetBomberPreview (0x5CE3D4)                     ; 412*id + CBomberAttr + 0x6C
    if (!CBomberAttr_IsNormalBomber(man, id) && !CBomberAttr_IsSkinBomber(man, id))
        id = [shop+0x773C]                                   ; 兜底槽初始 0 → Dao
    sub_458322(view, 412*id + CBomberAttr + 0x6C)

运行时实测（CA.exe EN v18，`CBomberAttr` 的加密计数器）：
  CBomberAttr_GetNormalBomberLimit(0x4D65D3) = 32 - 10(cat3) - 9(cat2) - 0(cat1) - 2(cat4) = 11 → 0..10
  CBomberAttr_IsSkinBomber(0x51969C) = [10, 10 + 9)                                                 → 10..18
所以 id 19（C22，attr 分类 5）和 20..31 在商城里一律回退 0；
8/9 因 <11 反而正常，10..18 因落在皮肤区正常。
CUDI 里始终是真实 id，因此「我的角色」正常、商城只坏显示——与用户观察一致。

补丁
----
把两处白名单判定换成 `0 <= id < 32`，其余逻辑（0..7 的角色卡映射、非法 id 的兜底）保持不变：

  A. VA 0x5CE3E2（26B，CShopStage_SetBomberPreview）
       56                push esi
       e8 98 b2 f4 ff    call CBomberAttr_IsNormalBomber
       85 c0             test eax,eax
       75 1d             jne 0x5CE409
       8b 0d 4c 3c 99 00 mov  ecx,[0x993C4C]
       56                push esi
       e8 a4 b2 f4 ff    call CBomberAttr_IsSkinBomber
       85 c0             test eax,eax
       75 0d             jne 0x5CE409
     →
       83 fe 20          cmp esi, 0x20
       72 22             jb  0x5CE409        ; 0..31 直接用
       eb 13             jmp 0x5CE3FC        ; 其余走原兜底（-1 仍兜底）
       90 × 19

  B. VA 0x5CEAA0（15B，CShopStage_SelectBomber）
       8b 0d 4c 3c 99 00 mov  ecx,[0x993C4C]
       e8 f1 ab f4 ff    call CBomberAttr_IsSkinBomber
       85 c0             test eax,eax
       75 0e             jne 0x5CEABD
     →
       83 c4 04          add esp, 4          ; ⚠️ 先吃掉 0x5CEA88 那个 push edi
       83 ff 20          cmp edi, 0x20
       72 15             jb  0x5CEABD        ; 0..31 直接用
       eb 05             jmp 0x5CEAAF        ; 其余走原清零逻辑
       90 × 5

   ⚠️ `add esp, 4` 不能省：0x5CEA88 的 `push edi` 原本由 `call CBomberAttr_IsSkinBomber`
   的 `ret 4` 吃掉；直接跳过调用会让 SEH 收尾（0x71DC24 的 pop edi/esi/ebx）整体错位，
   返回时 EBX 被弹成 0，调用方 `CShopStage_OnEnter` 接着 `CShopStage_ApplyBomber(this=0)`
   必崩（实测崩在 0x46BFE1，this=0x3520 = 0x2F74+0x5AC）。

  第一段 `CBomberAttr_IsNormalBomber` 分支（0..10 → sub_672024 角色卡映射）原样保留，
  避免改变基础角色的已装备外观解析。

用法
----
    python tools/client/patch_shop_char.py            # 默认 dry-run，只读
    python tools/client/patch_shop_char.py --apply    # 备份后写入 F:\\Games\\PopTag\\CA.exe
    python tools/client/patch_shop_char.py --apply --exe D:/other/CA.exe
    python tools/client/patch_shop_char.py --revert   # 从 CA.exe.shopbak 还原
    python tools/client/patch_shop_char.py --verify   # 只校验补丁状态

⚠️ 打补丁前请关闭游戏客户端（运行中的 exe 被映像节占用，无法写入）。
⚠️ 本补丁只影响商城舞台；个人交易市场（UserMarket，0x6A7823 / 0x6A7B99）有同构代码，
   目前未纳入，若市场也出现同样问题可按相同思路处理。
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

DEFAULT_EXE = Path(r"F:\Games\PopTag\CA.exe")
BAK_SUFFIX = ".shopbak"

# (名称, 文件偏移 = VA - 0x400000, 原始字节, 补丁后字节)
PATCHES: list[tuple[str, int, bytes, bytes]] = [
    (
        "CShopStage_SetBomberPreview 预览白名单",
        0x1CE3E2,
        bytes.fromhex(
            "56 e8 98 b2 f4 ff 85 c0 75 1d "
            "8b 0d 4c 3c 99 00 56 e8 a4 b2 f4 ff 85 c0 75 0d"
        ),
        bytes.fromhex("83 fe 20 72 22 eb 13") + b"\x90" * 19,
    ),
    (
        "CShopStage_SelectBomber 选角白名单",
        0x1CEAA0,
        bytes.fromhex("8b 0d 4c 3c 99 00 e8 f1 ab f4 ff 85 c0 75 0e"),
        bytes.fromhex("83 c4 04 83 ff 20 72 15 eb 05") + b"\x90" * 5,
    ),
]

# 与 patch_char_mask.py 的补丁点（VA 0x6E510F + 1）配合使用
CHAR_MASK_OFFSET = 0x2E5110


def fingerprint(data: bytes, exe: Path) -> None:
    for name, off, old, _ in PATCHES:
        cur = data[off:off + len(old)]
        if cur != old:
            raise SystemExit(
                f"[!] {exe} 在 {name} 处指纹不匹配，可能不是同一版本或已打过其它补丁，已中止。\n"
                f"    偏移 0x{off:X}\n"
                f"    期望 {old.hex(' ')}\n"
                f"    实际 {cur.hex(' ')}"
            )


def status(data: bytes) -> list[bool]:
    return [data[off:off + len(new)] == new for _, off, _, new in PATCHES]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--exe", type=Path, default=DEFAULT_EXE, help="CA.exe 路径")
    ap.add_argument("--apply", action="store_true", help="真正写入（默认只检查）")
    ap.add_argument("--revert", action="store_true", help="从 .shopbak 还原")
    ap.add_argument("--verify", action="store_true", help="只打印补丁状态")
    args = ap.parse_args()

    exe: Path = args.exe
    if not exe.is_file():
        print(f"[!] 找不到 {exe}")
        return 2

    if args.revert:
        bak = exe.with_suffix(exe.suffix + BAK_SUFFIX)
        if not bak.is_file():
            print(f"[!] 没有备份文件 {bak}")
            return 2
        shutil.copy2(bak, exe)
        print(f"[ok] 已从 {bak.name} 还原 {exe}")
        return 0

    data = exe.read_bytes()

    if args.verify:
        print(f"文件      : {exe}")
        for (name, off, old, new), done in zip(PATCHES, status(data)):
            cur = data[off:off + len(new)]
            state = "已打" if done else ("未打" if cur == old else "未知")
            print(f"  [{state}] {name} @ 0x{off:X}  {cur.hex(' ')}")
        cm = data[CHAR_MASK_OFFSET]
        print(f"  [{'已打' if cm == 0x1F else '未打'}] patch_char_mask @ 0x{CHAR_MASK_OFFSET:X}  {cm:02X}")
        return 0

    fingerprint(data, exe)

    done = status(data)
    print(f"文件      : {exe}")
    for (name, off, old, new), ok in zip(PATCHES, done):
        print(f"  {name}")
        print(f"    偏移 0x{off:X} (VA 0x{off + 0x400000:X})")
        print(f"    原始 {old.hex(' ')}")
        print(f"    目标 {new.hex(' ')}")
        print(f"    当前 {'已是目标状态' if ok else '待修改'}")
    cm = data[CHAR_MASK_OFFSET]
    if cm != 0x1F:
        print(f"[!] 提醒：patch_char_mask 未打（0x{CHAR_MASK_OFFSET:X} = 0x{cm:02X}）。")
        print("    不打它的话 CUDI 读取仍会 &7，商城最多只能看到 0..7，本补丁无意义。")

    if all(done):
        print("[=] 两个补丁点都已是目标状态，无需修改")
        return 0
    if not args.apply:
        print("[dry-run] 未写入。确认无误后加 --apply")
        return 0

    bak = exe.with_suffix(exe.suffix + BAK_SUFFIX)
    if not bak.is_file():
        shutil.copy2(exe, bak)
        print(f"[ok] 已备份到 {bak}")
    else:
        print(f"[=] 备份已存在，保留不覆盖：{bak}")

    for name, off, old, new in PATCHES:
        data = bytearray(data)
        data[off:off + len(new)] = new
        data = bytes(data)

    exe.write_bytes(data)

    check = exe.read_bytes()
    ok = all(status(check))
    print(f"[{'ok' if ok else '!!'}] 写入后校验：{'全部通过' if ok else '失败'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
