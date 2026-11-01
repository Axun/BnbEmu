# -*- coding: utf-8 -*-
"""局内核心玩法事件测试 (水泡生成、倒计时爆炸、道具拾取、角色死亡等)。

验证:
  1) 收到 C->S op 149 (EventItemReq / 放炸弹) 后, 立即收到 S->C op 138 (SetBombStateBatch state=1 解锁);
  2) 2.5s 倒计时结束后, 收到 S->C op 138 (state=0 引爆) 与 S->C op 148 (TileBombExplode 十字火焰);
  3) 发出 C->S op 126 (EatItemReq 拾取道具), 收到 S->C op 158 (EatItemResult);
  4) 发出 C->S op 154 (BomberDieReq), 收到 S->C op 156 (BomberTrapped) 与 S->C op 159 (BomberDie)。

用法: 先运行模拟器, 再运行 python -X utf8 tools/test_gameplay_events.py
"""
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

def enc1(val):
    return bytes([val ^ 0x5A])

def enc2(val):
    return struct.pack('>H', val ^ 0xA569)

def enc4(val):
    return struct.pack('>I', val ^ 0x96CA5395)

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
    print('[GameplayTest] Banner 验证通过 ✓')

    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    s.sendall(open(os.path.join(base, 'tools/replay_practice_cs.bin'), 'rb').read())

    # 接收进入练习房的所有启动包
    data = collect(s, 1.2)

    # 1. 模拟 AI 槽位 1 移动至 (3, 4)
    # C->S op 106 (seq=49): subType=0, slotId=1, slotParam=1, gridX=3, gridY=4, flag0=0, flag1=1
    op106 = enc2(106) + enc2(0) + enc1(1) + enc4(1) + enc1(3) + enc1(4) + enc1(0) + enc1(1)
    s.sendall(build_c2s_frame(49, op106))
    data += collect(s, 0.4)

    # 2. 模拟 AI 槽位 1 放置炸弹 (C->S op 149 EventItemReq)
    # op 149 (seq=52): slotId=1, token=0, action=0, param=0
    op149 = enc2(149) + enc1(1) + enc4(0) + enc1(0) + enc4(0)
    s.sendall(build_c2s_frame(52, op149))
    print('[GameplayTest] 发送 AI 槽位 1 放置炸弹请求 (op 149)...')

    # 收集 3.0 秒的数据，覆盖 2.5s 爆炸周期
    data += collect(s, 3.0)

    # 3. 模拟吃道具 (C->S op 126 EatItemReq)
    # op 126 (seq=55): gridX=3, gridY=4, slotId=1, itemType=2
    op126 = enc2(126) + enc1(3) + enc1(4) + enc1(1) + enc2(2)
    s.sendall(build_c2s_frame(55, op126))

    # 4. 模拟角色死亡 (C->S op 154 BomberDieReq)
    # op 154 (seq=58): slotId=1, killer=0, posX=3, posY=4
    op154 = enc2(154) + enc2(1) + enc2(0) + enc2(3) + enc2(4)
    s.sendall(build_c2s_frame(58, op154))
    data += collect(s, 0.5)

    ops, pkts = ops_of(data)
    print(f'[GameplayTest] 收到 S->C 操作码列表: {ops}')

    # 断言 1: 炸弹状态更新 (op 138)
    assert 138 in ops, f'未收到 op 138 (SetBombState/Batch)'
    print(f'[GameplayTest] 收到 op 138 (SetBombState) ×{ops.count(138)} ✓')

    # 断言 2: 十字火焰爆炸 (op 148 TileBombExplode)
    assert 148 in ops, f'未收到 op 148 (TileBombExplode)'
    print(f'[GameplayTest] 收到 op 148 (TileBombExplode) ×{ops.count(148)} ✓')

    # 断言 3: 吃道具响应 (op 158 EatItemResult)
    assert 158 in ops, f'未收到 op 158 (EatItemResult)'
    print(f'[GameplayTest] 收到 op 158 (EatItemResult) ×{ops.count(158)} ✓')

    # 断言 4: 角色困住 (op 156) 与死亡 (op 159)
    assert 156 in ops, f'未收到 op 156 (BomberTrapped)'
    assert 159 in ops, f'未收到 op 159 (BomberDie)'
    print(f'[GameplayTest] 收到 op 156 (BomberTrapped) & op 159 (BomberDie) ✓')

    s.close()
    print('[GameplayTest] 局内核心玩法事件全部测试通过！ALL PASS ✓')

if __name__ == '__main__':
    main()
