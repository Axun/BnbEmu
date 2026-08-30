package pingu.packet

import pingu.netty.PKT

// server = CConnectPool::OnCenterResConnEstablished
fun EnableShop() = PKT {
    Encode1Bool(true)
}