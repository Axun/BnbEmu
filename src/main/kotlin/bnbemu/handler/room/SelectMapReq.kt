package bnbemu.handler.room

import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.SelectMap
import bnbemu.server.Room
import org.tinylog.kotlin.Logger

// CPrepareStage 发送选图请求 (CA.exe 0x51c60f): Encode2(themeId) + Encode4(mapId)
// 服务端应答 S->C op 108 SelectMap: Encode2(result=0) + Encode2(themeId) + Encode4(mapId)
// 客户端 0x51efe0 收到后清除 [esi + 0x2f78] 选图等待锁并刷新房间地图 UI (0x51d9fb)
val SelectMapReq = PKTHandler { c ->
    if (readableBytes() >= 6) {
        val themeId = Decode2
        val mapId = Decode4
        println("SelectMapReq -> themeId: $themeId, mapId: $mapId")
        Room.themeId = themeId
        Room.mapId = mapId
        Room BC SelectMap(result = 0, themeId = themeId, mapId = mapId)
    } else if (readableBytes() >= 2) {
        val map = Decode2
        println("SelectMapReq (legacy 2B) -> mapId: $map")
        Room.mapId = map
        Room BC SelectMap(result = 0, themeId = 0, mapId = map)
    } else {
        Logger.warn("SelectMapReq unknown data")
    }
}
