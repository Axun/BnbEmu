#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PopTag CA.exe 去除管理员权限补丁。

原理：
  将 CA.exe PE 内置 RT_MANIFEST 中的:
    level="requireAdministrator"
  替换为等长对齐的:
    level="asInvoker"
  使游戏以普通用户权限（asInvoker）启动，不再弹出 UAC 提权提示。

用法：
  python tools/patch_noadmin.py             # 应用补丁
  python tools/patch_noadmin.py --restore   # 从备份还原
  python tools/patch_noadmin.py --dry-run   # 仅检查，不写入
"""

import argparse
import shutil
import sys
from pathlib import Path

# Ensure UTF-8 output on Windows consoles
if sys.stdout.encoding != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")

DEFAULT_EXE_PATH = r"F:\Games\PopTag\CA.exe"
BAK_SUFFIX = ".adminbak"

OLD_TARGET = b'            <requestedExecutionLevel level="requireAdministrator" uiAccess="false"></requestedExecutionLevel>'
NEW_TARGET = b'                       <requestedExecutionLevel level="asInvoker" uiAccess="false"></requestedExecutionLevel>'


def patch_ca_exe(exe_path: Path, dry_run: bool = False) -> None:
    if not exe_path.is_file():
        print(f"[错误] 目标文件不存在: {exe_path}")
        sys.exit(1)

    data = exe_path.read_bytes()
    if NEW_TARGET in data:
        print(f"[提示] {exe_path.name} 已经是普通用户权限 (asInvoker)，无需修改。")
        return

    if OLD_TARGET not in data:
        if b"requireAdministrator" in data:
            print("[警告] 找到 requireAdministrator 但格式与预期有细微差异，尝试宽松替换...")
            old_kw = b'level="requireAdministrator"'
            new_kw = b'level="asInvoker"          '
            if old_kw in data:
                new_data = data.replace(old_kw, new_kw, 1)
            else:
                print("[错误] 未能定位清单替换点。")
                sys.exit(1)
        else:
            print(f"[提示] 未在 {exe_path.name} 中检测到 requireAdministrator。")
            return
    else:
        new_data = data.replace(OLD_TARGET, NEW_TARGET, 1)

    assert len(new_data) == len(data), "补丁数据长度必须与原文件严格一致"

    if dry_run:
        print(f"[DRY-RUN] 成功定位 Manifest，可将 requireAdministrator 替换为 asInvoker（未写入）。")
        return

    # 备份
    bak_path = exe_path.with_name(exe_path.name + BAK_SUFFIX)
    if not bak_path.exists():
        shutil.copy2(exe_path, bak_path)
        print(f"已创建原始备份: {bak_path.name}")
    else:
        print(f"保留已有备份: {bak_path.name}")

    exe_path.write_bytes(new_data)
    print(f"[成功] {exe_path.name} 已成功修改为普通用户权限 (asInvoker)！")


def restore_ca_exe(exe_path: Path) -> None:
    bak_path = exe_path.with_name(exe_path.name + BAK_SUFFIX)
    if not bak_path.exists():
        print(f"[错误] 未找到备份文件: {bak_path}")
        sys.exit(1)

    shutil.copy2(bak_path, exe_path)
    print(f"[成功] 已从 {bak_path.name} 还原 {exe_path.name}。")


def main() -> None:
    parser = argparse.ArgumentParser(description="PopTag CA.exe 去除管理员权限补丁")
    parser.add_argument("--exe", default=DEFAULT_EXE_PATH, help=f"CA.exe 路径（默认: {DEFAULT_EXE_PATH}）")
    parser.add_argument("--restore", action="store_true", help="从备份还原原始文件")
    parser.add_argument("--dry-run", action="store_true", help="仅预览，不写入文件")
    args = parser.parse_args()

    target = Path(args.exe)
    if args.restore:
        restore_ca_exe(target)
    else:
        patch_ca_exe(target, args.dry_run)


if __name__ == "__main__":
    main()
