package bnbemu.packet

import bnbemu.ACP
import bnbemu.netty.PKT
import java.io.ByteArrayOutputStream

// server = CCenterProcess::MakeChannelsExtraInfoPacket
fun ChannelsExtraInfo() = PKT {
    // EN op11 = 頻道(伺服器)列表 (StringTableRevisionReq / EnterChannelReq 均回本包, 客戶端據此顯示頻道列表)
    // 塊結構 2026-08-30 由 login.pcap sq=46 (211B) 逐字節逆向, 重編碼與抓包逐位元組一致
    // (replay_test login 用例斷言, IP 改寫位基準 cap/login_s2c_op11_ip127.bin):
    //   整包 = Encode1Bool(true) + Encode4(塊長) + SSD 資料塊
    //   塊明文 = [0x18][(塊長-5)&0xFF][u32 0][u16 頻道數 LE] + N × 頻道條目, 經 ssdEncode 變換下發
    //   塊頭規律在 op1/op2/op11/op12/op13 五個抓包塊全部吻合 (0x18 恆定, 第2字節=塊長-5,
    //   op2 1854B → 0x39 可驗); op1 條目數為 u8, op11/12/13 為 u16 LE
    //   條目 = u32 名長 + 名 + u32 人數上限 + u8 當前人數 + u8 mapNo + u32 IPv4(BE)
    //          + 14B 恆定欄位 + 3B 續讀標記 (欄位語義見 ChannelEntry)
    //   續讀標記: 末條 = Encode2(0)+Encode1(0) 原樣 a5 69 5a (客戶端解碼 0 = 列表結束),
    //             非末條 = 00 00 00 (解碼 ≠ 0 繼續讀) — 此前誤判為「塊尾 3B 對齊尾巴」
    //   Town / SquareCompat 為特殊大地圖頻道 (bigMap=1), 與普通頻道欄位值不同, 並非被關閉
    // 改頻道名單/上限改 EN_CHANNELS 即可; 改 IP 須與 tools/patch_ip.py 同步
    val blob = ssdEncode(channelsExtraBlock(EN_CHANNELS))
    Encode1Bool(true)
    Encode4(blob.size)
    EncodeBuffer(blob)
}

// 抓包頻道 (login.pcap sq=46, 5 頻道) — 重編碼須與原塊逐位元組一致 (replay_test login 用例斷言 op 11/op 13)
// Town / SquareCompat 為特殊大地圖頻道 (bigMap=true), 與普通頻道欄位值不同
// op13 ChannelsState 的條目與本表一一對應 (state/enabled 欄位), 改頻道清單兩包同步生效
internal val EN_CHANNELS = listOf(
    ChannelEntry("PopTag-Forever", maxCount = 150, state = 88),
    ChannelEntry("Ranked", maxCount = 150, curCount = 50, subMapNo = 0x0A, state = 7),
    ChannelEntry("Baby-Channel", maxCount = 150, state = 7),
    ChannelEntry("Town", maxCount = 200, curCount = 1, mapNo = 0x78, bigMap = true),
    ChannelEntry("SquareCompat", maxCount = 200, curCount = 1, mapNo = 0x50, bigMap = true),
)

