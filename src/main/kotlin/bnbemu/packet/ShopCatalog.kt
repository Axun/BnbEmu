package bnbemu.packet

import bnbemu.ACP
import bnbemu.data.DataResource
import bnbemu.netty.PKT
import bnbemu.netty.ssdEncode
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
 *
 * 字段逆向布局 (0x006620B9):
 * @param prodId 唯一商品编号 (u32 LE)
 * @param category 商品分类 (u8, 0x10=角色卡/升级卡, 10=装扮, 12=特效, 14=功能, 15=消耗品, 18=旗帜, 29=气泡背景)
 * @param name 商品名称 (u32 LE 长度 + ASCII 字符串)
 * @param periodType 期限/有效类型 (u16 LE, 抓包中通常为 1)
 * @param itemId 关联物品定义 ID (u32 LE, 对应 op 60 ItemDef 表中的 ID)
 * @param count 拥有数量 / 捆绑包件数 (u32 LE)
 * @param desc 描述/资源标识 (u32 LE 长度 + ASCII 字符串)
 * @param tail 36 字节属性尾部
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
 * 商城/角色库存目录管理器 (Opcode 65, S→C)
 *
 * 逆向确认 (CA.exe, 2026-10-25):
 * 1. 客户端角色启用判定 (CStockContainer::FindCharacterStock):
 *    - 客户端角色选择界面 (0x005CE536, 0x005CADBF, 0x006AA1CC) 通过调用
 *      CStockContainer::FindCharacterStock(4) (0x006646AE) 校验蓝蓝 (Marid, ID 4) 升级版
 *      蓝妹妹 (LuxuryMarid / Posh Evie, ID 10) 的解锁状态。
 *    - 校验链:
 *      a. item->Category == 0x10 (在 0x006646CF 校验角色卡分类)
 *      b. item->Count >= 1 (在 0x006646DA 校验拥有数量/状态)
 *      c. 从全局物品库 (0x00993D58, 由 op 60 下发) 检索 ItemDef = FindItemDef(item->ItemID)
 *      d. ItemDef->TargetCharID == 4 (在 0x00664703 校验是否指向 Marid)
 *    - 若未拥有则弹出购买提示; 命中后直接激活蓝妹妹选择与对局。
 *
 * 2. 封包格式与反序列化 (CShopItemMan::Read 0x00670965 / CShopItem::Read 0x006620B9):
 *    - 客户端处理入口: CSelectServerStage::OnPacket (0x00502DF6 -> 0x004F813C -> 0x00670965)
 *    - 封包结构: [Encode4(blob.size)][SSD 加密资料块]
 *    - SSD 块明文结构:
 *      - 块头 (8字节): [0x18][blockLen:2 LE][0x00, 0x00, 0x00][itemCount:2 LE]
 *      - N × ShopItemEntry (每条 51 + len(name) + len(desc) 字节)
 *      - 4 字节补齐对齐
 */
object ShopCatalog {
    /**
     * 从基础数据文件加载历史 573 条商城物品条目
     */
    fun loadBaseItems(): List<ShopItemEntry> = decodeBlock(DataResource.bytes("data/shop_catalog_base.bin"))

    /**
     * 将明文 SSD 数据块解析为 ShopItemEntry 列表
     */
    fun decodeBlock(bytes: ByteArray): List<ShopItemEntry> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.get().toInt() and 0xFF
        require(magic == 0x18) { "Invalid SSD magic: 0x${magic.toString(16)}" }
        val blockLen = buf.short.toInt() and 0xFFFF
        // 3 字节保留位
        buf.get(); buf.get(); buf.get()
        val count = buf.short.toInt() and 0xFFFF
        val items = ArrayList<ShopItemEntry>(count)
        for (i in 0 until count) {
            items.add(ShopItemEntry.decode(buf))
        }
        return items
    }

    /**
     * 将条目列表序列化组装为明文 SSD 数据块
     */
    fun buildBlock(items: List<ShopItemEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        out.u8(0x18)
        out.u16le(0) // blockLen 占位 (组装完成后回填)
        out.u8(0); out.u8(0); out.u8(0) // 3 字节保留位
        out.u16le(items.size)
        for (item in items) {
            item.encode(out)
        }
        // 4 字节补齐对齐
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

    /** 组装条目列表为明文块, 再交给统一的 [ssdEncode] 下发 (尾部余数由其负责)。 */
    fun encode(items: List<ShopItemEntry>): ByteArray = ssdEncode(buildBlock(items))

    /**
     * 默认下发目录：基础 573 条商城道具 + 蓝妹妹 (Posh Evie / LuxuryMarid) 角色使用权卡
     */
    val DEFAULT_ITEMS: List<ShopItemEntry> by lazy {
        val list = loadBaseItems().toMutableList()
        // 注入蓝妹妹 (TargetCharID == 4 对应 Marid/蓝蓝)
//        list.add(
//            ShopItemEntry(
//                prodId = 2082001,
//                category = 0x10,
//                name = "Posh Evie",
//                periodType = 1,
//                itemId = 2082,
//                count = 1,
//                desc = "Item/CharDeco",
//                tail = ShopItemTail.DEFAULT_ACTIVE,
//            )
//        )
        list
    }

    /**
     * Adler-32 校验 (初始值为 0, 对应 CA.exe 0x0074EE50)
     */
    fun adler32Zero(data: ByteArray, offset: Int, length: Int): Int {
        var s1 = 0L
        var s2 = 0L
        val mod = 65521L
        for (i in offset until offset + length) {
            val b = data[i].toLong() and 0xFFL
            s1 = (s1 + b) % mod
            s2 = (s2 + s1) % mod
        }
        return (((s2 shl 16) or s1) and 0xFFFFFFFFL).toInt()
    }

    /**
     * 计算并缓存当前商城目录在客户端 fxstock.ini 对应的校验哈希 (CheckRevision)
     */
    val CATALOG_HASH: Int by lazy {
        val block = buildBlock(DEFAULT_ITEMS)
        val bLen = (block[1].toInt() and 0xFF) or ((block[2].toInt() and 0xFF) shl 8)
        adler32Zero(block, 5, bLen)
    }

    /**
     * 缓存的默认已加密数据块 (避免进频道时重复计算 50KB)
     */
    val CACHED_BLOB: ByteArray by lazy {
        encode(DEFAULT_ITEMS)
    }
}

/**
 * 进频道握手推送 #10: 商城目录与角色库存 (Opcode 65, S→C)
 */
fun ShopCatalog(): PKT = ShopCatalog(null)

fun ShopCatalog(items: List<ShopItemEntry>?) = PKT {
    val blob = if (items == null) ShopCatalog.CACHED_BLOB else ShopCatalog.encode(items)
    Encode4(blob.size)
    EncodeBuffer(blob)
}
