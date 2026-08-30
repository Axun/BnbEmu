# -*- coding: utf-8 -*-
"""
passport (47611 nmco_session) 樁回歸測試:
把 login.pcap 裡真實客戶端的 C→S 幀原樣回放給本地樁, 斷言 S→C 與真實成功回應逐位元組一致。

用法: 先啟動模擬器, 再 python tools/replay_passport.py
"""
import os
import socket
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

PORT = 47611
CAP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "cap")


def read_exact(s: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        d = s.recv(n - len(buf))
        if not d:
            raise ConnectionError(f"連線被關閉, 已收到 {len(buf)}B")
        buf += d
    return buf


def read_frame(s: socket.socket) -> bytes:
    hdr = read_exact(s, 2)
    total = struct.unpack(">H", hdr)[0] + 4
    return hdr + read_exact(s, total - 2)


def check_frame_shape(name: str, frame: bytes):
    """幀格式: [len:u16 BE][cmd:u16 BE][payload], total = len + 4"""
    total = len(frame)
    ln = struct.unpack(">H", frame[0:2])[0]
    assert ln + 4 == total, f"[{name}] 幀長不自洽: len={ln}, total={total}"


def case(name: str, c2s_file: str, expect_file: str = None, expect_cmd: int = None):
    blob = open(os.path.join(CAP, c2s_file), "rb").read()
    s = socket.create_connection(("127.0.0.1", PORT), timeout=5)
    s.sendall(blob)
    frame = read_frame(s)
    s.close()

    check_frame_shape(name, frame)
    cmd = struct.unpack(">H", frame[2:4])[0]
    if expect_cmd is not None:
        assert cmd == expect_cmd, f"[{name}] 回應 cmd={cmd:#06x}, 預期 {expect_cmd:#06x}"

    if expect_file:
        want = open(os.path.join(CAP, expect_file), "rb").read()
        assert frame == want, (
            f"[{name}] 回應與真實抓包不一致:\n  got  {frame.hex(' ')}\n  want {want.hex(' ')}"
        )
        print(f"[{name}] ✓ 與真實成功回應逐位元組一致 ({len(frame)}B, cmd={cmd:#06x})")
    else:
        print(f"[{name}] ✓ 幀格式正確 ({len(frame)}B, cmd={cmd:#06x})")


def case_garbage():
    """亂數位元組流不應讓樁崩潰"""
    s = socket.create_connection(("127.0.0.1", PORT), timeout=5)
    try:
        s.sendall(b"\x12\x34\x56")
        s.sendall(b"\x00\x05\x00\x0b")  # 宣告 5B payload 但不足 -> 掛半截
    finally:
        s.close()
    # 樁還能服務下一個正常連線
    case("garbage 後恢復", "passport_login_s0_c2s.bin", "passport_login_s0_s2c.bin", 0x000C)


if __name__ == "__main__":
    # 真實成功會話逐位元組斷言 (login.pcap: kcworms1 換票 + 查詢角色)
    case("passport 登入", "passport_login_s0_c2s.bin", "passport_login_s0_s2c.bin", 0x000C)
    case("passport 會話查詢", "passport_login_s1_c2s.bin", "passport_login_s1_s2c.bin", 0x002E)
    # tst.pcap 的另一帳號 (kcworm1): 樁恆回 login.pcap 模板, 只驗幀格式與 cmd
    case("passport 登入(另一帳號)", "passport_tst_s1_c2s.bin", None, 0x000C)
    case("passport 會話查詢(另一帳號)", "passport_tst_s2_c2s.bin", None, 0x002E)
    case_garbage()
    print("passport 樁回歸測試全部通過")
