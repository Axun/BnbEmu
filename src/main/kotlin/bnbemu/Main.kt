package bnbemu

import bnbemu.netty.NettyServer
import bnbemu.netty.OpcodeManager


fun main() {
    OpcodeManager.init()
    NettyServer.start()
}