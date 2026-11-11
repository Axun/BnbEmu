package bnbemu.handler.room

import bnbemu.data.map.MapCatalog
import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.PracticeAiLevel
import bnbemu.packet.PracticeBoard
import bnbemu.packet.PracticeStartInfo
import bnbemu.packet.room.GameStartMapInfo
import bnbemu.packet.room.assignSpawnPositions
import bnbemu.server.Room
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
//   (17B body, tst.pcap stream4 sq=91).
//   在舊版 TW_5 / CN_12 協議中對應 StartGameReq (TW_5 op 106).
// 伺服器回覆對局啟動序列:
//   [111 PracticeAiLevel, 112 GameStartMapInfo, 100 PracticeStartInfo, 136 PracticeBoard].
// 之後客戶端完成加載發送 149 Ready Ack, 伺服器啟動對局主循環 (Room.startGame).
val StartGameReq = PKTHandler { c ->
    val mapId = Decode4
    val themeId = Decode2
    val subType = Decode1            // 固定 0 (标准地图)
    val roomSelectedMapId = Decode4  // 房间原始选图按钮 ID (如随机池或练习按钮 172)
    val isCustomMap = Decode1        // 0=官方, 1=外部自定义
    val fileCheckType = Decode1      // 1=无需比对 CRC, 0=需比对 CRC
    val mapCrc32 = if (fileCheckType == 0) Decode4 else 0
    val eventParam = Decode4         // CEventManager 活动参数, 普通房间为 0

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
    // 與 Room.resetMatchState 的 hasSeat() 判據必須完全一致, 否則會出現
    // 「服務端按 6 個 AI 判勝負、客戶端卻照 op112 的 8 槽名冊生成 7 個 AI」的錯位。
    // 異常防護: 若房間槽位尚未初始化 (例如非練習房流程), 退回舊的 8 槽名冊,
    // 避免給客戶端發 slotCount=0 導致對局載入失敗。
    val activeSlots = Room.slots.indices.filter { i -> Room.slots[i].user != null || Room.slots[i].isAI }
        .ifEmpty { (0..7).toList() }
    println("StartGame. activeSlots=$activeSlots (AI=${activeSlots.count { Room.slots[it].isAI }})")

    val user = c.users.firstOrNull()
    c.send(
        PracticeAiLevel(),
        GameStartMapInfo(
            randomSeed = Random.nextInt(0x7FFFFFFF),
            mapId = mapId,
            themeId = themeId,
            mapName = mapName,
            activeSlots = activeSlots,
            // 出生点数量由地图决定（4 人图只有 0..3），按参战槽顺序分配，见 assignSpawnPositions
            spawnPositions = assignSpawnPositions(mapId, activeSlots),
        ),
        PracticeStartInfo(user = user),
        PracticeBoard(),
    )

    // 啟動對局主循環 (onTick: 水泡倒计时与 op 138 爆炸状态同步)
    Room.startGame()
}
