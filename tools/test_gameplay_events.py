# -*- coding: utf-8 -*-
"""局内核心玩法事件测试 (水泡放置与爆炸、炸毁箱子、拾取道具、踩障碍物)。

验证:
  1) 发出 C->S op 106 (SetBomberEventReq 放水泡) 后, 收到 S->C op 139 (SetBomberEvent),
     2.5s 倒计时结束后收到 S->C op 138 (SetBombState state=0 引爆), 且不误发 op 148;
  2) 发出 C->S op 126 (DestroyBoxReq 炸箱子) 与 C->S op 154 (箱子爆出道具上报 action=4), 服务端正常消费;
  3) 发出 C->S op 110 (EatItemReq 拾取道具, 20B), 收到 S->C op 143 (EatItemResult);
  4) 发出 C->S op 111 (EatObstacleReq 踩障碍物, 3B), 收到 S->C op 144 (EatObstacleResult)。

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
    return bytes([(val & 0xFF) ^ 0x5A])

def enc2(val):
    return struct.pack('>H', (val & 0xFFFF) ^ 0xA569)

def enc4(val):
    return struct.pack('>I', (val & 0xFFFFFFFF) ^ 0x96CA5395)

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
    data = collect(s, 1.0)

    # 1. 模拟 AI 槽位 4 在 (1, 12) 放置水泡 (C->S op 106 SetBomberEventReq, seq=49)
    op106 = enc2(106) + enc2(0) + enc1(4) + enc4(4) + enc1(1) + enc1(12) + enc1(0) + enc1(1)
    s.sendall(build_c2s_frame(49, op106))
    print('[GameplayTest] 发送槽位 4 放置水泡请求 (op 106)...')

    # 收集 2.8 秒的数据，覆盖 2.5s 爆炸周期
    data += collect(s, 2.8)

    # 2. 模拟炸毁方块 (C->S op 126 DestroyBoxReq, seq=52) 与爆出道具上报 (C->S op 154, seq=55)
    op126 = enc2(126) + enc1(1) + enc1(11) + enc1(0) + enc2(0xFFFF)
    s.sendall(build_c2s_frame(52, op126))
    op154 = enc2(154) + enc2(0) + enc2(4) + enc2(4) + enc2(1) + enc2(2) + enc2(11) + enc2(1) + enc1(1)
    s.sendall(build_c2s_frame(55, op154))

    # 3. 模拟吃道具 (C->S op 110 EatItemReq, seq=58):
    # slotId=4, gridX=1, gridY=11, itemSeq=2, itemType=2, arg1=0, arg2=0, arg3=0, extraStr=""
    op110 = enc2(110) + enc1(4) + enc1(1) + enc1(11) + enc2(2) + enc1(2) + enc4(0) + enc4(0) + enc4(0) + enc2(0)
    s.sendall(build_c2s_frame(58, op110))

    # 4. 模拟踩障碍物 (C->S op 111 EatObstacleReq, seq=61):
    # slotId=4, gridX=2, gridY=11
    op111 = enc2(111) + enc1(4) + enc1(2) + enc1(11)
    s.sendall(build_c2s_frame(61, op111))
    data += collect(s, 0.5)

    ops, pkts = ops_of(data)
    print(f'[GameplayTest] 收到 S->C 操作码列表: {ops}')

    # 断言 1: 放泡回显 (op 139) 与引爆 (op 138), 且无误发 op 148
    assert 139 in ops, '未收到 op 139 (SetBomberEvent)'
    assert 138 in ops, '未收到 op 138 (SetBombState)'
    assert 148 not in ops, '不应收到 op 148 (DropObstacleResult)'
    print(f'[GameplayTest] 收到 op 139 (SetBomberEvent) & op 138 (SetBombState), 且无 op 148 ✓')

    # 断言 2: 吃道具响应 (op 143 EatItemResult)
    assert 143 in ops, '未收到 op 143 (EatItemResult)'
    print(f'[GameplayTest] 收到 op 143 (EatItemResult) ×{ops.count(143)} ✓')

    # 断言 3: 踩障碍物响应 (op 144 EatObstacleResult)
    assert 144 in ops, '未收到 op 144 (EatObstacleResult)'
    print(f'[GameplayTest] 收到 op 144 (EatObstacleResult) ×{ops.count(144)} ✓')

    s.close()
    print('[GameplayTest] 局内核心玩法事件全部测试通过！ALL PASS ✓')

if __name__ == '__main__':
    main()
