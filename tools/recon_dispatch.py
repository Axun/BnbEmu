# -*- coding: utf-8 -*-
"""快速侦察 CA.exe 局内消息分发器 0x642621 附近的 opcode 分派链。

用法: python tools/recon_dispatch.py [start] [end]
默认反汇编 0x642400..0x642900, 打印 cmp/sub/test 立即数与 call 目标,
用于定位 op 172 / op 139 / op 106 / op 149 的处理分支。
"""
import sys
import pefile
from capstone import Cs, CS_ARCH_X86, CS_MODE_32

CA = r"F:\Games\PopTag\CA.exe"
IB = 0x400000


def va_to_off(pe, va):
    return pe.get_offset_from_rva(va - IB)


def main():
    start = int(sys.argv[1], 0) if len(sys.argv) > 1 else 0x642400
    end = int(sys.argv[2], 0) if len(sys.argv) > 2 else 0x642900

    pe = pefile.PE(CA, fast_load=True)
    data = pe.__data__
    code = data[va_to_off(pe, start):va_to_off(pe, end)]

    md = Cs(CS_ARCH_X86, CS_MODE_32)
    md.detail = True
    for ins in md.disasm(code, start):
        extra = ""
        # 立即数
        for op in ins.operands:
            if op.type == 2:  # X86_OP_IMM
                extra += f"  imm=0x{op.imm & 0xffffffff:x}"
        print(f"0x{ins.address:08x}  {ins.mnemonic:<8}{ins.op_str}{extra}")


if __name__ == "__main__":
    main()
