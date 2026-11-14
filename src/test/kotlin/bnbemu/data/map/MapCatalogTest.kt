package bnbemu.data.map

import bnbemu.data.FxdynMapInfo
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MapCatalog 回归测试。
 *
 * 期望值全部取自真端 2004 年数据包（reference/古早真端ts实现/data/FxServer.idd 里的 Map/ 条目），
 * 不是拍脑袋写的常量；改动数据文件时这里必须先红一遍。
 */
class MapCatalogTest {

    @Test
    fun `全部地图载入且头字段落在真端实测范围内`() {
        assertTrue(MapCatalog.all.size > 200, "resource/fx/Map 下应有图")

        for (t in MapCatalog.all) {
            val h = t.header
            assertEquals(1000, h.version, "${t.name} 版本应统一为 v1000")
            assertTrue(h.maxPlayer in 1..8, "${t.name} maxPlayer=${h.maxPlayer} 越界")
            assertTrue(h.maxTeam in 1..8, "${t.name} maxTeam=${h.maxTeam} 越界")
            assertTrue(h.levelLimit >= 0, "${t.name} levelLimit=${h.levelLimit} 为负")
            assertEquals("Map/${t.name}", t.entry)
        }
    }

    @Test
    fun `Bula00 与真端文件逐字段一致`() {
        val t = MapCatalog.byName.getValue("Bula00")
        assertEquals(4, t.maxTeam, "Bula00 是 4 队图")
        assertEquals(8, t.maxPlayer, "Bula00 人数上限 8")
        assertEquals(1, t.levelLimit)
        assertFalse(t.premiumOnly)
        assertEquals("Theme/Bula", t.themeName)
        assertEquals(15, t.header.arg40, "真端 +12（v1000 的 arg40）在 171 张图上恒为 15")
        assertEquals(13, t.header.arg44, "真端 +16（arg44）在 171 张图上恒为 13")
    }

    @Test
    fun `广场与商城占位图只有 1 个槽位`() {
        assertEquals(1, MapCatalog.byName.getValue("Square01").maxPlayer)
        assertEquals(1, MapCatalog.byName.getValue("shopmap01").maxPlayer)
    }

    @Test
    fun `会员专属与锁图标记保留`() {
        assertTrue(MapCatalog.byName.getValue("Ocean18").premiumOnly)
        assertEquals(500, MapCatalog.byName.getValue("Bula21").levelLimit, "500 = 常规玩法够不到的锁图")
    }

    @Test
    fun `mapId 经 fxdynmapinfo 的 flag 为零序 join`() {
        // 客户端 CDynamicMapMan 只装 flag==0 记录，其 0-based 下标就是 op 85/92 的 mapId
        // 此处不再写死 mapId→图名，直接逐条比对 records 与 refs，换表也不会失效。
        val flagZero = FxdynMapInfo.records.filter { it.flag == 0 }
        val refs = MapCatalog.refs
        assertEquals(flagZero.size, refs.size, "refs 必须逐条对应 flag==0 记录")
        refs.forEachIndexed { i, ref ->
            assertEquals(i, ref.mapId)
            assertEquals(flagZero[i].name, ref.name, "mapId $i 的名字须来自同一份 records")
            assertEquals(flagZero[i].sortOrder, ref.themeId, "mapId $i 的 themeId 即 sort_order")
        }

        // 有 .bin 的条目能 join 到同名模板；HotRandom 这类随机按钮条目没有自己的 .bin
        val withBin = refs.filter { it.name in MapCatalog.byName }
        assertTrue(withBin.isNotEmpty(), "至少应有一张图能 join 到 .bin")
        withBin.forEach { assertEquals(it.name, MapCatalog.of(it.mapId)?.name) }
        assertNull(MapCatalog.of(0), "HotRandom 没有地图文件，应返回 null")
        assertEquals(8, MapCatalog.maxPlayerOf(0), "查不到时退回 8 槽兜底")
        assertTrue(MapCatalog.missing.contains("HotRandom"))
        assertFalse(MapCatalog.missing.contains(withBin.first().name))
    }

    @Test
    fun `主题取旗下最大队伍数`() {
        assertEquals(4, MapCatalog.themeMaxTeam("Theme/Bula"))
        assertEquals(8, MapCatalog.themeMaxTeam("Theme/Cemetery"))
        assertEquals(0, MapCatalog.themeMaxTeam("Theme/NoSuchTheme"))
    }

    @Test
    fun `出生点排列只在该图实际存在的出生点里洗牌`() {
        // 从当前表里找真实存在 .bin 的 4 人图 / 8 人图，不写死 mapId
        val fourId = MapCatalog.refs.first {
            it.name in MapCatalog.byName && MapCatalog.maxPlayerOf(it.mapId) == 4
        }.mapId
        val eightId = MapCatalog.refs.first {
            it.name in MapCatalog.byName && MapCatalog.maxPlayerOf(it.mapId) == 8
        }.mapId

        // 真端 spawns = min(4, 8) = 4，给 0..3 的排列
        val four = MapCatalog.spawnPermutation(fourId)
        assertEquals(4, four.size, "4 人图不应发 8 个出生点编号")
        assertArrayEquals(intArrayOf(0, 1, 2, 3), four.sortedArray())

        val eight = MapCatalog.spawnPermutation(eightId)
        assertEquals(8, eight.size)
        assertArrayEquals(IntArray(8) { it }, eight.sortedArray(), "8 个出生点各用一次，不重不漏")

        assertEquals(8, MapCatalog.spawnPermutation(MapCatalog.refs.size).size, "越界 mapId 退回 8 槽")
    }

    @Test
    fun `模板派生属性不越界`() {
        for (t in MapCatalog.all) {
            assertTrue(t.maxPlayer in 1..8, "${t.name} 实际可用槽位越界")
            assertTrue(t.canHost(1, 1), "${t.name} 至少能容纳 1 人 1 队")
            assertTrue(t.canHost(t.maxPlayer, t.maxTeam), "${t.name} 应容得下自身上限")
            assertFalse(t.canHost(t.maxPlayer + 1, 1), "${t.name} 不该容下超额人数")
        }
    }
}
