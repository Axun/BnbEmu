package bnbemu.packet.shop

import bnbemu.netty.PKT

fun DigiQAuthorizationResult(res: Int) = PKT {
    Encode1(res)
    if (res == 12) {
        Encode4()
        Encode4()
    } else {
        EncodeStr()
    }
}