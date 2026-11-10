package bnbemu.handler

import bnbemu.debugMode
import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.DecodeTokenStr
import bnbemu.netty.PKTHandler
import bnbemu.packet.ChannelsGameState
import bnbemu.packet.ChannelsState
import bnbemu.packet.RoomList
import bnbemu.packet.UserDynamicInfoValue
import bnbemu.packet.WelcomeNotice
import bnbemu.packet.PermissionInfo
import bnbemu.packet.ChangeStage
import bnbemu.packet.ClientStage
import bnbemu.server.User
import org.tinylog.kotlin.Logger

// 進頻道後第一個包
// server = CConnectPool::OnConnEstablished | CMainPool::OnConnEstablished
val OnConnEstablished = PKTHandler { c ->
    val userCount = if (readableBytes() >= 1) Decode1 else 0
    if (userCount in 1..2) {
        repeat(userCount) {
            val name = DecodeTokenStr
            val userId = Decode4
            val userReserved = if (readableBytes() >= 4) Decode4 else 0

            c.users.add(
                User(c, userId, name)
            )
        }

        // 8B 校验码 (00 00 00 00 71 B1 85 89)
        val authChecksumHigh = if (readableBytes() >= 4) readInt() else 0
        val authChecksumLow = if (readableBytes() >= 4) readInt() else 0

        // 标志位 (0x5A -> 0, 0x5B -> 1)
        val flag0 = if (readableBytes() >= 1) Decode1 else 0
        val flag1 = if (readableBytes() >= 1) Decode1 else 0

        // 双频道 Hash (0x84E960B7 -> 0x12233322, 与 op 15 ChannelsStateReq 携带的双 hash 一致)
        val channelHash1 = if (readableBytes() >= 4) Decode4 else 0
        val channelHash2 = if (readableBytes() >= 4) Decode4 else 0

        // 尾部标志 (0x5B -> 1)
        val tailFlag = if (readableBytes() >= 1) Decode1 else 0

        if (debugMode) {
            Logger.info("[OnConnEstablished] userCount=$userCount, users=${c.users.map { "${it.id}:${it.name}" }}, auth=(0x${Integer.toHexString(authChecksumHigh)}, 0x${Integer.toHexString(authChecksumLow)}), flags=($flag0, $flag1), channelHash=(0x${Integer.toHexString(channelHash1)}, 0x${Integer.toHexString(channelHash2)}), tail=$tailFlag")
        }

        // EN 單埠拓撲: op 6 ReqConnectToSvr 回 3838 後, 客戶端新開連線並以 op 7 為首包
        // (enter-channel.pcap stream 1, uid=230)
        // 回應批次 (enter-channel.pcap 線序 seq=43~61): [48 PermissionInfo 空體, 13 ChannelsState,
        //   14 ChannelsGameState, 97 ChangeStage] + [122 UserDynamicInfoValue (金幣同步), 87 RoomList, 423 週期推送]
        // 之後客戶端跟發 297 / 302 / 310 / 64 / 293 / 78 批次, 整套回應由 StageInitReq (op 78) 觸發
        // 批次後還有自動空包 81 (⚠️ 非進房, 見 JoinSessionReq.kt 註釋) 與 274/281/276;
        // 大廳期 S 週期推送 [413 UserInventoryInfo, 198, 200, 274 UserItemUpdate×2, 212 FxConfig, 210, 87, 65, 204, 66, 14, 20, 261 ShopVersion, 247, 423]
        // 及 S→C 81 名冊/72 交替
        c.send(
            PermissionInfo(),
            ChannelsState(),
            ChannelsGameState(),
            ChangeStage(),
            UserDynamicInfoValue(),
            RoomList(),
            WelcomeNotice(),
        )
        // 这条 ChangeStage() 默认把客户端切进大厅 (stage 11); 记录为「进商城前的来源舞台」基准
        c.stage = ClientStage.Lobby.value
    }
}
