package bnbemu.packet

import bnbemu.ACP
import bnbemu.netty.PKT
import bnbemu.netty.SendPacketBase

// 频道在线用户列表 — op 72 (enter-channel.pcap 线序 seq=73)
// 条目 = E2(len)+角色名令牌(encodeName) + 16 个固定栏位; JoinSessionResult 以同一令牌指名进房玩家
fun SessionList(entries: List<SessionEntry> = EN_SAMPLE_ENTRIES) = PKT {
    Encode2(0)
    Encode1(entries.size)
    entries.forEach { it.Encode(this) }
}

// 角色名令牌编码: 4B 一块 LE 读 ^0x96CA5395 按 BE 写; 尾部 2B LE 读 ^0xA569 按 BE 写, 1B ^0x5A;
// 3B 尾 = 2B 尾 + 1B 尾 (2026-08-30 由 kcworm1 7B 名对出, 与 4/5/6B 名样本回编码逐字节一致)
// (op 81 JoinSessionResult 的名冊令牌同款, internal 供共用)
internal fun encodeName(name: String): ByteArray {
    val src = name.toByteArray(ACP)
    val dst = ByteArray(src.size)
    var i = 0
    while (i + 4 <= src.size) {
        val le = (src[i].toInt() and 0xFF) or
                ((src[i + 1].toInt() and 0xFF) shl 8) or
                ((src[i + 2].toInt() and 0xFF) shl 16) or
                ((src[i + 3].toInt() and 0xFF) shl 24)
        val enc = le xor 0x96CA5395.toInt()
        dst[i] = (enc ushr 24).toByte()
        dst[i + 1] = (enc ushr 16).toByte()
        dst[i + 2] = (enc ushr 8).toByte()
        dst[i + 3] = enc.toByte()
        i += 4
    }
    while (i < src.size) {
        if (src.size - i >= 2) {
            val le = (src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8)
            val enc = le xor 0xA569
            dst[i] = (enc ushr 8).toByte()
            dst[i + 1] = enc.toByte()
            i += 2
        } else {
            dst[i] = (src[i].toInt() xor 0x5A).toByte()
            i += 1
        }
    }
    return dst
}

/**
 * [encodeName] 的逆變換：4B 一塊 BE 讀 ^0x96CA5395 按 LE 寫回, 尾 2B BE 讀 ^0xA569 按 LE 寫回, 尾 1B ^0x5A。
 *
 * 用途：C→S 的字串欄位（聊天文字 op 18、會話令牌 op 297 等）客戶端也是用同一套令牌變換送出的
 * （實測 "asf" → D6 08 3C），伺服器要拿到明文才能處理/轉發。
 */
internal fun decodeName(token: ByteArray): String {
    val dst = ByteArray(token.size)
    var i = 0
    while (i + 4 <= token.size) {
        val be = ((token[i].toInt() and 0xFF) shl 24) or
                ((token[i + 1].toInt() and 0xFF) shl 16) or
                ((token[i + 2].toInt() and 0xFF) shl 8) or
                (token[i + 3].toInt() and 0xFF)
        val dec = be xor 0x96CA5395.toInt()
        dst[i] = dec.toByte()
        dst[i + 1] = (dec ushr 8).toByte()
        dst[i + 2] = (dec ushr 16).toByte()
        dst[i + 3] = (dec ushr 24).toByte()
        i += 4
    }
    while (i < token.size) {
        if (token.size - i >= 2) {
            val be = ((token[i].toInt() and 0xFF) shl 8) or (token[i + 1].toInt() and 0xFF)
            val dec = be xor 0xA569
            dst[i] = dec.toByte()
            dst[i + 1] = (dec ushr 8).toByte()
            i += 2
        } else {
            dst[i] = (token[i].toInt() xor 0x5A).toByte()
            i += 1
        }
    }
    return String(dst, ACP)
}

class SessionEntry(
    val name: String,
    val level: Int,
    val maxCount: Int = 50,
    val listItemBg: Int = 0,
    val grade: Int = 0,
    val state2: Int = 0,
) {
    fun Encode(p: SendPacketBase) {
        val token = encodeName(name)
        p.Encode2(token.size)
        p.EncodeBuffer(token)
        p.Encode1(0)
        p.Encode1(grade)
        p.Encode4(0)
        p.Encode1(0)
        p.Encode4(0)
        p.Encode2(level)
        p.Encode4(maxCount)
        p.Encode4(listItemBg)
        p.Encode4(0)
        p.Encode4(0)
        p.Encode1(0)
        p.Encode4(0)
        p.Encode2(0)
        p.Encode2(state2)
        p.Encode2(0)
        p.Encode2(0)
    }
}

// 抓包样本 (8 玩家) — 重编码须与原载荷逐字节一致 (replay_test enter 用例断言 op 72)
private val EN_SAMPLE_ENTRIES = listOf(
    SessionEntry("Kuso", level = 326, listItemBg = 16),
    SessionEntry("Lotso", level = 317, listItemBg = 17),
    SessionEntry("Jacko", level = 112),
    SessionEntry("suan", level = 87),
    SessionEntry("Hello", level = 69),
    SessionEntry("Mirage", level = 15, grade = 1, state2 = 1),
    SessionEntry("Static", level = 1),
    SessionEntry("ffdf", level = 1),
)
