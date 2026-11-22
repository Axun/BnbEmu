package bnbemu.handler.room

import bnbemu.domain.CharacterType
import bnbemu.data.MapCatalog
import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.StartGameResult
import bnbemu.packet.battle.GameStart
import bnbemu.packet.battle.GameStartPeer
import bnbemu.packet.room.SetPlayer
import bnbemu.packet.lobby.SESSION_TYPE_BATTLE
import bnbemu.packet.lobby.SESSION_TYPE_PRACTICE
import bnbemu.packet.battle.GameStartMapInfo
import bnbemu.packet.battle.GameStartSlotConfig
import bnbemu.packet.battle.RANDOM_CHARACTER_ID
import bnbemu.packet.battle.assignSpawnPositions
import bnbemu.server.Room
import bnbemu.server.bomber
import bnbemu.service.player
import org.tinylog.kotlin.Logger
import kotlin.random.Random

// ============================================================================
// 地图表: 唯一事实源是 data/fxdynmapinfo.json，join 逻辑在 data/map/MapCatalog
// ============================================================================
// 客户端 CDynamicMapMan 只把 flag==0 的记录按数组顺序装入选图数组，其 0-based 下标
// 就是 C→S op 85 / op 92 的 mapId；themeId 等于记录的 sort_order。
// 这里只取「名字」（下发 op 112 的 mapName），元数据（人数/等级/会员）用 MapCatalog.of(mapId)。
private const val FALLBACK_MAP_NAME = "Cemetery03"

