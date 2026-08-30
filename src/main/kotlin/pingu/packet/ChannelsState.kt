package pingu.packet

import pingu.isEN
import pingu.isJP
import pingu.netty.PKT
import pingu.netty.toBA

val channelSize = if (!isJP) 5 else 1
// server = CChannelCache::MakeStatePacket
fun ChannelsState() = PKT {
    if (isEN) {
        // login.pcap sq=49 (38B SSD 資料塊: 5 頻道的開關/狀態, 尾部 Encode2(0) 原樣)
        Encode4(38)
        EncodeBuffer(EN_CHANNELS_STATE_BLOB)
    } else {
        Encode2(channelSize)

        repeat(channelSize) { i ->
            Encode2() // 開啟 = 0 關閉 = -1
        }
    }
}

// 38B SSD 資料塊, 生成工具 tools/extract_login_flow.py
private val EN_CHANNELS_STATE_BLOB = """
    96ca728d 96cf5395 96cb53cd 96cd5395 96ca5394 96cb5392
    96ca5395 96ca5394 96cb5395 a569
""".toBA()
