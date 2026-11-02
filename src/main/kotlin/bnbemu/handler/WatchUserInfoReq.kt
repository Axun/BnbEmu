package bnbemu.handler

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler

/**
 * C->S op 74: 查看用户状态通知 (WatchUserInfoReq)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 发包点: CA.exe 0x006E48E1 (参数: slotIndex, mode)
 *   - 0x005DDCF7: CMyPageDialog 析构时调用 0x006E48E1(0, 0)，通知服务端结束查看自身资料
 *   - 0x005D445A / 0x0066A12F: 查看他人时调用 0x006E48E1(slotIndex, 1)，通知服务端开始查看
 * - 包体结构: Encode1(slotIndex) + Encode1(mode)
 *   (日志样本: 5A 5A -> slotIndex=0, mode=0 结束查看)
 */
val WatchUserInfoReq = PKTHandler { _ ->
    val slotIndex = Decode1
    val mode = Decode1
}
