package bnbemu.data.shop

import bnbemu.ACP
import bnbemu.crypto.Adler32
import bnbemu.data.DataResource
import bnbemu.netty.buildSsdBlock
import bnbemu.netty.ssdEncode
import bnbemu.packet.u16le
import bnbemu.packet.u32le
import bnbemu.packet.u8
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 商城物品属性尾部 (36 字节)
 * 对应 CA.exe CShopItem::Read (0x00662171 ~ 0x0066228D) 反序列化的价格与属性字段:
 * - price1, price2: 2 × u32 LE (金币 / 点券价格)
 * - f3 ~ f13: 状态/参数栏位 (含期限、折扣率、限购等参数)
 * - f14: u8 (0x01 = 激活/可拥有标志)
 */
data class ShopItemTail(
    val price1: Int = 0,
    val price2: Int = 0,
    val f3: Int = 0,
    val f4: Int = 0,
    val f5: Int = 0,
    val f6: Int = 0,
    val f7: Long = 0,
    val f8: Int = 0,
    val f9: Int = 0,
    val f10: Long = 0,
    val f11: Int = 0,
    val f12: Long = 0,
    val f13: Long = 0,
    val f14: Int = 1,
) {
    fun encode(out: ByteArrayOutputStream) {
        out.u32le(price1)
        out.u32le(price2)
        out.u16le(f3)
        out.u8(f4)
        out.u16le(f5)
        out.u16le(f6)
        out.u32le((f7 and 0xFFFFFFFFL).toInt())
        out.u8(f8)
        out.u8(f9)
        out.u32le((f10 and 0xFFFFFFFFL).toInt())
        out.u16le(f11)
        out.u32le((f12 and 0xFFFFFFFFL).toInt())
        out.u32le((f13 and 0xFFFFFFFFL).toInt())
        out.u8(f14)
    }

    companion object {
        val DEFAULT_ACTIVE = ShopItemTail()

        fun decode(buf: ByteBuffer): ShopItemTail {
            val p1 = buf.int
            val p2 = buf.int
            val f3 = buf.short.toInt() and 0xFFFF
            val f4 = buf.get().toInt() and 0xFF
            val f5 = buf.short.toInt() and 0xFFFF
            val f6 = buf.short.toInt() and 0xFFFF
            val f7 = buf.int.toLong() and 0xFFFFFFFFL
            val f8 = buf.get().toInt() and 0xFF
            val f9 = buf.get().toInt() and 0xFF
            val f10 = buf.int.toLong() and 0xFFFFFFFFL
            val f11 = buf.short.toInt() and 0xFFFF
            val f12 = buf.int.toLong() and 0xFFFFFFFFL
            val f13 = buf.int.toLong() and 0xFFFFFFFFL
            val f14 = buf.get().toInt() and 0xFF
            return ShopItemTail(p1, p2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14)
        }
    }
}

/**
 * 商城/库存条目 (与 CA.exe CShopItem 一一对应)
 */
data class ShopItemEntry(
    val prodId: Int,
    val category: Int,
    val name: String,
    val periodType: Int = 1,
    val itemId: Int,
    val count: Int = 1,
    val desc: String = "",
    val tail: ShopItemTail = ShopItemTail.DEFAULT_ACTIVE,
) {
    fun encode(out: ByteArrayOutputStream) {
        out.u32le(prodId)
        out.u8(category)
        val nameBytes = name.toByteArray(ACP)
        out.u32le(nameBytes.size)
        out.write(nameBytes)
        out.u16le(periodType)
        out.u32le(itemId)
        out.u32le(count)
        val descBytes = desc.toByteArray(ACP)
        out.u32le(descBytes.size)
        out.write(descBytes)
        tail.encode(out)
    }

    companion object {
        fun decode(buf: ByteBuffer): ShopItemEntry {
            val prodId = buf.int
            val category = buf.get().toInt() and 0xFF
            val nameLen = buf.int
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            val name = String(nameBytes, ACP)
            val periodType = buf.short.toInt() and 0xFFFF
            val itemId = buf.int
            val count = buf.int
            val descLen = buf.int
            val descBytes = ByteArray(descLen)
            buf.get(descBytes)
            val desc = String(descBytes, ACP)
            val tail = ShopItemTail.decode(buf)
            return ShopItemEntry(prodId, category, name, periodType, itemId, count, desc, tail)
        }
    }
}

/**
 * 商城目录主数据管理器（与 [bnbemu.data.map.MapCatalog] 平级）。
 * 负责加载 `data/shop_catalog_base.bin`、商品查询、缓存块与版本戳生成。
 */
object ShopCatalogData {
    fun loadBaseItems(): List<ShopItemEntry> = decodeBlock(DataResource.bytes("data/shop_catalog_base.bin"))

    fun decodeBlock(bytes: ByteArray): List<ShopItemEntry> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.get().toInt() and 0xFF
        require(magic == 0x18) { "Invalid SSD magic: 0x${magic.toString(16)}" }
        buf.short // blockLen
        buf.get(); buf.get(); buf.get()
        val count = buf.short.toInt() and 0xFFFF
        val items = ArrayList<ShopItemEntry>(count)
        for (i in 0 until count) {
            items.add(ShopItemEntry.decode(buf))
        }
        return items
    }

    fun buildBlock(items: List<ShopItemEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        out.u8(0x18)
        out.u16le(0)
        out.u8(0); out.u8(0); out.u8(0)
        out.u16le(items.size)
        for (item in items) {
            item.encode(out)
        }
        val pad = (4 - (out.size() % 4)) % 4
        for (i in 0 until pad) {
            out.u8(0)
        }
        val ba = out.toByteArray()
        val blockLen = (ba.size - 5) and 0xFFFF
        ba[1] = (blockLen and 0xFF).toByte()
        ba[2] = ((blockLen shr 8) and 0xFF).toByte()
        return ba
    }

    fun encode(items: List<ShopItemEntry>): ByteArray {
        val plain = buildBlock(items)
        return ssdEncode(buildSsdBlock(plain.copyOfRange(6, plain.size)))
    }

    val DEFAULT_ITEMS: List<ShopItemEntry> by lazy { loadBaseItems() }

    private val byProdId: Map<Int, ShopItemEntry> by lazy {
        DEFAULT_ITEMS.associateBy { it.prodId }
    }

    fun findByProdId(prodId: Int): ShopItemEntry? = byProdId[prodId]

    fun adler32Zero(data: ByteArray, offset: Int, length: Int): Int =
        Adler32.computeZero(data, offset, length)

    val CACHE_BLOCK: ByteArray by lazy {
        val plain = buildBlock(DEFAULT_ITEMS)
        buildSsdBlock(plain.copyOfRange(6, plain.size))
    }

    val CATALOG_HASH: Int by lazy { Adler32.computeSsdBlock(CACHE_BLOCK) }

    val PLAIN_HASH: Int by lazy { Adler32.computeSsdBlock(buildBlock(DEFAULT_ITEMS)) }

    val CACHED_BLOB: ByteArray by lazy { ssdEncode(CACHE_BLOCK) }
}
