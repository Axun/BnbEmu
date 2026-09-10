package pingu.packet

import pingu.ACP
import pingu.netty.PKT
import pingu.netty.toBA
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
 * SSD 数据块结构:
 * - 块头 (5字节): [0x18][(len-5)&0xFF][u32 0] (标准 SSD 块头)
 * - 块体字段 (明文按顺序):
 *   1. noticeTitle: ReadString (u16 LE 长度 + 文本) -> 存入 CFxApp (0x994d04) 横幅公告缓冲区 (0x68357f, 偏移 +0x360)
 *   2. param1, param2, param3: 3 × ReadWord (u16 LE) -> 存入 CFxApp (+0x560, +0x564, +0x568) 公告属性
 *   3. pplType: ReadByte (u8) -> CPPLManager (0x9943b8) 广告/赞助商横幅类型:
 *      - 若 pplType == 1: ReadString(url1), ReadString(url2) (图片资源路径与跳转链接)
 *      - 若 pplType == 2: ReadWord (u16 LE 参数)
 *   4. msgCount: ReadWord (u16 LE) -> CFxMessage (0x99b808) 滚动系统公告条数:
 *      - 先调用 CFxMessage::ClearMessages (0x68b604)
 *      - 循环 msgCount 次: ReadByte (type), ReadByte (flag/color), ReadString (text) -> CFxMessage::AddMessage (0x68bb6f)
 *   5. tailStatus: ReadWord (u16 LE) -> 触发 CSelectServerDlg::RefreshNotice (0x4a4afe) 刷新界面公告
 *
 * 默认空包 (login.pcap sq=52, 21B):
 *   [0x18][0x10][0x00×4][0x00×15] -> noticeTitle="", p1=0, p2=0, p3=0, pplType=0, msgCount=0, tailStatus=0
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
    // 若为默认空包，直接下发抓包验证过的 21 字节基准块 (与 login.pcap sq=52 逐字节一致)
    if (noticeTitle.isEmpty() && param1 == 0 && param2 == 0 && param3 == 0 &&
        pplType == 0 && messages.isEmpty() && tailStatus == 0
    ) {
        Encode4(21)
        EncodeBuffer(EN_CHANNELS_NOTICE_EMPTY_BLOB)
    } else {
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
            )
        )
        Encode4(blob.size)
        EncodeBuffer(blob)
    }
}

private val EN_CHANNELS_NOTICE_EMPTY_BLOB = """
    96ca438d 96ca5395 96ca5395 96ca5395 96ca5395 5a
""".toBA()

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

    // 1. 公告横幅标题/文本
    val titleBytes = noticeTitle.toByteArray(ACP)
    out.u16le(titleBytes.size)
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
            out.u16le(u1.size)
            out.write(u1)
            val u2 = pplUrl2.toByteArray(ACP)
            out.u16le(u2.size)
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
        out.u16le(textBytes.size)
        out.write(textBytes)
    }

    // 5. 尾部刷新标志
    out.u16le(tailStatus)

    val ba = out.toByteArray()
    ba[1] = ((ba.size - 5) and 0xFF).toByte()
    return ba
}

/*
处理函数 `CSelectServerStage::OnPacket` (`0x625702` -> `0x6228a5`)，包含：
  1. `strNotice`: `ReadString` 公告横幅文本 -> `CFxApp` (`0x994d04`)
  2. `p1, p2, p3`: 3 × `ReadWord` 公告参数 -> `CFxApp`
  3. `pplType`: `ReadByte` -> `CPPLManager` (`0x9943b8`) 广告类型 (type 1 为 2 个 URL 字符串, type 2 为 1 个 word)
  4. `msgCount`: `ReadWord` 滚动系统公告数 -> `CFxMessage` (`0x99b808`)，循环读取 `[u8 type, u8 flag, ReadString text]`
  5. `tailStatus`: `ReadWord` -> `CSelectServerDlg::RefreshNotice`
  默认空块 `login.pcap sq=52` 为 21 字节全零空块，
 */
