# -*- coding: utf-8 -*-
"""局内 AI/角色放置水泡与引爆同步回归测试 (先启动模拟器, 再运行)。

验证对局中:
  1) 不再收到任何周期性 S→C op 172 广播（CA.exe 0x00559FA1 证实 op 172 = MapEventSync 地图事件
     时间轴同步, 与 AI/道具抛掷无关; 客户端 AI 完全本地运行, 不需要服务端喂包）;
  2) 第一轮发出 C→S op 106 (SetBomberEventReq, slot=4, bombToken=4) 后,
     收到 S→C op 139 (SetBomberEvent) 回显, 且载荷与 tst.pcap stream4 抓包逐位元组一致;
  3) 2.5s 倒计时到期后, 收到 S→C op 138 (SetBombState, bombId=4, state=0),
     且不会误发 S→C op 148 (DropObstacleResult, 避免在地面错误生成香蕉皮/图钉);
  4) 第二轮发出 C→S op 106 (slot=4, bombToken=12=4+8) 后,
     收到 S→C op 139 回显且第 3 栏 bombToken=12、第 10 栏 bombId=12。

用法: 先 `mvn exec:java` 启动模拟器, 再 `python tools/test_ai_sync.py`
"""
import socket, struct, time, sys, os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decode_final import dec_s2c, ssc_enc, crc8_pingu

PORT = 3838
BANNER_BODY = bytes.fromhex('0000120012000000000100000000000037000000')

# tst.pcap stream4 真实 C→S op 106 载荷 (含 2B opcode): slot=4, bombToken=4, gridX=1, gridY=12
OP106_SLOT4_R1 = bytes.fromhex('a503 a569 5e 96ca5391 5b 56 5a 5b'.replace(' ', ''))
# 真实 S→C op 139 回显 (含 2B opcode), 同一对
OP139_SLOT4_R1 = bytes.fromhex(
    'a5e2 a569 5e 96ca5391 96ca5395 96ca5395 5b 56 5a 5b a56d a569 5a'.replace(' ', ''))

# 第二轮放泡: slot=4, bombToken=12 (0x96CA5395 ^ 12 = 0x96CA5399), bombId=12 (0xA569 ^ 12 = 0xA565)
OP106_SLOT4_R2 = bytes.fromhex('a503 a569 5e 96ca5399 5b 56 5a 5b'.replace(' ', ''))
OP139_SLOT4_R2 = bytes.fromhex(
    'a5e2 a569 5e 96ca5399 96ca5395 96ca5395 5b 56 5a 5b a565 a569 5a'.replace(' ', ''))


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
        except socket.timeout:
            continue
        if d:
            data += d
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
    data = collect(s, 1.0)

    # 1) 第一轮发 C→S op 106 (seq=49, slot=4, bombToken=4), 期望回 S→C op 139 + 2.5s 后 op 138
    s.sendall(build_c2s_frame(49, OP106_SLOT4_R1))
    data += collect(s, 2.8)

    # 2) 第二轮发 C→S op 106 (seq=52, slot=4, bombToken=12), 期望回 S→C op 139 (bombToken=12, bombId=12)
    s.sendall(build_c2s_frame(52, OP106_SLOT4_R2))
    data += collect(s, 0.5)

    ops, pkts = ops_of(data)
    assert 172 not in ops, f'不应再发送 op 172 (MapEventSync 地图事件同步), ops={ops}'
    print('[AiSync] 未收到任何 op 172 周期广播 ✓ (AI 由客户端本地驱动)')

    op139_list = [p['p'] for p in pkts if p['crc_ok']
                  and struct.unpack('>H', p['p'][:2])[0] ^ 0xA569 == 139]
    assert len(op139_list) >= 2, f'未收到两轮 op 139 (SetBomberEvent) 回显, got={len(op139_list)}'
    assert op139_list[0] == OP139_SLOT4_R1, \
        f'第一轮 op 139 载荷与抓包不一致:\n  got {op139_list[0].hex(" ")}\n  exp {OP139_SLOT4_R1.hex(" ")}'
    print(f'[AiSync] 第一轮 op 106 → op 139 回显逐位元组与抓包一致 ✓ ({len(op139_list[0])}B)')

    assert 138 in ops, f'未收到 2.5s 到期引爆包 op 138 (SetBombState), ops={ops}'
    assert 148 not in ops, f'不应发送 op 148 (DropObstacleResult), ops={ops}'
    print('[AiSync] 收到 2.5s 到期引爆包 op 138 (SetBombState) 且未误发 op 148 ✓')

    assert op139_list[1] == OP139_SLOT4_R2, \
        f'第二轮 op 139 (bombToken=12, bombId=12) 载荷不一致:\n  got {op139_list[1].hex(" ")}\n  exp {OP139_SLOT4_R2.hex(" ")}'
    print('[AiSync] 第二轮 op 106 → op 139 (bombToken=12, bombId=12) 验证通过 ✓')

    s.close()
    print('[AiSync] AI 放泡与引爆同步测试全部通过！ALL PASS ✓')


if __name__ == '__main__':
    main()
