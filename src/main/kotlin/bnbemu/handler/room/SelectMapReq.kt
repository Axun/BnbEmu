package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.ChangeStage
import bnbemu.packet.room.SelectMap
import bnbemu.server.Room

// CPrepareStage 发送选图请求 (CA.exe 0x51c60f): Encode2(themeId) + Encode4(mapId)
// 服务端应答 S->C op 108 SelectMap: Encode2(result=0) + Encode2(themeId) + Encode4(mapId)
// 客户端 0x51efe0 收到后清除 [esi + 0x2f78] 选图等待锁并刷新房间地图 UI (0x51d9fb)
// 兼容: CStage::OnStageStateSync (0x004F73B2) 退出当前舞台请求: Encode1(0)
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
    } else if (readableBytes() >= 1) {
        val stageState = Decode1
        if (stageState == 0) {
            // 客户端退出商城/当前舞台请求 (0x005CDAFB 传入 0)
            // 服务端应答 S->C op 97 ChangeStage(11), 驱动客户端切回大厅舞台 (CLobbyStage) 并自动发送 op 78 StageInitReq
            c send ChangeStage(11)
        }
    }
}
