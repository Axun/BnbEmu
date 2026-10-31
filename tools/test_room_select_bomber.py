# -*- coding: utf-8 -*-
"""房间内切换角色 (C→S op 87) 回归测试。

验证:
  1) 客户端发出 C→S op 87 (body = E1(slot) E1(bomber), 实测 5A 5C = 槽 0 / bomber 6)
     后, 服务器必须回 S→C op 105 (body = E1(slot) E1(bomber))。
  2) 该应答载荷逐字节等于 CA.exe 预期的 2 字节回显 (也是 cap/practice_s2c_op105_2B_* 的结构)。
  3) 客户端据 op 105 清除槽位等待锁 [+0x2f64]==5; 缺包会导致后续点击角色无反应,
     因此断言"每个 op 87 都恰好换回一个 op 105"。

用法: 先运行模拟器 (mvn compile exec:java), 再 python -X utf8 tools/test_room_select_bomber.py
"""
import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decode_final import dec_s2c, ssc_enc, crc8_pingu

PORT = 3838
BANNER_BODY = bytes.fromhex('0000120012000000000100000000000037000000')


def build_c2s_frame(seq, payload, uid=198):
    """按 AGENTS.md 的 C→S 加密帧格式封装: [hc][uid:2][len^0x69:1][payload][CRC8]"""
    enc = ssc_enc(seq, payload)
    hc = (231 ^ (192 + seq)) & 0xFF
    hdr = bytes([hc]) + struct.pack('<H', uid ^ 0xA569) + bytes([len(payload) ^ 0x69])
    crc = crc8_pingu(seq & 0xFF, payload)
    return hdr + enc + bytes([crc])


def enc1(val):
    return bytes([val ^ 0x5A])


def enc2(val):
    return struct.pack('>H', val ^ 0xA569)


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
    """dec_s2c 只能从 seq=40/43 起对齐, 故必须一次解码整条累积流。"""
    pkts = dec_s2c(data)
    good = [p for p in pkts if p['crc_ok']]
    return [struct.unpack('>H', p['p'][:2])[0] ^ 0xA569 for p in good], good


def main():
    s = socket.create_connection(('127.0.0.1', PORT), timeout=5)

    hdr = s.recv(3)
    blen = struct.unpack('>H', hdr[1:3])[0]
    body = b''
    while len(body) < blen:
        body += s.recv(blen - len(body))
    assert body == BANNER_BODY, 'Banner 错误'
    print('[SelectBomber] Banner 验证通过 ✓')

    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    # 用练习房回放把连接推进到房间阶段 (op7 → op75)
    s.sendall(open(os.path.join(base, 'tools/replay_practice_cs.bin'), 'rb').read())
    data = collect(s, 1.2)
    ops, _ = ops_of(data)
    assert 87 in ops, f'练习房回应链缺 op 87 (房间列表), 实际: {ops}'
    print(f'[SelectBomber] 已进入房间阶段 (回应 {len(ops)} 包) ✓')

    # ---- 用例 1: 槽位 0 切换到 bomber 6 (与真实客户端抓包 5A 5C 一致) ----
    payload = enc2(87) + enc1(0) + enc1(6)
    s.sendall(build_c2s_frame(49, payload))
    data += collect(s, 0.6)
    ops, pkts = ops_of(data)

    assert 105 in ops, f'C→S op 87 未收到 S→C op 105 应答, 实际: {ops}'
    # 练习房回应链本身也含 op 105 (整槽资料版), 故取最新一个 105 作为 op 87 的回显
    idx = len(ops) - 1 - ops[::-1].index(105)
    body = pkts[idx]['p'][2:]
    assert body == enc1(0) + enc1(6), (
        f'op 105 载荷不符: {body.hex(" ")} != {(enc1(0) + enc1(6)).hex(" ")}'
    )
    print(f'[SelectBomber] op 87 → op 105 回显 = {body.hex(" ")} '
          f'(E1(slot)=0, E1(bomber)=6) ✓')

    # ---- 用例 2: 另一个槽位, 确认不是硬编码 ----
    payload = enc2(87) + enc1(3) + enc1(2)
    s.sendall(build_c2s_frame(52, payload))
    data += collect(s, 0.6)
    ops, pkts = ops_of(data)
    assert ops.count(105) >= 2, f'第二次 op 87 未收到 op 105 应答, 实际: {ops}'
    idx = len(ops) - 1 - ops[::-1].index(105)
    body = pkts[idx]['p'][2:]
    assert body == enc1(3) + enc1(2), f'op 105 载荷不符: {body.hex(" ")}'
    print(f'[SelectBomber] 第二次 op 87 → op 105 回显 = {body.hex(" ")} '
          f'(E1(slot)=3, E1(bomber)=2) ✓')

    s.close()
    print('[SelectBomber] 房间内切换角色测试全部通过！ALL PASS ✓')


if __name__ == '__main__':
    main()
