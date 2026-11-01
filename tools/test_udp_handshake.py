# -*- coding: utf-8 -*-
import socket, struct, time, sys, os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decode_final import crc8_pingu

def build_udp_packet(opcode: int, payload: bytes = b'') -> bytes:
    data = bytes([1 + len(payload), opcode]) + payload
    crc = crc8_pingu(0, data)
    return data + bytes([crc])

def test_udp_port(port: int):
    print(f'[UDPTest] Testing UDP port {port}...')
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(2.0)

    # 模拟客户端发送 UDP opcode 0 握手探测 (含 4 字节 userId = 7215)
    user_id = 7215
    probe_pkt = build_udp_packet(0, struct.pack('>I', user_id))
    sock.sendto(probe_pkt, ('127.0.0.1', port))
    print(f'[UDPTest] Sent UDP opcode 0 probe ({len(probe_pkt)} bytes, hex={probe_pkt.hex()})')

    try:
        resp, addr = sock.recvfrom(1024)
    except socket.timeout:
        print(f'[UDPTest] ERROR: Timeout waiting for UDP response on port {port}')
        sock.close()
        return False

    print(f'[UDPTest] Received UDP response ({len(resp)} bytes from {addr}, hex={resp.hex()})')

    assert len(resp) >= 3, f'Response too short: {len(resp)}'
    pkt_len = resp[0]
    opcode = resp[1]
    crc_recv = resp[-1]

    # 验证 CRC8: 在整段 [len + opcode + payload + crc] 上计算 CRC8，结果必须为 0
    crc_check = crc8_pingu(0, resp[:pkt_len + 1])
    assert crc_check == 0, f'CRC8 check failed: remainder={crc_check}, expected 0'
    print(f'[UDPTest] CRC8 verification passed (remainder == 0) [OK]')

    assert opcode == 2, f'Expected UDP opcode 2 (Ack), got {opcode}'
    print(f'[UDPTest] UDP opcode 2 (Ack) verified [OK]')

    sock.close()
    return True

def main():
    success_3839 = test_udp_port(3839)
    success_4849 = test_udp_port(4849)
    if success_3839 and success_4849:
        print('\n[UDPTest] ALL UDP HANDSHAKE TESTS PASSED [OK]')
    else:
        sys.exit(1)

if __name__ == '__main__':
    main()
