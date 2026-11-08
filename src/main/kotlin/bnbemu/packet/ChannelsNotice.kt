package bnbemu.packet

import bnbemu.ACP
import bnbemu.netty.PKT
import bnbemu.netty.ssdEncode
import java.io.ByteArrayOutputStream

/**
 * 滚动系统公告条目
 * @param type 消息类型 (u8)
 * @param flag 消息显示标志/颜色 (u8)
 * @param text 消息内容 (ASCII/ACP)
 */
data class NoticeMessage(
    val type: Int = 0,
    val flag: Int = 0,
    val text: String = "",
)

/**
 * 频道/服务器选择界面 公告与PPL广告信息 (Opcode 12 / 0x0C, S→C)
 *
 * 逆向确认 (CA.exe, 2026-09-10):
 * - 客户端处理入口: CSelectServerStage::OnPacket (0x625702 -> case 12: 0x62574e -> 0x6228a5)
 * - 封包格式: [Encode4(len)][SSD 数据块] (无 Encode1Bool 前缀, 与 op 13 ChannelsState 一致)
 *
 * SSD 数据块结构 (客户端 sub_78F1E0 反混淆后交给 CISmartStream, 全是明文):
 * - 块头 (6字节): [0x18][(len-5)&0xFF][u32 0] (标准 SSD 块头, 与 op11/op13 一致)
 * - 块体字段 (明文按顺序); 注意子块内 ReadString (sub_78CE60:783831) 的长度是 **u32 LE**,
 *   不是顶层报文 EncodeStr 的 u16:
 *   1. noticeTitle: ReadString (u32 LE 长度 + 文本) -> 存入 CFxApp (0x994d04) 横幅公告缓冲区 (0x68357f, 偏移 +0x360)
 *   2. param1, param2, param3: 3 × ReadWord (u16 LE) -> 存入 CFxApp (+0x560, +0x564, +0x568) 公告属性
 *   3. pplType: ReadByte (u8) -> CPPLManager (0x9943b8) 广告/赞助商横幅类型:
 *      - 若 pplType == 1: ReadString(url1), ReadString(url2) (u32 串长, 图片资源路径与跳转链接)
 *      - 若 pplType == 2: ReadWord (u16 LE 参数)
 *   4. msgCount: ReadWord (u16 LE) -> CFxMessage (0x99b808) 滚动系统公告条数:
 *      - 先调用 CFxMessage::ClearMessages (0x68b604)
 *      - 循环 msgCount 次: ReadByte (type), ReadByte (flag/color), ReadString (u32 串长 + text) -> CFxMessage::AddMessage (0x68bb6f)
 *   5. tailStatus: ReadWord (u16 LE) -> 触发 CSelectServerDlg::RefreshNotice (0x4a4afe) 刷新界面公告
 *
 * 块编码: 4B 分组 LE 读 ^0x96CA5395 按 BE 写; 尾部 <4B 按 Encode2(^0xA569) / Encode1(^0x5A)
 *   编码 —— 客户端 sub_75DAF0:744227 的统一反混淆, 由 ssdEncode 全权负责, 本文件只产出纯明文块。
 *   默认空块 21B = 6B 头 + 15B 全零字段 (4+6+1+2+2), 末字节 0x00 编码成 0x5A,
 *   下发与 login.pcap sq=52 / cap/login_s2c_op12.bin 逐字节一致:
 *   a5 65 | 96ca5380 | 96ca438d 96ca5395 96ca5395 96ca5395 96ca5395 5a
 *   (opcode + Encode4(21) + 21B 块)。默认参数即走同一套程序化组装, 不再回放写死的二进制常量。
 */
fun ChannelsNotice(
    noticeTitle: String = "",
    param1: Int = 0,
    param2: Int = 0,
    param3: Int = 0,
    pplType: Int = 0,
    pplUrl1: String = "",
    pplUrl2: String = "",
    pplParam: Int = 0,
    messages: List<NoticeMessage> = emptyList(),
    tailStatus: Int = 0,
) = PKT {
    // 默认空包也走 channelsNoticeBlock 组装; 结果与抓包 21B 基准块逐字节一致
    val blob = ssdEncode(
        channelsNoticeBlock(
            noticeTitle = noticeTitle,
            param1 = param1,
            param2 = param2,
            param3 = param3,
            pplType = pplType,
            pplUrl1 = pplUrl1,
            pplUrl2 = pplUrl2,
            pplParam = pplParam,
            messages = messages,
            tailStatus = tailStatus,
        ),
    )
    Encode4(blob.size)
    EncodeBuffer(blob)
}

/**
 * 组装 Opcode 12 ChannelsNotice SSD 明文资料块
 */
private fun channelsNoticeBlock(
    noticeTitle: String,
    param1: Int,
    param2: Int,
    param3: Int,
    pplType: Int,
    pplUrl1: String,
    pplUrl2: String,
    pplParam: Int,
    messages: List<NoticeMessage>,
    tailStatus: Int,
): ByteArray {
    val out = ByteArrayOutputStream()
    out.u8(0x18)
    out.u8(0) // 占位: (块长 - 5) & 0xFF
    out.u32le(0)

    // 1. 公告横幅标题/文本 (子块 ReadString = u32 LE 串长)
    val titleBytes = noticeTitle.toByteArray(ACP)
    out.u32le(titleBytes.size)
    out.write(titleBytes)

    // 2. 公告属性参数
    out.u16le(param1)
    out.u16le(param2)
    out.u16le(param3)

    // 3. PPL 广告横幅
    out.u8(pplType)
    when (pplType) {
        1 -> {
            val u1 = pplUrl1.toByteArray(ACP)
            out.u32le(u1.size)
            out.write(u1)
            val u2 = pplUrl2.toByteArray(ACP)
            out.u32le(u2.size)
            out.write(u2)
        }
        2 -> {
            out.u16le(pplParam)
        }
    }

    // 4. 系统滚动公告
    out.u16le(messages.size)
    for (m in messages) {
        out.u8(m.type)
        out.u8(m.flag)
        val textBytes = m.text.toByteArray(ACP)
        out.u32le(textBytes.size)
        out.write(textBytes)
    }

    // 5. 尾部刷新标志
    out.u16le(tailStatus)

    val ba = out.toByteArray()
    ba[1] = ((ba.size - 5) and 0xFF).toByte()
    return ba
}

/*
处理函数 `CSelectServerStage::OnPacket` (`0x625702` -> `0x6228a5`, reference/CA.exe.c:506439)，包含：
  1. `strNotice`: `ReadString` (u32 LE 串长) 公告横幅文本 -> `CFxApp` (`0x994d04`)
  2. `p1, p2, p3`: 3 × `ReadWord` 公告参数 -> `CFxApp`
  3. `pplType`: `ReadByte` -> `CPPLManager` (`0x9943b8`) 广告类型 (type 1 为 2 个 URL 字符串, type 2 为 1 个 word)
  4. `msgCount`: `ReadWord` 滚动系统公告数 -> `CFxMessage` (`0x99b808`)，循环读取 `[u8 type, u8 flag, ReadString text]`
  5. `tailStatus`: `ReadWord` -> `CSelectServerDlg::RefreshNotice`
  默认空块 `login.pcap sq=52` 为 21B (6B 头 + 15B 零字段)，由 channelsNoticeBlock 组装，
  线上末字节 0x5A 是 1B 余数分支 Encode1(0) 的结果，不是额外字段。
 */
