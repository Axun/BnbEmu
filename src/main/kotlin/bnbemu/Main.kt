package bnbemu

import bnbemu.netty.NettyServer
import bnbemu.netty.OpcodeManager

fun main() {
    ServerConfig.load()
    OpcodeManager.init()
    Runtime.getRuntime().addShutdownHook(
        Thread({ NettyServer.shutdown() }, "bnbemu-shutdown")
    )
    NettyServer.start()
}
