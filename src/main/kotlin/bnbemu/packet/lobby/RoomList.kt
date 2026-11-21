package bnbemu.packet.lobby

import bnbemu.netty.PKT
import bnbemu.netty.buildSsdBlock
import bnbemu.netty.ssdEncode
import java.io.ByteArrayOutputStream
import bnbemu.packet.ss
import bnbemu.packet.u16le
import bnbemu.packet.u32le
import bnbemu.packet.u8

// ============================================================================
// S→C op 87：RoomList — 房间列表（CLobbyStage::OnPacket case 0x57 → sub_50FF27）
// ============================================================================
// ⚠️ 所有房间条目顺序写入同一个 `roomsStream` 再包一层 SSD 块，绝不是每间房各包一层。
//    `roomsStream` 内部全部采用原生小端序（无 XOR 混淆），房间名 `ss` 为 `u32le(len) + ACP明文`。
//    `CGameSessionSlot +0x20` 由客户端 sub_504E9B 硬编码置 1（sub_44B4D1(this+32, 1)），流中不存在。
//
// server = C???::MakeRoomListPacket (原始名称未知)
fun RoomList(
    rooms: List<RoomEntry> = EN_SAMPLE_ROOMS,
    compress: Boolean = true,
) = PKT {
    Encode2(rooms.size)
    if (rooms.isNotEmpty()) {
        val enc = ssdEncode(buildSsdBlock(roomsStream(rooms), compress = compress))
        Encode4(enc.size)
        EncodeBuffer(enc)
    }
}

/**
 * 房间列表条目（对应客户端 `CGameSessionSlot`，每条目在 `sub_50FF27` + `sub_504E9B` 中顺序读出）。
 *
 * 人数显示公式（`sub_5170DE`）：
 * - 当前人数 = [curPlusClosed] - [closedSlots]
 * - 人数上限 = [maxPlusClosed] - [closedSlots]
 */
data class RoomEntry(
    /** 房间号（`u16le`，`sub_505123` 查找键）：op 83 / op 84 / op 67 / op 76 共用 */
    val roomId: Int,
    /** 会话句柄 / 序号（`u32le`，槽位 `+0x44`）：op 83 写入 `CFxContext`，op 67 / op 76 回传 */
    val handle: Int,
    /** 房间名（明文 ACP 字节，`u32le(len) + rawBytes`，无需 4B 对齐） */
    val name: String,
    /** 是否带密码（`u8`，`+0x48`）：`!= 0` 时弹出密码输入框 */
    val hasPassword: Boolean = false,
    /** 未知属性字节（`u8`，`+0x54`）：抓包见过 `0x00`/`0x90`/`0xA6`/`0x16`/`0x8C` */
    val unk54: Int = 0,
    /** 对局状态（`u8`，`+0x6C`）：`false`=等待中，`true`=游戏中 */
    val isPlaying: Boolean = false,
    /** 未知 `u16le`（`+0x78`）：5 份抓包均为 `0` */
    val unk78: Int = 0,
    /** 当前真人人数 + 封闭槽位数（`u8`，`+0x84`） */
    val curPlusClosed: Int = 1,
    /** 封闭槽位数（`u8`，`+0x90`） */
    val closedSlots: Int = 0,
    /** 总槽位数（含封闭槽位，`u8`，`+0x9C`，通常 `8`） */
    val maxPlusClosed: Int = 8,
    /** 房间类型（`u8`，`+0x60`） */
    val sessionType: Int = SESSION_TYPE_NORMAL,
    /** 地图 ID（`u32le`，`+0xC0`） */
    val mapId: Int = 0,
    /** 未知 `u8`（`+0xA8`）：5 份抓包均为 `0` */
    val unkA8: Int = 0,
    /** 特性 67 开关字节（`u8`，`+0xB4`）：客户端启用特性 67 时多读此 1B，抓包均为 `0` */
    val feature67: Int = 0,
)

/**
 * 组装未加密的房间条目流（纯明文原生小端序）；外层 SSD 块头/压缩由 [buildSsdBlock] 负责，
 * 线上变换由 [ssdEncode] 负责。
 */
internal fun roomsStream(rooms: List<RoomEntry>): ByteArray {
    require(rooms.size <= 0xFFFF) { "RoomList 房间数 ${rooms.size} 超出 u16 上限" }
    val out = ByteArrayOutputStream()
    for (r in rooms) {
        out.u16le(r.roomId)
        out.u32le(r.handle)
        out.ss(r.name)
        out.u8(if (r.hasPassword) 1 else 0)
        out.u8(r.unk54)
        out.u8(if (r.isPlaying) 1 else 0)
        out.u16le(r.unk78)
        out.u8(r.curPlusClosed)
        out.u8(r.closedSlots)
        out.u8(r.maxPlusClosed)
        out.u8(r.sessionType)
        out.u32le(r.mapId)
        out.u8(r.unkA8)
        out.u8(r.feature67)
    }
    return out.toByteArray()
}

// enter-channel.pcap 41B 样本 = "rd" 房（1/1 人，激战模式，mapId=4）——
// RoomList() 默认下发此样本以与 replay_test.py 的 enter 用例逐字节一致。
internal val EN_SAMPLE_ROOMS = listOf(
    RoomEntry(
        roomId = 61,
        handle = 119,
        name = "rd",
        hasPassword = false,
        unk54 = 0x90,
        isPlaying = false,
        unk78 = 0,
        curPlusClosed = 8,
        closedSlots = 7,
        maxPlusClosed = 8,
        sessionType = SESSION_TYPE_BATTLE,
        mapId = 4,
        unkA8 = 0,
        feature67 = 0,
    ),
)
