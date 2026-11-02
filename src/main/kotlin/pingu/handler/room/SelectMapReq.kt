package pingu.handler.room

import pingu.netty.Decode1
import pingu.netty.Decode2
import pingu.netty.PKTHandler
import pingu.packet.ChangeStage
import pingu.packet.room.SelectMap
import pingu.server.Room

// CGameSession::OnSelectMap / CStage::OnStageStateSync (0x004F73B2)
val SelectMapReq = PKTHandler { c ->
    if (readableBytes() >= 2) {
        val map = Decode2
        println("mapId: $map")
        Room.mapId = map
        Room BC SelectMap(map)
    } else if (readableBytes() >= 1) {
        val stageState = Decode1
        if (stageState == 0) {
            // 客户端退出商城/当前舞台请求 (0x005CDAFB 传入 0)
            // 服务端应答 S->C op 97 ChangeStage(11), 驱动客户端切回大厅舞台 (CLobbyStage) 并自动发送 op 78 StageInitReq
            c send ChangeStage(11)
        }
    }
}
