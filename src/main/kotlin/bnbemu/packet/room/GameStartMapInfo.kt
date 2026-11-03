package bnbemu.packet.room

import bnbemu.netty.PKT
import bnbemu.server.Room
import bnbemu.server.bomber

/**
 * S→C op 112: GameStartMapInfo (對局地圖與槽位出生點/角色初始化)
 *
 * 逆向確認:
 *   CA.exe 0x0051F29C (CPrepareStage::OnPacket -> Case 112).
 *   客戶端在開局序列 (op 92 StartGameReq) 後接收:
 *     [111 PracticeAiLevel, 112 GameStartMapInfo, 100 PracticeStartInfo, 136 PracticeBoard].
 *   若本地無此地圖或校驗不通過, 客戶端彈窗 "InvalidMapReturnToLobby" (0x51F7B0) 退回大廳.
 *
 * 封包體欄位結構 (165B body, 抓包 practice_s2c_op112_167B_0.bin):
 *   - Encode4: randomSeed (局內隨機種子, 如 0x7E022E6B, 設入 CRandomGenerator::SetSeed)
 *   - Encode2: themeId (地圖主題編號, 如 29)
 *   - EncodeCipherStr: mapName (地圖資源名, 經 0x75daf0 加密為 10B "Cemetery04")
 *   - Encode4: mapId (地圖 ID, 如 176)
 *   - Encode1: gameOptions (遊戲參數, 如 15)
 *   - Encode1: flag (標誌位, 0)
 *   - Encode1: slotCount (槽位數量, 8)
 *   - 8 個槽位配置 (每個槽位 17B):
 *       - Encode1: slotIdx (0..7)
 *       - Encode1: spawnPos (出生點編號, 0x687415 SetSlotSpawnPos)
 *       - Encode1: bomber (角色 ID, 如 5, 0x6860bd SetSlotBomber)
 *       - Encode1: teamFlag (隊伍標誌, 0=玩家, 8=AI, 0x6873f5 SetSlotTeam)
 *       - Encode1: state (0)
 *       - Encode4: param1 (0)
 *       - Encode4: param2 (0)
 *       - Encode4: param3 (0)
 *   - Encode1: obsCount (觀戰人數, 0)
 *   - Encode1: field1 (0)
 *   - Encode1: field2 (0)
 *   - Encode1: timeLimitSec (限制時間秒數, 若 >0 則乘 1000ms 設為對局時限)
 */
fun GameStartMapInfo(
    randomSeed: Int = 0x7E022E6B,
    themeId: Int = 29,
    mapName: String = "Cemetery04",
    mapId: Int = 176,
    gameOptions: Int = 15,
    flag: Int = 0,
    spawnPositions: IntArray = intArrayOf(2, 1, 6, 0, 3, 4, 5, 7),
    obsCount: Int = 0,
    field1: Int = 0,
    field2: Int = 0,
    timeLimitSec: Int = 0
) = PKT {
    Encode4(randomSeed)
    Encode2(themeId)
    EncodeCipherStr(mapName)
    Encode4(mapId)
    Encode1(gameOptions)
    Encode1(flag)
    Encode1(8)

    for (i in 0 until 8) {
        val slot = Room.slots.getOrNull(i)
        val bomberId = slot?.bomber?.toInt()?.let { if (it == 0) 5 else it } ?: 5
        val team = if (i == 0) 0 else 8
        val spawn = if (i < spawnPositions.size) spawnPositions[i] else i

        Encode1(i)
        Encode1(spawn)
        Encode1(bomberId)
        Encode1(team)
        Encode1(0)
        Encode4(0)
        Encode4(0)
        Encode4(0)
    }

    Encode1(obsCount)
    Encode1(field1)
    Encode1(field2)
    Encode1(timeLimitSec)
}
