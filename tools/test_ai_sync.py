# -*- coding: utf-8 -*-
"""局内 AI/角色事件同步回归测试 (先启动模拟器, 再运行)。

验证对局中:
  1) 每 ~400ms 周期性收到 S→C op 172 (ItemThrow, 实体时间同步);
  2) 发出 C→S op 106 (SetBomberEventReq) 后, 收到 S→C op 139 (SetBomberEvent) 回显,
     且载荷与 tst.pcap stream4 抓包逐位元组一致 (slot/a/b 三栏随 op 106 变化)。

用法: 先 `mvn exec:java` 启动模拟器, 再 `python tools/test_ai_sync.py`
"""
import socket, struct, time, sys, os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decode_final import dec_s2c, ssc_enc, crc8_pingu

PORT = 3838
BANNER_BODY = bytes.fromhex('0000120012000000000100000000000037000000')

# tst.pcap stream4 真实 C→S op 106 载荷 (含 2B opcode): slot=4, a=1, b=12
OP106_SLOT4 = bytes.fromhex('a503 a569 5e 96ca5391 5b 56 5a 5b'.replace(' ', ''))
# 真实 S→C op 139 回显 (含 2B opcode), 同一对
OP139_SLOT4 = bytes.fromhex(
    'a5e2 a569 5e 96ca5391 96ca5395 96ca5395 5b 56 5a 5b a56d a569 5a'.replace(' ', ''))


def build_c2s_frame(seq, payload, uid=198):
    enc = ssc_enc(seq, payload)
    hc = (231 ^ (192 + seq)) & 0xFF
    hdr = bytes([hc]) + struct.pack('<H', uid ^ 0xA569) + bytes([len(payload) ^ 0x69])
    crc = crc8_pingu(seq & 0xFF, payload)
    return hdr + enc + bytes([crc])


def collect(s, seconds):
    s.settimeout(0.3)
    data, end = b'', time.time() + seconds
    while time.time() < end:
        try:
            d = s.recv(65536)
            if d:
                data += d
        except socket.timeout:
            pass
    return data


def ops_of(data):
    pkts = dec_s2c(data)
    return [struct.unpack('>H', p['p'][:2])[0] ^ 0xA569 for p in pkts if p['crc_ok']], pkts


def main():
    s = socket.create_connection(('127.0.0.1', PORT), timeout=5)

    hdr = s.recv(3)
    blen = struct.unpack('>H', hdr[1:3])[0]
    body = s.recv(blen)
    assert body == BANNER_BODY, 'Banner 错误'
    print('[AiSync] Banner 验证通过 ✓')

    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    s.sendall(open(os.path.join(base, 'tools/replay_practice_cs.bin'), 'rb').read())

    # 累积整段 S→C 流 (dec_s2c 只能从 seq=40/43 起对齐, 故必须一次解码整条流)
    data = collect(s, 1.2)

    # 发 C→S op 106 (seq=49, 接续回放帧 40/43/46), 期望回 S→C op 139
    s.sendall(build_c2s_frame(49, OP106_SLOT4))
    data += collect(s, 0.8)

    ops, pkts = ops_of(data)
    assert 172 in ops, f'未收到周期性 op 172 (ItemThrow), ops={ops}'
    n172 = ops.count(172)
    print(f'[AiSync] 收到周期性 op 172 (ItemThrow) ×{n172} ✓')

    assert 139 in ops, f'未收到 op 139 (SetBomberEvent) 回显, ops={ops}'
    got = next(p['p'] for p in pkts if p['crc_ok']
               and struct.unpack('>H', p['p'][:2])[0] ^ 0xA569 == 139)
    assert got == OP139_SLOT4, \
        f'op 139 载荷与抓包不一致:\n  got {got.hex(" ")}\n  exp {OP139_SLOT4.hex(" ")}'
    print(f'[AiSync] op 106 → op 139 回显逐位元组与抓包一致 ✓ ({len(got)}B)')

    s.close()
    print('[AiSync] AI 事件同步测试全部通过！ALL PASS ✓')


if __name__ == '__main__':
    main()
