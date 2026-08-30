package pingu.handler

import pingu.isCN
import pingu.isEN
import pingu.isTW
import pingu.netty.Decode1
import pingu.netty.Decode4
import pingu.netty.DecodeStr
import pingu.netty.PKTHandler
import pingu.packet.ChannelsGameState
import pingu.packet.ChannelsState
import pingu.packet.EnableShop
import pingu.packet.EnterLobbyStage
import pingu.packet.EnterShopStage
import pingu.packet.RoomList
import pingu.packet.Unk122
import pingu.packet.Unk423
import pingu.packet.Unk48
import pingu.packet.Unk97
import pingu.server.User
import java.net.InetSocketAddress

// 進頻道後第一個包
// server = CConnectPool::OnConnEstablished | CMainPool::OnConnEstablished
val OnConnEstablished = PKTHandler { c ->
    val port = (c.ch.localAddress() as InetSocketAddress).port
    val inGameServer = port == 4848
    val inShop = port == 4849

    val userCount = Decode1
    if (userCount in 1..2) {
        repeat(userCount) { i ->
            val name = DecodeStr
            val userId = Decode4

            if (isTW) {
                Decode4 // 0
                if (inShop) {
                    Decode4 // 0
                }
            }

            if (isCN && inGameServer) {
                Decode4
            }

            c.users.add(
                User(c, userId, name)
            )
        }

        when {
            isEN -> {
                // EN 單埠拓撲: op 6 ReqConnectToSvr 回 3838 後, 客戶端新開連線並以 op 7 為首包
                // (enter-channel.pcap stream 1, uid=230)
                // EN payload = E1(1) + [E2(4)+4B會話串] + E4(639) + E4(0) + 00000000(原樣)
                //   + 71b18589(原樣,hash?) + E1(0) + E1(1) + 頻道hash×2(與 op 15 內一致) + E1(1)
                //   (前段 userCount/name/userId 已由上方共通解析邏輯吃掉, name 即會話串)
                // 回應批次 (enter-channel.pcap 線序 seq=43~61): [48 空體, 13 ChannelsState,
                //   14 ChannelsGameState, 97] + [122(疑似5s定時器), 87, 423 週期推送]
                // 之後客戶端跟發 297 / 302 / 310 / 64 / 293 / 78 批次, 整套回應由 Unk_78 觸發
                // (297/302/310/64/293 之外的回應尚未實作: 進房 81 之後的 413/198/200/274/212 等)
                c.send(
                    Unk48(),
                    ChannelsState(),
                    ChannelsGameState(),
                    Unk97(),
                    Unk122(),
                    RoomList(),
                    Unk423(),
                )
            }
            inGameServer -> {
                val checksum = Decode4 xor Decode4
                Decode1 // 1
                c.send(
                    EnableShop(),
                    EnterLobbyStage()
                )
            }
            inShop -> {
                Decode1 // 1
                c send EnterShopStage()
            }
        }
    }
}