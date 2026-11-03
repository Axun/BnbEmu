package bnbemu.packet.room

import bnbemu.netty.PKT

// CPrepareStage::OnPacket (0x52a949 -> Case 108: 0x51efe0)
// 解除 [esi + 0x2f78] 选图等待锁并刷新房间地图 UI (0x51d9fb)
// 结构: Encode2(result) + Encode2(themeId) + Encode4(mapId)
// 抓包参照: cap/practice_s2c_op108_10B_0.bin (E2(0) E2(0) E4(172))
fun SelectMap(result: Int = 0, themeId: Int = 0, mapId: Int) = PKT {
    Encode2(result)
    Encode2(themeId)
    Encode4(mapId)
}

// 兼容单参数调用 (默认 result=0, themeId=0)
fun SelectMap(mapId: Int) = SelectMap(result = 0, themeId = 0, mapId = mapId)
