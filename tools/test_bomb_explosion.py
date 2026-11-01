# -*- coding: utf-8 -*-
import socket, struct, time, sys, os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decode_final import dec_s2c, ssc_enc, crc8_pingu

PORT = 3838
BANNER_BODY = bytes.fromhex('0000120012000000000100000000000037000000')

def build_c2s_frame(seq, payload, uid=198):
    enc = ssc_enc(seq, payload)
    hc = (231 ^ (192 + seq)) & 0xFF
    hdr = bytes([hc]) + struct.pack('<H', uid ^ 0xA569) + bytes([len(payload) ^ 0x69])
    crc = crc8_pingu(seq & 0xFF, payload)
    return hdr + enc + bytes([crc])

def main():
    s = socket.create_connection(('127.0.0.1', PORT), timeout=5)

    hdr = s.recv(3)
    blen = struct.unpack('>H', hdr[1:3])[0]
    body = s.recv(blen)
    assert body == BANNER_BODY, 'Banner 错误'
    print('[BombTest] Banner 验证通过 ✓')

    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    cs_init = open(os.path.join(base, 'tools/replay_practice_cs.bin'), 'rb').read()
    s.sendall(cs_init)
    time.sleep(0.5)

    op140_raw = struct.pack('>H', 140 ^ 0xA569) + bytes([0 ^ 0x5A, 0x21 ^ 0x5A, 2 ^ 0x5A])
    s.sendall(build_c2s_frame(49, op140_raw, 198))
    print('[BombTest] 发送放置水泡请求 op 140 (seq=49) ✓')

    s.settimeout(0.5)
    data_all = b''
    # 持续接收 3.5 秒 (包含放置广播 + 2.5秒倒计时 + 爆炸广播)
    t_end = time.time() + 3.5
    while time.time() < t_end:
        try:
            d = s.recv(65536)
            if d:
                data_all += d
        except socket.timeout:
            pass

    pkts = dec_s2c(data_all)
    ops = [struct.unpack('>H', p['p'][:2])[0] ^ 0xA569 for p in pkts if p['crc_ok']]
    print(f'[BombTest] 收到 S->C 加密包共 {len(pkts)} 个, ops={ops}')
    assert 177 in ops, f'未收到放置水泡广播 op 177, 当前收到: {ops}'
    print('[BombTest] 收到水泡放置广播 op 177 (BombIgnite) ✓')
    assert 178 in ops, f'未收到水泡爆炸广播 op 178, 当前收到: {ops}'

    # 校验爆炸包内容 (EN 格式: bombId=Encode2, state=Encode1(0))
    bomb_pkts = [p['p'] for p in pkts if p['crc_ok'] and (struct.unpack('>H', p['p'][:2])[0] ^ 0xA569) == 178]
    bp = bomb_pkts[0][2:]
    bid = struct.unpack('>H', bp[:2])[0] ^ 0xA569
    state = bp[2] ^ 0x5A
    assert state == 0, f'爆炸包字段解析错误: bid={bid}, state={state}'
    print(f'[BombTest] 收到水泡爆炸广播 op 178 (SetBombState) ✓ (bombId={bid}, state={state}=爆炸消除)')

    s.close()
    print('[BombTest] 水泡放置与爆炸状态机测试全部通过！ALL PASS ✓')

if __name__ == '__main__':
    main()
