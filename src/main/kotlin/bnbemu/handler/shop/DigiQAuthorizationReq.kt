package bnbemu.handler.shop

import bnbemu.netty.DecodeStr
import bnbemu.netty.PKTHandler
import bnbemu.packet.shop.DigiQAuthorizationResult

val DigiQAuthorizationReq = PKTHandler { c ->
    val acc = DecodeStr
    val pw = DecodeStr

    val res = 12
    c send DigiQAuthorizationResult(res)
}