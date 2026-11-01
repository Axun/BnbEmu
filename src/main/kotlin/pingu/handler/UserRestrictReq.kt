package pingu.handler

import pingu.netty.Decode1
import pingu.netty.DecodeStr
import pingu.netty.PKTHandler

/**
 * C->S op 66: 用户受限状态校验请求 (UserRestrictReq / ModifyUserInfoReq)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 发包点: CA.exe 0x00501BB4 (点击 MyPage 按钮时由 0x00501B50 发出)
 * - 包体结构: EncodeStr(session) + Encode1(isMe) + Encode1(slotIndex)
 *   (日志样本: A5 6D F0 AE 35 F3 5B 5A -> session="f0ae35f3", isMe=1, slotIndex=0)
 * - 关键机制:
 *   首次点击 MyPage 时，客户端会同时发送 op 301, op 73, op 66；
 *   而后续再次点击 MyPage 时，客户端内部标志位 [0x00993E70 + 0x358] 已锁定为 0，
 *   0x00501B50 会跳过发送 op 73，直接发送 op 301 与 op 66！
 *   服务端在收到 op 66 时触发 sendUserInfo(c, slotIndex)，使得后续打开 MyPage 时
 *   也能下发 S->C op 73 UserInfoResult 顺利唤醒 CMyPageDialog 弹窗。
 */
val UserRestrictReq = PKTHandler { c ->
    val session = DecodeStr
    val isMe = Decode1
    val slotIndex = Decode1

    sendUserInfo(c, slotIndex)
}

// 兼容旧配置命名
val ModifyUserInfoReq = UserRestrictReq
