package bnbemu.handler

import bnbemu.isJP
import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.DecodeEncryptedStr
import bnbemu.netty.PKTHandler
import bnbemu.nextUserId
import bnbemu.packet.LoginResult
import bnbemu.packet.LoginResult_JP
import bnbemu.server.User


// client = CMainSystem::Login | TW_5 = 430A80
// server = CLoginPool::OnLogin
val LoginReq = PKTHandler { c ->
    val userCount = Decode1
    if (userCount in 1..2) {
        repeat(userCount) { i ->
            val name = DecodeEncryptedStr(0x11223344)
            val pw = DecodeEncryptedStr(0x44332211)

            val userId = nextUserId++
            val user = User(c, userId, name)
            c.users.add(user)
        }

        val unk = Decode4 // 0

        val resPacket = if (!isJP) ::LoginResult else ::LoginResult_JP
        c send resPacket(c.users)
    }
}