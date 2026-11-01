# -*- coding: utf-8 -*-
"""op198 记录与 op200/op413 交叉比对 + dword 暴力解码."""
import struct

def rdbytes(p):
    return bytes.fromhex(p.replace(' ', ''))

BLOB = rdbytes('5896ca53 9796ca53 945596ca 5381a56a c42d3588 95ae63d6 2cc49196 c9fa159a 96ca7483 5796ca53 94a565fb af27dcf3 be1abaf1 ab11f888 95ae63d6 2cc49196 c9fa15a5 96ca5397 96ca5394 5596ca53 81a56ac4 2d358895 ae63d62c c49196c9 fa159a96 ca748357 96ca5394 a565fbaf 27dcf3be 1abaf1ab 11f88895 ae63d62c c49196c9 fa15a5')
REC = BLOB[1:68]
assert BLOB[68:] == REC
print('op198 payload len', len(BLOB), '| E1(count) =', BLOB[0] ^ 0x5A, '| record len', len(REC))

op200 = open(r'E:\workspace\test\pingu\cap\chan_s2c_op200_105B_0.bin', 'rb').read()
op413 = open(r'E:\workspace\test\pingu\cap\chan_s2c_op413_616B_0.bin', 'rb').read()

def lcs(a, b):
    n, m = len(a), len(b)
    best = (0, 0, 0)
    for i in range(n):
        for j in range(m):
            k = 0
            while i + k < n and j + k < m and a[i + k] == b[j + k]:
                k += 1
            if k > best[0]:
                best = (k, i, j)
    return best

print()
print('=== op198 record 在 op200/op413 中的子串/公共子串 ===')
for name, blob in [('op200', op200), ('op413', op413)]:
    for L in (24, 20, 16, 12, 8):
        print('  %s find REC[:%d]=%s -> %d' % (name, L, REC[:L].hex(), blob.find(REC[:L])))
    k, i, j = lcs(REC, blob)
    print('  LCS(REC,%s) = len %d @ REC[%d] == %s[%d]: %s' % (name, k, i, name, j, REC[i:i+k].hex()))

print()
print('=== op200 前 40B ===')
print(' ', op200[:40].hex(' '))

# op200 与 op198 记录: 共享 '58 96 ca 53' 前缀
print()
print('=== 共享前缀检测: 58 96 ca 53 (op198 首 4B 与 op200@2) ===')
for name, blob in [('op200', op200), ('op413', op413), ('op87', open(r'E:\workspace\test\pingu\cap\chan_s2c_op87_41B_0.bin','rb').read())]:
    idxs = []
    st = 0
    while True:
        i = blob.find(bytes.fromhex('5896ca53'), st)
        if i < 0: break
        idxs.append(i); st = i + 1
    print('  %s: 58 96 ca 53 at' % name, idxs)

print()
print('=== op198 record 逐 4B (SSD 式 BE^0x96CA5395) ===')
for i in range(0, len(REC) - 3, 4):
    v = struct.unpack_from('>I', REC, i)[0] ^ 0x96CA5395
    print('  @%2d -> %10d (0x%08x)' % (i, v, v))
t = len(REC) % 4
if t:
    print('  tail', t, 'B:', REC[-t:].hex())
