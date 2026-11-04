package bnbemu.handler

import bnbemu.isCN
import bnbemu.isEN
import bnbemu.isTW
import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.DecodeStr
import bnbemu.netty.PKTHandler
import bnbemu.packet.ChannelsGameState
import bnbemu.packet.ChannelsState
import bnbemu.packet.DynamicMapInfo
import bnbemu.packet.EnterLobbyStage
import bnbemu.packet.EnterShopStage
import bnbemu.packet.RoomList
import bnbemu.packet.UserDynamicInfo
import bnbemu.packet.Unk423
import bnbemu.packet.PermissionInfo
import bnbemu.packet.ChangeStage
import bnbemu.server.User
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
                // 回應批次 (enter-channel.pcap 線序 seq=43~61): [48 PermissionInfo 空體, 13 ChannelsState,
                //   14 ChannelsGameState, 97 ChangeStage] + [122 UserDynamicInfo (金幣同步), 87 RoomList, 423 週期推送]
                // 之後客戶端跟發 297 / 302 / 310 / 64 / 293 / 78 批次, 整套回應由 StageInitReq (op 78) 觸發
                // 批次後還有自動空包 81 (⚠️ 非進房, 見 JoinSessionReq.kt 註釋) 與 274/281/276;
                // 大廳期 S 週期推送 [413 UserInventoryInfo, 198, 200, 274 UserItemUpdate×2, 212 FxConfig, 210, 87, 65, 204, 66, 14, 20, 261 ShopVersion, 247, 423]
                // 及 S→C 81 名冊/72 交替
                c.send(
                    PermissionInfo(),
                    ChannelsState(),
                    ChannelsGameState(),
                    ChangeStage(),
                    UserDynamicInfo(),
                    RoomList(),
                    Unk423(),
                )
            }
            inGameServer -> {
                val checksum = Decode4 xor Decode4
                Decode1 // 1
                c.send(
                    DynamicMapInfo(),
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