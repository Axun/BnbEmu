package pingu.packet

import pingu.Ver
import pingu.isEN
import pingu.netty.PKT

// 握手
// client = CMainSystem::OnConnect
// server = CMainSystem::OnConnEstablished
fun ConnEstablished() = PKT {
    if (isEN) {
        // 真實伺服器 banner 內容, 20B 明文 (start/login/enter-channel.pcap 三次抓包逐位元組一致)
        EncodeBuffer("0000120012000000000100000000000037000000")
    } else {
        Encode2() // 好像是能更新的最低版本
        Encode2(Ver)
        Encode4() //  ReceivedPacket Opcode offset
        EncodeStr() // PatchURL

        // NDA
//        if (isNA) {
//            Encode1Bool()
//        }
    }
}