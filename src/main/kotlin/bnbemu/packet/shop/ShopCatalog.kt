package bnbemu.packet.shop

import bnbemu.ACP
import bnbemu.crypto.Adler32
import bnbemu.data.DataResource
import bnbemu.netty.PKT
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
 *    - 封包结构: [Encode4(blob.size)][ssdEncode(SSD 块)]，blob 约 6.8 KB
 *    - 线上 SSD 块（压缩下发，客户端 `sub_78BB60` 的 `*a2==1` 分支解压）:
 *      - 块头: [0x18][u32 LE payloadLen][compress=1][u32 LE rawSize][zlib]
 *      - 解压后: [itemCount:2 LE] + N × ShopItemEntry（每条 51 + len(name) + len(desc) 字节）
 *      - 4 字节补齐对齐
 *    - 兼容性: 客户端同样支持 compress=0 的未压缩块（抓包就是这种，50544 B）；
 *      现在改发压缩块只为省流量，解析结果一致
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

    /**
     * 组装条目列表为**压缩** SSD 块，再交给统一的 [ssdEncode] 下发（尾部余数由其负责）。
     *
     * 客户端 `sub_78BB60` 的 `*a2 == 1` 分支支持解压，因此 op 65 可以直接下发压缩块：
     * 50544 B 明文 → 6747 B 压缩块，线上包体从 50550 B 降到约 6760 B。
     * 客户端解压后得到的仍是 [count][items]，解析结果与未压缩下发完全一致。
     */
    fun encode(items: List<ShopItemEntry>): ByteArray {
        val plain = buildBlock(items)
        return ssdEncode(buildSsdBlock(plain.copyOfRange(6, plain.size)))
    }

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
    fun adler32Zero(data: ByteArray, offset: Int, length: Int): Int =
        Adler32.computeZero(data, offset, length)

    /**
     * 客户端本地缓存块 —— 与 CA.exe `sub_78BC60` 写 `fxstock.ini` 的字节完全同构:
     *   `[0x18][u24 payloadLen][0x00][0x01][u32 LE rawSize][zlib(raw)]`
     *
     * 读盘走 `sub_78C590` → `sub_78C120` (`CA.exe.c:748271`): 看到 `body[0]==0x18` 后把
     * `body+5`（即 `[0x01][rawSize][zlib]`）交给 `sub_78BB60` 算 Adler-32，存进
     * `COSmartStream+0x14`；`sub_78BB50` 取出的正是这个值，也就是
     * `CShopItemMan::CheckRevision` (`sub_670CBE`) 比较的本地版本号。
     *
     * op 65 现在直接下发这一块（客户端解压后有 [count][items]），因此「线上块」与
     * 「磁盘缓存块」字节同构；`CATALOG_HASH` 正是它的校验戳。
     * 若下发未压缩块、261 又用未压缩块的戳（抓包原值 `0x75D92163`），客户端读盘后算的是
     * 压缩块戳，两者永远不相等 → 每次进频道/进商城都发 C→S op 276。
     */
    val CACHE_BLOCK: ByteArray by lazy {
        val plain = buildBlock(DEFAULT_ITEMS)
        // buildSsdBlock 压缩的是 [count][items]（不含 6 字节块头），
        // 压缩收益判定与 sub_78BC60 相同；op 65 现在直接下发这一块。
        buildSsdBlock(plain.copyOfRange(6, plain.size))
    }

    /**
     * 客户端 `fxstock.ini` 读盘后的版本戳（`COSmartStream+0x14`，`sub_78BB50`）
     * = 压缩块 `[0x01][rawSize][zlib]` 的 Adler-32（初值 0），与 `sub_78BB60` 一致。
     */
    val CATALOG_HASH: Int by lazy { Adler32.computeSsdBlock(CACHE_BLOCK) }

    /**
     * 未压缩明文块的校验戳 —— 抓包 op 261 原值 `0x75D92163`，仅作对照/回归基准。
     * ⚠️ 不能用于下发：客户端缓存里存的是压缩块，两者永远对不上。
     */
    val PLAIN_HASH: Int by lazy { Adler32.computeSsdBlock(buildBlock(DEFAULT_ITEMS)) }

    // zlib 压缩与块头组装统一走 netty/PKT.kt 的 zlibCompress / buildSsdBlock
    // （与 CA.exe sub_74C020(..., 9) / sub_78BC60 同一条判定，客户端写盘输出逐字节一致）。

    /**
     * 缓存的默认线上块（已 ssdEncode）：约 6.8 KB，避免每次请求都重压 50 KB 明文
     */
    val CACHED_BLOB: ByteArray by lazy {
        ssdEncode(CACHE_BLOCK)
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
