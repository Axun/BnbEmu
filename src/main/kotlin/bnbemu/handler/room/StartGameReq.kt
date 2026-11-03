package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.PracticeAiLevel
import bnbemu.packet.PracticeBoard
import bnbemu.packet.PracticeStartInfo
import bnbemu.packet.room.GameStartMapInfo
import bnbemu.server.Room
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.decodeFromStream
import java.io.File
import kotlin.random.Random

val mapsYaml: Map<Int, Map<String, String>> by lazy {
    loadMapsYaml()
}

fun loadMapsYaml(): Map<Int, Map<String, String>> {
    val stream = StartGameReq::class.java.getResourceAsStream("/data/maps.yaml")
        ?: File("src/main/resources/data/maps.yaml").takeIf { it.exists() }?.inputStream()
        ?: File("data/maps.yaml").takeIf { it.exists() }?.inputStream()
        ?: File("maps.yaml").takeIf { it.exists() }?.inputStream()

    if (stream == null) {
        System.err.println("Warning: maps.yaml not found, falling back to empty map list")
        return emptyMap()
    }

    return try {
        stream.use { Yaml.default.decodeFromStream(it) }
    } catch (e: Exception) {
        System.err.println("Failed to parse maps.yaml: ${e.message}")
        emptyMap()
    }
}

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

    val mapName = mapsYaml[mapId]?.get("name") ?: "Cemetery03"
    println("StartGame. mapId: $mapId, mapName: $mapName")

    c.send(
        PracticeAiLevel(),
        GameStartMapInfo(randomSeed = Random.nextInt(0x7FFFFFFF), mapId = mapId, themeId = themeId, mapName = mapName),
        PracticeStartInfo(),
        PracticeBoard(),
    )

    // 啟動遊戲主循環 (onTick 爆炸檢測與炸彈狀態同步, aiTick 實體時間同步)
    Room.startGame()
}
