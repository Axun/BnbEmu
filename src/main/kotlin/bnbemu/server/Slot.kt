package bnbemu.server

import bnbemu.netty.SendPacketBase

const val FLAG_CLOSED = 0x1
const val FLAG_CHIEF = 0x2
const val FLAG_READY = 0x4

/**
 * 槽位在本局内的生命状态。
 * ⚠️ 与 [Slot.state]（meta[0] 的房间旗标 FLAG_CLOSED / CHIEF / READY）无关，那个是房间座位状态。
 */
enum class SlotMatchState {
    /** 本局不参战（空槽）：不参与胜负统计，也不接受阵亡上报 */
    OUT_OF_MATCH,

    /** 参战且存活 */
    ALIVE,

    /** 参战且已阵亡（阵亡已上报；同一槽位后续的重复上报忽略） */
    DEAD
}

class Slot(
    var user: User? = null,
    val meta: ByteArray = ByteArray(3), // [0]:狀態, [1]:角色, [2]:顏色
    var isAI: Boolean = false,
    var AILevel: Int = 1,
    var gridX: Int = 0,
    var gridY: Int = 0,
    // 本局生命状态：开局由 Room.resetMatchState 按"座位上有人"重置，
    // C→S op 154 action=5 时 ALIVE -> DEAD。它同时是阵亡上报的去重位与胜负判定依据。
    // 默认 OUT_OF_MATCH：没进过任何一局的槽位不参战。
    var matchState: SlotMatchState = SlotMatchState.OUT_OF_MATCH
)

var Slot.state: Number
    get() = meta[0]
    set(value) { meta[0] = value.toByte() }

var Slot.bomber: Number
    get() = meta[1]
    set(value) { meta[1] = value.toByte() }

var Slot.color: Number
    get() = meta[2]
    set(value) { meta[2] = value.toByte() }

/** 参战且仍存活（判定对局是否结束用） */
val Slot.isAlive: Boolean
    get() = matchState == SlotMatchState.ALIVE

/** 本局是否参战（不论死活）：空槽恒为 false */
val Slot.isInMatch: Boolean
    get() = matchState != SlotMatchState.OUT_OF_MATCH

fun Slot.Encode(sendPacket: SendPacketBase) {
    sendPacket.EncodeBuffer(meta)
}
