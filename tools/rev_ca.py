# -*- coding: utf-8 -*-
import pefile
import capstone
import struct
import sys

EXE_PATH = r"F:\Games\PopTag\CA.exe"

class Disasm:
    def __init__(self, path=EXE_PATH):
        self.pe = pefile.PE(path)
        self.image_base = self.pe.OPTIONAL_HEADER.ImageBase
        self.md = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)
        self.md.detail = True
        
    def va_to_rva(self, va):
        return va - self.image_base if va >= self.image_base else va

    def read_bytes(self, va, size):
        rva = self.va_to_rva(va)
        return self.pe.get_data(rva, size)

    def disasm(self, va, count=50, max_bytes=500):
        data = self.read_bytes(va, max_bytes)
        out = []
        for i, ins in enumerate(self.md.disasm(data, va)):
            if i >= count:
                break
            bytes_hex = ins.bytes.hex(' ')
            out.append(f"0x{ins.address:08X}: {bytes_hex:<20} {ins.mnemonic:<8} {ins.op_str}")
        return "\n".join(out)

    def search_bytes(self, pattern):
        results = []
        for section in self.pe.sections:
            data = section.get_data()
            idx = 0
            while True:
                idx = data.find(pattern, idx)
                if idx == -1:
                    break
                va = self.image_base + section.VirtualAddress + idx
                results.append(va)
                idx += 1
        return results

    def search_string(self, s):
        return self.search_bytes(s.encode('ascii'))

if __name__ == '__main__':
    d = Disasm()
    if len(sys.argv) > 1:
        addr = int(sys.argv[1], 16)
        cnt = int(sys.argv[2]) if len(sys.argv) > 2 else 40
        print(d.disasm(addr, cnt))
