package bnbemu.packet

import bnbemu.netty.PKT
import java.io.ByteArrayOutputStream


// server = CChannelCache::MakeStatePacket
fun ChannelsState() = PKT {
    // EN op13 = 各頻道開關/狀態 (login.pcap sq=49, 38B; StringTableRevisionReq / EnterChannelReq 回整套推送,
    // 客戶端另在頻道列表界面以 op 15 ChannelsStateReq 輪詢刷新)
    // 條目與 op11 的 EN_CHANNELS 一一對應 (state/enabled 欄位定義在 ChannelEntry),
    // 重編碼與抓包逐位元組一致 (replay_test login 用例斷言 op 13); 結構:
    //   整包 = Encode4(塊長) + SSD 資料塊 (op13 無 Encode1Bool 開頭)
    //   塊明文 = [0x18][(塊長-5)&0xFF][u32 0][u16 頻道數 LE] + N × 條目, 經 ssdEncode 變換下發
    //            (塊頭規律、SSD 變換見 ChannelsExtraInfo.kt 頂部註釋)
//   條目 = [u16 LE state][u8 enabled][u8 0] + 2B 續讀標記
    val blob = ssdEncode(channelsStateBlock(EN_CHANNELS))
    Encode4(blob.size)
    EncodeBuffer(blob)
}

// op13 塊明文組裝: 塊頭 + N × ([u16 LE state][u8 enabled][u8 0] + 續讀標記)
private fun channelsStateBlock(entries: List<ChannelEntry>): ByteArray {
    val out = ByteArrayOutputStream()
    out.u8(0x18)
    out.u8(0) // (塊長-5)&0xFF, 組裝完回填
    out.u32le(0)
    out.u16le(entries.size)
    for (e in entries) {
        out.u16le(e.state)
        out.u8(if (e.enabled) 1 else 0)
        out.u8(0)
        // 尾標記明文一律 0
        out.u8(0x00); out.u8(0x00)
    }
    val ba = out.toByteArray()
    ba[1] = ((ba.size - 5) and 0xFF).toByte()
    return ba
}
