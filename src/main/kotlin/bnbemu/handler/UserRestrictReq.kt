package bnbemu.handler

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.packet.decodeName

/**
 * C->S op 66: 用户受限状态校验 / 资料查询请求 (UserRestrictReq)
 *
 * 逆向确认 (CA.exe EN v18):
 * 1. 查看自己 (CStage::OnMyPageButton 0x00501B50 -> 0x00501BB4):
 *    - 结构: EncodeTokenStr(myCharName) + Encode1(1) + Encode1(slotIndex)
 *    - 样本: A5 6D F0 AE 35 F3 5B 5A -> myCharName="ffdf", isMe=1, slotIndex=0
 *    - 机制:
 *      首次点击 MyPage 时，客户端会同时发送 op 301, op 73, op 66；
 *      而后续再次点击 MyPage 时，客户端内部标志位 [0x00993E70 + 0x358] 已锁定为 0，
 *      0x00501B50 会跳过发送 op 73，直接发送 op 301 与 op 66！
 *      服务端在收到 isMe == 1 时下发 sendUserInfo(c, slotIndex)，唤醒 CMyPageDialog 弹窗。
 *
 * 2. 查看他人资料 (CGameIdListDlg 0x004887C3 / CRoomDetailInfoDialog 0x004ADB2D / CUserListCtrl 0x0069E91E):
 *    - 调用发包点: 0x004F9B25
 *    - 结构: EncodeTokenStr(targetCharName) + Encode1(0) (⚠️ 后面没有 slotIndex 字段！)
 *    - 样本: A5 6C FD A9 32 DF 35 5A -> targetCharName="Jacko" (5B), isMe=0
 *    - 机制:
 *      客户端在右键点击其他玩家或在列表中双击他人查看名片时，直接且仅发送此 op 66。
 *      服务端收到后必须下发 isSelf=false 的 S->C op 73 UserInfoResult，
 *      客户端 CStage::OnUserInfoResult (0x004FBB6F) 收到后唤起 COtherUserPageDialog (0x005E58E4) 展示他人名片。
 */
val UserRestrictReq = PKTHandler { c ->
    // ⚠️ 长度与 Token 读取：
    // 必须用 Decode2 读取长度后直接 readBytes，再调用 decodeName 解出明文；
    // 不能直接用 DecodeStr（经操作系统字符集转码会有损破坏 Token 原始字节）。
    val len = Decode2
    val token = ByteArray(len).also { readBytes(it) }
    val charName = decodeName(token)
    val isMe = Decode1

    if (isMe == 1) {
        val slotIndex = Decode1
        sendUserInfo(c, slotIndex)
    } else {
        sendOtherUserInfo(c, charName)
    }
}