// ============================================================================
// C→S op 92: 開始遊戲請求 (StartGameReq) — 房間/練習房內點擊"開始"後發出
// ============================================================================
// 逆向確認:
//   CA.exe 0x00528071 (CPrepareStrategyAIBattle::OnButtonClick / 房間開始序列).
//   客戶端在 0x00528076 構造 CSendPacket(0x5C = 92), 並於 0x00531A09 寫入欄位:
//     Encode4: mapId 地圖 ID
//     Encode2: themeId 地图主题 ID
//     Encode1: subtype 子模式 固定0
//     Encode4: roomSelectedMapId (房间界面原始选项 ID：取自 CDynamicMapMan[+0x40]，若房间选的是具体地图，此值与参数 1 mapId 相同。若房间选的是“随机地图”，则此处为该虚拟按钮 ID)
//     Encode1: isCustomMap （0 = 官方内置预置地图；1 = 自定义/外部下载地图）
//     Encode1: fileCheckType （地图文件完整性校验标志。1 = 跳过文件哈希校验（官方内置地图或随机地图）0 = 需比对文件哈希，此时封包体会在后面紧跟一个 Encode4(mapFileCrc32) 传入该地图文件的 CRC32 防作弊。）
//     Encode4: eventParam （活动 / 赛事参数）
//   ⚠️ CA.exe 0x00531A09 在 isCustomMap 之后仅当 sub_686233(CFxContext) != 0（即
//      CA.exe 0x0052725F 仅对 sessionType == 15 练习模式置 1）时才写 fileCheckType(+CRC32)；
//      激战模式 (16) 与普通房 (0) 不写该字段，body 为 16B (tst.pcap 练习房为 17B)。
// 伺服器回覆對局啟動序列:
//   [111 StartGameResult(0), 112 GameStartMapInfo, 100 SetPlayer(isStart=true), 136 GameStart].
//   op 111 是「開始遊戲」的結果碼 (E1, 0/1 = 成功, 2..24 = 拒絕原因, 取值表見
//   packet/room/StartGameResult.kt): 它不解鎖也不推進流程, 失敗碼才會彈提示; 練習房正常路徑回 0。
//   收尾的 op 136 (GameStart) 才是真正開局: 啟動對局時鐘 + 每個 bomber 的激活,
//   並附帶局次密鑰與 UDP 對端表 (真端 DoStartGame + OnUDPPeerInfo 兩包合一,
//   逐字段見 packet/battle/GameStart.kt)。它不含棋盤資料。
// 之後客戶端完成加載發送 149 Ready Ack, 伺服器啟動對局主循環 (Room.startGame).
//
// 本鏈的抓包基準 (tst.pcap stream 4) 提取工具: tools/extract_op75.py;
// 其餘回放 blob 由 tools/gen_practice_kotlin.py 生成。
// ⚠️ 真实服务器另会按地图事件时间轴发 S→C op 172 (MapEventSync), 既非 ItemThrow
//    也与 AI 行动无关, 见 packet/room/MapEventSync.kt。
val StartGameReq = PKTHandler { c ->
    val mapId = Decode4
    val themeId = Decode2
    val subType = Decode1            // 固定 0 (标准地图)
    val roomSelectedMapId = Decode4  // 房间原始选图按钮 ID (如随机池或练习按钮 172)
    val isCustomMap = Decode1        // 0=官方, 1=外部自定义
    // 只有剩余字节 > 4 (即带 fileCheckType [+ optional CRC32] + 4B eventParam) 才读 fileCheckType
    val fileCheckType = if (readableBytes() > 4) Decode1 else 1
    val mapCrc32 = if (fileCheckType == 0 && readableBytes() >= 8) Decode4 else 0
    val eventParam = if (readableBytes() >= 4) Decode4 else 0
    if (readableBytes() > 0) skipBytes(readableBytes())

    if (mapId != Room.mapId || themeId != Room.themeId) {
        println("mapId or themeId mismatch")
    }

    val mapName = MapCatalog.ref(mapId)?.name ?: FALLBACK_MAP_NAME
    println("StartGame. mapId: $mapId, mapName: $mapName")

    val mapLimit = MapCatalog.maxPlayerOf(mapId)
    if (Room.activeSlotCount > mapLimit) {
        // 应该不会发生，客户端有检查
        Logger.warn("[StartGame] mapId=$mapId 人数上限 $mapLimit, 房间人数超限")
    }

    // 開局名冊 = 房間裡真正參戰的槽位 (op 89 / 选图上限 踢掉的 AI 已被清成 user=null / isAI=false)。
    // 與 BattleSession.start() 按 hasSeat() 定格的參戰名單判據必須完全一致, 否則會出現
    // 「服務端按 6 個 AI 判勝負、客戶端卻照 op112 的 8 槽名冊生成 7 個 AI」的錯位。
    // 異常防護: 若房間槽位尚未初始化 (例如非練習房流程), 退回舊的 8 槽名冊,
    // 避免給客戶端發 slotCount=0 導致對局載入失敗。
    val activeSlots = Room.slots.indices.filter { i -> Room.slots[i].player != null || Room.slots[i].isAI }
        .ifEmpty { (0..7).toList() }
    println("StartGame. activeSlots=$activeSlots (AI=${activeSlots.count { Room.slots[it].isAI }})")

    val spawnPositions = assignSpawnPositions(mapId, activeSlots)
    val slotConfigs = activeSlots.map { i ->
        val slot = Room.slots.getOrNull(i)
        val rawBomber = slot?.bomber?.toInt()
        val bomberId = when {
            rawBomber == RANDOM_CHARACTER_ID -> CharacterType.Lucy.value
            rawBomber != null -> rawBomber
            i == 0 -> CharacterType.Dao.value
            else -> CharacterType.Boz.value
        }
        val team = if (i == 0 || slot?.player != null) 0 else 8
        val spawn = if (i < spawnPositions.size) spawnPositions[i] else i
        GameStartSlotConfig(slotIdx = i, spawnPos = spawn, bomber = bomberId, team = team)
    }

    val player = c.player
    c.send(
        StartGameResult(),  // op 111 开局结果码 = 0 (成功)。
        GameStartMapInfo(
            randomSeed = Random.nextInt(0x7FFFFFFF),
            mapId = mapId,
            themeId = themeId,
            mapName = mapName,
            gameOptions = if (Room.isAIBattle) SESSION_TYPE_BATTLE else SESSION_TYPE_PRACTICE,
            // 名冊唯一入口: 誰參戰 / 出生點 / 角色 / 隊伍全部在這裡定死 (見 GameStartSlotConfig)。
            // 出生點數量由地圖決定（4 人图只有 0..3），按参战槽顺序分配，见 assignSpawnPositions
            slotConfigs = slotConfigs,
        ),
        // op 100 开局版 (77B, 抓包 practice_s2c_op100_79B_1.bin): isStart=true 去掉 enterEffectId
        // 与房主位, 只置 FLAG_READY。slotId 恒 0 —— 真人恒在槽 0 (CreateSessionReq / Room.begin),
        // 角色与颜色由上一发 op 112 的槽位名册决定, 这里的槽位元数据与抓包基准保持 Boz/0。
        SetPlayer(slotId = 0, player = player, isStart = true),
        // op 136 開局令: 時鐘 + 局次密鑰 + UDP 對端表。名冊必須與上面 op 112 的 slotConfigs
        // 同源 (客戶端會按 occupied 位反向校驗名冊: flag=0 就刪人, flag=1 卻沒人就回 op 149)。
        // 本模擬器只有 TCP, 所有 UDP 端點填 0 —— 客戶端 0x00789FD0 對 ip/port 為 0 直接跳過,
        // 不會去連抓包裡那台早已下線的 66.135.19.7。
        GameStart(
            // 兩個秒級時長沿用抓包實測值: A=180s (3 分鐘) / B=0。CA.exe 收下後只把它們
            // ×1000 寫進 +13808/+13812, 之後從不讀取, 故對行為沒有影響 (語義未定案,
            // 真端同位置是 ghostStart/ghostFreq, 參考分支按「對局時限」使用)。
            timerASec = 180,
            timerBSec = 0,
            peers = activeSlots.map { GameStartPeer(it) },
        ),
    )

    // 啟動對局主循環 (onTick: 水泡倒计时与 op 138 爆炸状态同步)
    Room.startGame()
}
