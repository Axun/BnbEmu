package pingu.handler

import pingu.isCN
import pingu.isTW
import pingu.netty.Decode1
import pingu.netty.Decode4
import pingu.netty.DecodeStr
import pingu.netty.PKTHandler
import pingu.packet.EnableShop
import pingu.packet.EnterLobbyStage
import pingu.packet.EnterShopStage
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