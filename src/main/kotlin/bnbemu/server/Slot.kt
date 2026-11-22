package bnbemu.server

import bnbemu.netty.ClientSocket
import bnbemu.netty.SendPacketBase
import bnbemu.codec.ssdEncode

const val FLAG_CLOSED = 0x1
const val FLAG_CHIEF = 0x2
const val FLAG_READY = 0x4
/** 观察者/隐身位：`((meta[0] >> 2) & 7) == 7`，对应位掩码 0x1C (CA.exe sub_51FB37/sub_524A22) */
const val FLAG_OBSERVER = 0x1C

const val SLOT_BOMBER_MASK = 0x3F
const val SLOT_COLOR_MASK = 0x1F

const val DEFAULT_AI_LEVEL = 2

class Slot(
    @Volatile
    var playerId: Int? = null,
    var client: ClientSocket? = null,
    val meta: ByteArray = ByteArray(3), // [0]:狀態, [1]:角色, [2]:顏色
    var isAI: Boolean = false,
    var aILevel: Int = DEFAULT_AI_LEVEL,
) {
    val isHuman: Boolean
        get() = playerId != null

    fun assignHuman(playerId: Int?, socket: ClientSocket? = null) {
        this.playerId = playerId
        client = socket
    }

    fun clearHuman() {
        playerId = null
        client = null
    }
}

var Slot.state: Number
    get() = meta[0]
    set(value) { meta[0] = value.toByte() }

var Slot.bomber: Number
    get() = meta[1]
    set(value) { meta[1] = value.toByte() }

var Slot.color: Number
    get() = meta[2]
    set(value) { meta[2] = value.toByte() }

fun Slot.Encode(sendPacket: SendPacketBase) {
    sendPacket.EncodeBuffer(meta)
}

/**
 * 按客户端 `sub_78E290 → sub_75DAF0` 规则编码 3 字节房间槽位元数据：
 * - `state`  -> `meta[0]`：bit0=`FLAG_CLOSED(0x01)` 封闭，bit1=`FLAG_CHIEF(0x02)` 房主，
 *   bit2..4=`(state >> 2) & 7`（`0`=等待、`1`=`FLAG_READY(0x04)` 已准备/对局中、`7`=`FLAG_OBSERVER(0x1C)` 观察者）
 * - `bomber` -> `meta[1]`：低 6 位（`& 0x3F`）角色 ID 0..63（13=随机）
 * - `color`  -> `meta[2]`：低 5 位（`& 0x1F`）颜色/队伍 ID 0..31
 */
fun SendPacketBase.EncodeSlotMeta(
    state: Int = 0,
    bomber: Int = 0,
    color: Int = 0,
) {
    EncodeBuffer(
        ssdEncode(
            byteArrayOf(
                (state and 0xFF).toByte(),
                (bomber and SLOT_BOMBER_MASK).toByte(),
                (color and SLOT_COLOR_MASK).toByte(),
            )
        )
    )
}

fun SendPacketBase.EncodeSlotMeta(slot: Slot) {
    EncodeSlotMeta(
        state = slot.state.toInt(),
        bomber = slot.bomber.toInt(),
        color = slot.color.toInt(),
    )
}
