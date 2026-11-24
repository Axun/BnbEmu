package bnbemu

import bnbemu.netty.NettyServer
import bnbemu.netty.OpcodeManager
import bnbemu.repository.SqlitePlayerRepository
import bnbemu.service.PlayerService

fun main() {
    ServerConfig.load()
    OpcodeManager.init()
    PlayerService.repository = SqlitePlayerRepository(ServerConfig.current.database.path)
    Runtime.getRuntime().addShutdownHook(
        Thread({ NettyServer.shutdown() }, "bnbemu-shutdown")
    )
    NettyServer.start()
}
