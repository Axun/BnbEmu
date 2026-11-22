package bnbemu.packet.battle

import bnbemu.domain.MAX_SESSION_SLOTS
import bnbemu.data.MapCatalog
import bnbemu.netty.PKT
import bnbemu.packet.lobby.SESSION_TYPE_PRACTICE
import kotlin.random.Random

// ============================================================================
// 開局出生點洗牌
// ============================================================================
// 客戶端 (CA.exe 0x0051F29C) 把 op112 每筆紀錄的第 2 個欄位交給
// 0x687415 SetSlotSpawnPos 存進 CGameContext, 開局時 0x005483... 再以
// 0x687D80 讀出 8 個槽位的出生點編號交給棋盤初始化 (vtable +416)。
// 也就是說 op112 的 spawnPos 就是「這局誰站在哪個出生點」的唯一來源。
//
// 真實對局不能沿用抓包裡的固定排列 [2,1,6,0,3,4,5,7], 否則每局玩家都在同一個點出生。
// GameStartMapInfo() 的預設值仍保留抓包原值, 供 replay_test 與單元測試逐位元組比對。
//
// 出生點數量由地圖決定: 真端 LaunchGameStage 取 min(maxPlayer, 8), 4 人圖只有 0..3
// 四個出生點, 洗 0..7 會把玩家放到不存在的出生點 (GameSession.ts:1384)。
//
// spawnRandom 抽成可替換的 RNG, 讓回歸測試能用固定 seed 得到確定性結果。
internal var spawnRandom: Random = Random.Default

// 这个ID代表客户端选的是问号
const val RANDOM_CHARACTER_ID = 13

/**
 * 產生本局的出生點分配表, 按「真實槽號」索引 (見 [GameStartMapInfo])。
 *
 * 與真端 CGameSession::LaunchGameStage 同序: 先把 0 until spawnCount 洗成一個排列,
 * 再按參戰槽號從小到大依次取用; 未參戰的槽位保持自身槽號 (不會被編碼進 op112)。
 * 參戰人數超過該圖出生點數時, 多出來的槽位退回自身槽號 (房間本應先擋掉超额人數)。
 */
fun assignSpawnPositions(mapId: Int, activeSlots: List<Int>): IntArray {
    val order = MapCatalog.spawnPermutation(mapId, spawnRandom)
    val out = IntArray(MAX_SESSION_SLOTS) { it }
    activeSlots.filter { it in 0 until MAX_SESSION_SLOTS }
        .sorted()
        .forEachIndexed { i, slot -> if (i < order.size) out[slot] = order[i] }
    return out
}

/**
 * 开局单槽位只读配置快照（解耦 GameStartMapInfo 与 Room 单例）。
 *
 * op 112 名册的**唯一**数据来源: 谁参战、出生点、角色、队伍全在这里带进来。
 */
data class GameStartSlotConfig(
    val slotIdx: Int,
    val spawnPos: Int,
    val bomber: Int,
    val team: Int,
)

/**
 * S→C op 112: GameStartMapInfo (對局地圖與槽位出生點/角色初始化)
 *
 * 逆向確認:
 *   CA.exe 0x0051F29C (CPrepareStage::OnPacket -> Case 112).
 *   客戶端在開局序列 (op 92 StartGameReq) 後接收:
 *     [111 PracticeAiLevel, 112 GameStartMapInfo, 100 SetPlayer(isStart=true), 136 PracticeBoard].
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
 *    因此由 [StartGameReq] 把 Room.slots 中「有人或 isAI」的真實槽號 + 出生點 + 角色 + 隊伍
 *    組成 [GameStartSlotConfig] 傳進來; 包層不讀 Room, 也沒有第二套 activeSlots 入口。
 */
fun GameStartMapInfo(
    randomSeed: Int = 0x7E022E6B,
    themeId: Int = 29,
    mapName: String = "Cemetery04",
    mapId: Int = 176,
    gameOptions: Int = SESSION_TYPE_PRACTICE,
    flag: Int = 0,
    /** 本局參戰名冊（唯一入口，必填）。 */
    slotConfigs: List<GameStartSlotConfig>,
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

    // 只發「本局真的參戰」的槽位。槽號不重排 (踢掉 3 號就跳過 3 號), 出生點也按真實槽號帶進來。
    // 客戶端 CGameContext 只有 8 組槽位陣列, 多餘條目會越界寫, 這裡兜底截斷。
    val roster = slotConfigs.take(MAX_SESSION_SLOTS)
    Encode1(roster.size)

    for (cfg in roster) {
        Encode1(cfg.slotIdx)
        Encode1(cfg.spawnPos)
        Encode1(cfg.bomber)
        Encode1(cfg.team)
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
