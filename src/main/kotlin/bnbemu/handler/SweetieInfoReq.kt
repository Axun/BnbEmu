package bnbemu.handler

import bnbemu.netty.DecodeTokenStr
import bnbemu.netty.PKTHandler

/**
 * C->S op 288 (0x120): 情侣/伴侣信息查询请求 (SweetieInfoReq / CoupleInfoReq)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 发包来源: COtherUserPageDialog::InitData (0x005E3438)
 * - 触发时机:
 *   当打开其他玩家名片时，客户端读取目标玩家 UserInfo 中的伴侣名字 sweetieName ([ebx + 0x2c])，
 *   在 0x005E3438 构造 op 288 包并发送 EncodeTokenStr(sweetieName)。
 * - 数据包结构:
 *   - Encode2(len)
 *   - 若 len > 0 则紧随 token 密文字节 (按 decodeName 逆变换还原伴侣名字)
 *   - 若目标玩家没有伴侣 (sweetieName 为空)，客户端发送 Encode2(0)，即十六进制 A5 69
 */
val SweetieInfoReq = PKTHandler { c ->
    val sweetieName = DecodeTokenStr
    // 当前伴侣系统尚未启用，先空消费；后续可下发伴侣情侣戒指与纪念日等信息
}
