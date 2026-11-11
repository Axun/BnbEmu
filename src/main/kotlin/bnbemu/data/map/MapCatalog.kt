package bnbemu.data.map

import bnbemu.constants.MAX_SESSION_SLOTS
import bnbemu.data.DataResource
import bnbemu.data.FxdynMapInfo
import java.io.File
import java.net.JarURLConnection
import kotlin.random.Random

// ============================================================================
// 地图目录服务（真端 CGameMapTmplList 的 Kotlin 版）
// ============================================================================
// 职责边界：
//   · 只做「数据」：载入 data/maps/*.bin、按名字/按协议 mapId 建索引、主题聚合。
//   · 不碰协议：谁要用人数上限/等级门槛/会员限制，来查这里，不要自己读字节。
//   · 门面与解析都在本包：handler → MapCatalog → MapTemplate/MapBinReader。
//
// 两种「地图标识」在此收口，禁止别处再各自 join：
//   · 协议 mapId（C→S op 85/92 的 4 字节字段）= fxdynmapinfo.json 里 flag==0 记录的
//     0-based 下标，由 FxdynMapInfo.records 决定；
//   · 地图元数据 = data/maps/<name>.bin，由真端 Map/* 文件决定。
//   of(mapId) 是两者唯一的连接点。
// ============================================================================

/**
 * 协议意义上的「地图引用」：只依赖 fxdynmapinfo.json，不依赖 .bin 是否存在。
 *
 * 名字与主题编号对所有 flag==0 记录都成立（含 HotRandom 这类没有地图文件的虚拟条目），
 * 需要人数/等级等元数据时再用 [MapCatalog.of] 换 [MapTemplate]。
 *
 * @param themeId 记录的 sort_order，即 S→C op 112 里的 themeId
 */
data class MapRef(val mapId: Int, val name: String, val themeId: Int)

object MapCatalog {

    private const val DIR = "data/maps"

    /** 图名（"Bula00"）→ 模板，按名字字典序，保证遍历顺序确定。 */
    val byName: Map<String, MapTemplate> by lazy { loadAll() }

    val all: List<MapTemplate> by lazy { byName.values.toList() }

    /** fxdynmapinfo 里 flag==0 的记录，顺序即协议 mapId（客户端 CDynamicMapMan 的选图数组）。 */
    private val mapRecords by lazy { FxdynMapInfo.records.filter { it.flag == 0 } }

    /** 全部协议 mapId 的地图引用，下标即 mapId。 */
    val refs: List<MapRef> by lazy {
        mapRecords.mapIndexed { i, r -> MapRef(i, r.name, r.sortOrder) }
    }

    /**
     * fxdynmapinfo 引用了、但没有对应 .bin 的图名。
     *
     * 实测 193 条 flag==0 记录里有 27 条属于该情形（HotRandom / WriggleRandom /
     * BonusExp01 等随机按钮与活动伪条目），属正常现象，只记录不报错。
     */
    val missing: Set<String> by lazy { refs.filterNot { it.name in byName }.map { it.name }.toSet() }

    private val themeMaxTeam: Map<String, Int> by lazy {
        all.mapNotNull { t -> t.themeName?.let { it to t.maxTeam } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, teams) -> teams.max() }
    }

    /** 协议 mapId → 地图引用（名字/主题，恒有）；越界返回 null。 */
    fun ref(mapId: Int): MapRef? = refs.getOrNull(mapId)

    /** 协议 mapId → 地图模板；没有对应 .bin（随机按钮等）时返回 null。 */
    fun of(mapId: Int): MapTemplate? = ref(mapId)?.let { byName[it.name] }

    /** 人数上限；查不到时退回 8 槽（与真端缺模板时的 MAX_SESSION_SLOTS 兜底一致）。 */
    fun maxPlayerOf(mapId: Int): Int = of(mapId)?.maxPlayer ?: MAX_SESSION_SLOTS

    /** 本局出生点数量；查不到时退回 8。 */
    fun spawnCount(mapId: Int): Int = of(mapId)?.maxPlayer ?: MAX_SESSION_SLOTS

    /**
     * 主题内所有地图的最大队伍数（真端 GetThemeMaxMaxPlayer）。
     * 选「主题随机图」时用它判分队，[theme] 形如 "Theme/Bula"。
     */
    fun themeMaxTeam(theme: String): Int = themeMaxTeam[theme] ?: 0

    /**
     * 本局出生点排列：只在该图实际存在的出生点里洗牌。
     *
     * 真端 LaunchGameStage：`spawns = min(maxPlayer, 8)`，然后 random_shuffle(0..spawns-1)。
     * 直接洗 0..7 会给出该 4 人图上并不存在的出生点编号 4..7。
     */
    fun spawnPermutation(mapId: Int, rng: Random = Random.Default): IntArray =
        (0 until spawnCount(mapId)).shuffled(rng).toIntArray()

    private fun loadAll(): Map<String, MapTemplate> {
        val names = listNames()
        check(names.isNotEmpty()) {
            "找不到地图目录 $DIR（classpath 与工作目录均已尝试），无法建立地图目录"
        }
        val out = sortedMapOf<String, MapTemplate>()
        for (name in names) {
            val data = DataResource.bytes("$DIR/$name.bin")
            out[name] = try {
                MapTemplate.parse(name, data)
            } catch (e: Exception) {
                throw IllegalStateException("地图 $name 解析失败: ${e.message}", e)
            }
        }
        // 分组表（Attr/MapGroup）不读：真端只在「练习房限定练习图」与「重生玩法标记」
        // 两处消费，先不做。
        println(
            "[Map] 载入 ${out.size} 张地图模板，" +
                "${out.values.mapNotNull { it.themeName }.distinct().size} 个主题"
        )
        return out
    }

    /**
     * 枚举 data/maps 下的 .bin 文件名（不含扩展名）。
     *
     * jar 内无法列目录，故三条路径依次尝试：classpath 目录（file:/jar:）→ 源码树 → 工作目录。
     */
    private fun listNames(): List<String> {
        val out = sortedSetOf<String>()
        val url = MapCatalog::class.java.getResource("/$DIR")
        when (url?.protocol) {
            "file" -> File(url.toURI()).listFiles()?.forEach { addIfMap(it, out) }
            "jar" -> (url.openConnection() as? JarURLConnection)?.jarFile?.use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.startsWith("$DIR/") && it.name.endsWith(".bin") }
                    .forEach { out += it.name.removePrefix("$DIR/").removeSuffix(".bin") }
            }
        }
        if (out.isEmpty()) {
            for (candidate in listOf("src/main/resources/$DIR", DIR)) {
                val dir = File(candidate)
                if (!dir.isDirectory) continue
                dir.listFiles()?.forEach { addIfMap(it, out) }
                if (out.isNotEmpty()) break
            }
        }
        return out.toList()
    }

    private fun addIfMap(file: File, out: MutableSet<String>) {
        if (file.isFile && file.extension == "bin") out += file.nameWithoutExtension
    }
}
