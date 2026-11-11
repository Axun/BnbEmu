package bnbemu.data.map

import bnbemu.constants.MAX_SESSION_SLOTS

// ============================================================================
// Map/*.bin —— 单张地图的元数据（真端 CGameMapTmpl）
// ============================================================================
//
// 文件头是紧挨着的 little-endian int32 序列，没有魔数也没有长度前缀；
// v < 1000 的老格式没有 maxTeam/old/arg40 三个字段，直接用常量补（真端同样这么干）。
// 全部 173 张现役地图都是 v=1000，老格式分支只为兼容留档。
// ============================================================================

/** 低于此版本的地图走老格式（maxTeam 固定 MAX_SESSION_SLOTS）。 */
private const val MAP_VERSION_MIN = 1000

/**
 * Map/ 下的 .bin 头字段，顺序与真端 CGameMapTmpl::LoadMap 的读取顺序一一对应。
 *
 * 括号里的偏移是 v1000 格式的文件内字节偏移，便于对着 hex 看。
 */
data class MapHeader(
    val version: Int,           // +0
    val maxTeam: Int,           // +4   队伍/颜色组数（1=单人图 / 2 / 3 / 4 / 8=混战）
    val old: Int,               // +8
    val arg40: Int,             // +12  171 张图恒为 15
    val arg44: Int,             // +16  171 张图恒为 13
    val maxPlayer: Int,         // +20  房间总人数上限（实测只有 1 / 4 / 6 / 8）
    val levelLimit: Int,        // +24  进图等级门槛（500 = 常规玩法够不到的锁图）
    val premiumOnly: Int,       // +28  1 = 会员专属
    val ghostStart: Int,        // +32
    val ghostFreq: Int,         // +36
    val arg68: Int,             // +40
    val airplaneItemProb: Int,  // +44  SendAirplaneItemProb 用，不是时长
    val arg76: Int,             // +48
    val missionType: Int,       // +52  > 0 即任务图
    val lapTileCount: Int,      // +56
    val lapTime: Int,           // +60
    val lapBonus: Int,          // +64
    val syncGameObjCount: Int,  // +68
) {
    /** 本局可用的出生点数量：真端 LaunchGameStage 的 min(maxPlayer, MAX_SESSION_SLOTS)。 */
    val spawnCount: Int get() = maxPlayer.coerceIn(1, MAX_SESSION_SLOTS)
}

/**
 * 一张地图的完整模板：文件名 + 头字段。
 *
 * [name] 是不带前缀和扩展名的图名（"Bula00"），与本项目 data/maps/Bula00.bin、
 * 以及 fxdynmapinfo.json 里的 name 字段完全同形。
 */
data class MapTemplate(
    val name: String,
    val themeName: String?,     // null = "Map/_xxx" 这类无主题图
    val header: MapHeader,
) {
    /** 真端数据包里的条目名（无扩展名）："Map/Bula00"。 */
    val entry: String get() = "Map/$name"

    val maxPlayer: Int get() = header.spawnCount
    val maxTeam: Int get() = header.maxTeam
    val levelLimit: Int get() = header.levelLimit
    val premiumOnly: Boolean get() = header.premiumOnly == 1
    val isMissionMap: Boolean get() = header.missionType > 0

    /** 与真端 GetRandomMapIdx 相同的初筛条件：人数与队伍数都得装得下。 */
    fun canHost(players: Int, teams: Int): Boolean = players in 1..maxPlayer && teams <= maxTeam

    companion object {
        /**
         * 与真端 CGameMapTmplList::CheckGetThemeIdx 一致：去掉末尾两位编号
         * （"Cemetery01" → "Theme/Cemetery"）；"Map/_xxx" 没有主题。
         */
        fun themeNameOf(name: String): String? =
            if (name.startsWith('_')) null else "Theme/" + name.dropLast(2)

        /** 解析一个 Map/ 下的 .bin。数据损坏时抛异常，由调用方补上文件名上下文。 */
        fun parse(name: String, data: ByteArray): MapTemplate {
            val r = MapBinReader(data)
            val version = r.int()
            val maxTeam: Int
            val old: Int
            val arg40: Int
            if (version < MAP_VERSION_MIN) {
                maxTeam = MAX_SESSION_SLOTS
                old = 1
                arg40 = version
            } else {
                maxTeam = r.int()
                old = r.int()
                arg40 = r.int()
            }
            // 两种格式都先吃掉 +44 再读 +48 的人数（真端 0040C2B5 同一段代码）。
            val arg44 = r.int()
            val header = MapHeader(
                version = version,
                maxTeam = maxTeam,
                old = old,
                arg40 = arg40,
                arg44 = arg44,
                maxPlayer = r.int(),
                levelLimit = r.int(),
                premiumOnly = r.int(),
                ghostStart = r.int(),
                ghostFreq = r.int(),
                arg68 = r.int(),
                airplaneItemProb = r.int(),
                arg76 = r.int(),
                missionType = r.int(),
                lapTileCount = r.int(),
                lapTime = r.int(),
                lapBonus = r.int(),
                syncGameObjCount = r.int(),
            )
            return MapTemplate(
                name = name,
                themeName = themeNameOf(name),
                header = header,
            )
        }
    }
}

/**
 * 带越界检查的 LE 读取器，等价于真端 MapDataReader.ReadInt。
 *
 * 地图头部很短，不做流式抽象，读满即止；越界一律抛出（数据文件损坏必须立刻暴露）。
 */
internal class MapBinReader(private val data: ByteArray) {
    private var at = 0

    val remaining: Int get() = data.size - at

    fun int(): Int {
        require(remaining >= 4) { "地图文件截断：偏移 $at 处不足 4 字节（总长 ${data.size}）" }
        val v = (data[at].toInt() and 0xFF) or
                ((data[at + 1].toInt() and 0xFF) shl 8) or
                ((data[at + 2].toInt() and 0xFF) shl 16) or
                ((data[at + 3].toInt() and 0xFF) shl 24)
        at += 4
        return v
    }
}
