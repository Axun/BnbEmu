# -*- coding: utf-8 -*-
"""定位某个函数在 .rdata 虚表中的槽位, 换算成 vtable 偏移 (index*4),
用于对照 `call dword ptr [eax+disp]` 的 disp。

用法: python tools/vtable_slot.py 0x51D8FB
"""
import struct
import sys

from rev_ca import Disasm

d = Disasm()
raw = open(r"F:\Games\PopTag\CA.exe", 'rb').read()


def file_to_va(off):
    for sec in d.pe.sections:
        if sec.PointerToRawData <= off < sec.PointerToRawData + sec.SizeOfRawData:
            return d.image_base + sec.VirtualAddress + (off - sec.PointerToRawData)
    return None


for arg in sys.argv[1:]:
    target = int(arg, 0)
    pat = struct.pack('<I', target)
    idx = raw.find(pat)
    while idx != -1:
        # 向前回溯连续的代码指针, 找表首
        start = idx
        while start - 4 >= 0:
            prev = struct.unpack('<I', raw[start - 4:start])[0]
            if 0x401000 <= prev < 0x800000:
                start -= 4
            else:
                break
        table_va = file_to_va(start)
        slot = (idx - start) // 4
        print(f"0x{target:08X}: vtable@0x{table_va:08X} slot={slot} disp=0x{slot * 4:X}")
        # 打印该表的前 6 项 (含 RTTI/析构)
        vals = struct.unpack('<6i', raw[start - 8:start + 16])
        print("   head: " + ", ".join(f"{v & 0xFFFFFFFF:08X}" for v in vals))
        idx = raw.find(pat, idx + 1)