// 頻道條目 — 欄位由 op11 211B 塊逐位元組解出, 每條目 = 4 + 名長 + 27B, 重編碼與抓包逐位元組一致:
//   name      頻道名 (大廳 UI 顯示, u32 名長 + 名字)
//   maxCount  u32 人數上限 (抓包: 普通頻道 150, 大地圖 200)
//   curCount  u8 當前人數 (抓包: Ranked 50, Town/Square 1, 其餘 0)
//   mapNo     u8 (普通 0x0A / Town 0x78 / Square 0x50; 推測地圖號或房間數, 語義未釘死)
//   ip        頻道伺服器 IPv4 (u32 BE) — 改動須與 tools/patch_ip.py 同步
//   subMapNo  u8 (僅 Ranked = 0x0A, 其餘 0; 語義未知)
//   bigMap    大地圖標記 (Town/Square = 1, 普通頻道 0)
//   state     op13 ChannelsState 條目的 u16 欄 (語義未釘死; 抓包: PopTag-Forever 88 /
//             Ranked 7 / Baby-Channel 7 / Town 0 / SquareCompat 0)
//   enabled   op13 條目的開關旗標
class ChannelEntry(
    val name: String,
    val maxCount: Int,
    val curCount: Int = 0,
    val mapNo: Int = 0x0A,
    val ip: String = "127.0.0.1",
    val subMapNo: Int = 0,
    val bigMap: Boolean = false,
    val state: Int = 0,
    val enabled: Boolean = true,
) {
    fun Encode(out: ByteArrayOutputStream, isLast: Boolean) {
        out.ss(name)
        out.u32le(maxCount)
        out.u8(curCount)
        out.u8(mapNo)
        out.ipv4(ip)
        // 14B 恆定欄位 (抓包原樣, 語義未釘死; bigMap 位於其中第 10 位元組)
        out.u8(0x01)
        out.u8(0x00)
        out.u8(subMapNo)
        out.u8(0x00)
        out.u8(0x00)
        out.u8(0x01)
        out.u8(0x00)
        out.u8(0xC7)
        out.u8(0x01)
        out.u8(0x00)
        out.u8(if (bigMap) 1 else 0)
        out.u8(0x01)
        out.u8(0x00)
        out.u8(0x00)
        // 條目尾續讀標記: 末條 = Encode2(0)+Encode1(0) 原樣 a5 69 5a (客戶端解碼為 0 → 列表結束),
        // 非末條 = 00 00 00 (解碼 0xA569/0x5A ≠ 0 → 繼續讀下一條)
        if (isLast) {
            out.u8(0xA5); out.u8(0x69); out.u8(0x5A)
        } else {
            out.u8(0x00); out.u8(0x00); out.u8(0x00)
        }
    }
}

// 塊明文: [0x18][(塊長-5)&0xFF][u32 0][u16 頻道數 LE] + N × 頻道條目 (塊頭規律見頂部註釋)
private fun channelsExtraBlock(entries: List<ChannelEntry>): ByteArray {
    val out = ByteArrayOutputStream()
    out.u8(0x18)
    out.u8(0) // (塊長-5)&0xFF, 組裝完回填
    out.u32le(0)
    out.u16le(entries.size)
    entries.forEachIndexed { i, e -> e.Encode(out, isLast = i == entries.lastIndex) }
    val ba = out.toByteArray()
    ba[1] = ((ba.size - 5) and 0xFF).toByte()
    return ba
}

// SSD 資料塊變換: 每 dword 以 LE 讀明文、^0x96CA5395、按 BE 寫回; 尾部不足 4B 原樣
// (= tools/extract_first_push.py 的 ssd_encode, 客戶端 ssd_decode 為其逆變換; op13 共用)
internal fun ssdEncode(plain: ByteArray): ByteArray {
    val out = ByteArray(plain.size)
    var i = 0
    while (i + 4 <= plain.size) {
        val le = (plain[i].toInt() and 0xFF) or
                ((plain[i + 1].toInt() and 0xFF) shl 8) or
                ((plain[i + 2].toInt() and 0xFF) shl 16) or
                ((plain[i + 3].toInt() and 0xFF) shl 24)
        val enc = le xor 0x96CA5395.toInt()
        out[i] = (enc ushr 24).toByte()
        out[i + 1] = (enc ushr 16).toByte()
        out[i + 2] = (enc ushr 8).toByte()
        out[i + 3] = enc.toByte()
        i += 4
    }
    while (i < plain.size) {
        out[i] = plain[i]
        i += 1
    }
    return out
}

// ---- 塊拼裝小工具 (塊內欄位皆為明文, 勿用會 XOR 的 Encode1/2/4 DSL; op13 共用) ----

internal fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)

internal fun ByteArrayOutputStream.u16le(v: Int) {
    u8(v); u8(v shr 8)
}

internal fun ByteArrayOutputStream.u32le(v: Int) {
    u8(v); u8(v shr 8); u8(v shr 16); u8(v shr 24)
}

private fun ByteArrayOutputStream.ipv4(ip: String) = ip.split('.').forEach { u8(it.toInt()) } // BE

private fun ByteArrayOutputStream.ss(s: String) {
    val a = s.toByteArray(ACP)
    u32le(a.size)
    write(a)
}
