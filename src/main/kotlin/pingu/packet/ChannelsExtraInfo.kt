package pingu.packet

import pingu.isCN
import pingu.isEN
import pingu.isJP
import pingu.isTW
import pingu.netty.PKT
import pingu.netty.toBA

// server = CCenterProcess::MakeChannelsExtraInfoPacket
fun ChannelsExtraInfo() = PKT {
    if (isEN) {
        // login.pcap sq=46 (211B SSD 資料塊, 解碼 = 5 頻道:
        //   PopTag-Forever / Ranked / Baby-Channel / Town / SquareCompat, 各帶一個 IPv4)
        // 塊內 IP 原抓包為 66.135.19.7, 已改寫 127.0.0.1 (生成工具 tools/extract_login_flow.py)
        Encode1Bool(true)
        Encode4(211)
        EncodeBuffer(EN_CHANNELS_EXTRA_BLOB)
    } else {
        EncodeStr("http://bnb.digicell.com.tw/webpage/BnB/EVENT/20040618/index.htm")
        Encode2(600)
        Encode2(525)
        Encode2(60)

        if (isTW || isCN) {
            Encode1(1) //size
            Encode4(0x7F000001)
            Encode2(9898)

            // NDA
            Encode1(1) //size
            Encode4(0x7F000001)

            // Relay
            Encode1(1) //size
            Encode4(0x7F000001)

            val size = 0
            Encode2(size)
            repeat(size) {
                Encode1()
                Encode1()
                EncodeStr()
            }

            Encode2(15)

            // JUM Server
            if (isTW) {
                Encode1(1)
                Encode4(0x7F000001)
                Encode2(7360)
            }
        } else if (isJP) {
            Encode1(1)
            Encode4(0x7F000001)

            val size = 0
            Encode1(size)
            repeat(size) {
                Encode1()
                Encode1()
                EncodeStr()
            }

            Encode2(15)
        }
    }
}

// 211B SSD 資料塊 (每 dword BE 讀取 ^ 0x96CA5395 後 LE 寫回, 尾部 3B 為 Encode2(0)+Encode1(0) 原樣)
private val EN_CHANNELS_EXTRA_BLOB = """
    96ca9d8d 96cf5395 96ca539b c2ba3cc5 d0e734f4 e0af21fa
    965c21f0 9cca5395 97ca53ea 96ca5394 51ca5295 97ca5394
    96ca5395 96ca5595 f8ab0195 00ae36fe a4ca5395 96ca2c9f
    9cca5294 96cb5395 96ca5252 96ca5394 96c65395 f7885395
    d5e72af7 f8a432fd 965c3ff0 9cca5395 97ca53ea 96ca5394
    51ca5295 97ca5394 96ca5395 96ca5795 e1a50795 96ca9bfb
    e9b25295 97cb5395 96ca5395 970d5394 96cb5295 96ca5395
    96ca5399 f7bf22c6 f98936e7 e2ab23f8 96ca535d 96b50394
    96cb5295 97ca5395 96cb9495 96ca5294 a5695a
""".toBA()
