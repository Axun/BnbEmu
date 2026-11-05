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
 *   - Encode1: slotCount (本局參戰槽位數)
 *   - slotCount 個槽位配置 (每個槽位 17B):
 *       - Encode1: slotIdx (0..7, 真實槽號; 客戶端 0x51F53D 用這個值索引, 不是迴圈序號)
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
 *
 * ⚠️ 這份名冊就是「開局時有幾個電腦」的唯一來源: CA.exe 0x0051F522 讀 slotCount,
 *    0x0051F52A 起按 slotCount 讀 17B 紀錄並逐槽 SetSlotSpawnPos / SetSlotBomber /
 *    SetSlotTeam, 之後 CGameStage 才依這份資料建立 bomber。
 *    房間階段 op 89 踢掉的 AI 只改了 CPrepareStrategyAIBattle 的本地槽位,
 *    若這裡仍固定發 8 筆 (1..7 全 team=8), 開局就會把 7 個 AI 原樣重建回來。
 *    因此由 [StartGameReq] 把 Room.slots 中「有人或 isAI」的真實槽號傳進 activeSlots;
 *    預設值 0..7 保留給抓包重放/單元測試, 與 cap/practice_s2c_op112_167B_0.bin 逐位元組一致。
 */
fun GameStartMapInfo(
    randomSeed: Int = 0x7E022E6B,
    themeId: Int = 29,
    mapName: String = "Cemetery04",
    mapId: Int = 176,
    gameOptions: Int = 15,
    flag: Int = 0,
    spawnPositions: IntArray = intArrayOf(2, 1, 6, 0, 3, 4, 5, 7),
    activeSlots: List<Int> = (0..7).toList(),
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

    // 只發「本局真的參戰」的槽位。槽號不重排 (踢掉 3 號就跳過 3 號),
    // spawnPositions 也按真實槽號取, 否則剩下的人出生點會全部錯位。
    val roster = activeSlots.filter { it in 0..7 }.distinct().sorted().take(8)
        .ifEmpty { (0..7).toList() }
    Encode1(roster.size)

    for (i in roster) {
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
