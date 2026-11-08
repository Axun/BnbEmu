package bnbemu.packet

import bnbemu.ACP
import bnbemu.netty.PKT
import bnbemu.netty.ssdEncode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 物品定义条目 (与 CA.exe CUserItem / UserItem 0x00676CFA 一一对应)
 *
 * 逆向确认 (CA.exe, 2026-11-01):
 * 1. 客户端物品定义加载与存储 (CUserItemMan::Read / CItemDef::Read):
 *    - 客户端处理入口: CSelectServerStage::OnPacket (0x00502DC8 -> 0x004F81E7 -> 0x0067877F)
 *    - 全局物品管理器单例: 0x00993D58 (CUserItemMan)
 *    - 封包结构: [Encode4(blob.size)][SSD 加密资料块]
 *    - SSD 块明文结构:
 *      - 块头 (8字节): [0x18][blockLen:2 LE][0x00, 0x00, 0x00][itemCount:2 LE]
 *      - N × ItemDefEntry
 *      - 尾部不足 4 字节按 SSD 标准规则混淆: 2B LE 读 ^ 0xA569 按 BE 写回, 1B ^ 0x5A
 *
 * 2. 字段逆向布局 (0x00676CFA CItemDef::Read):
 * @param itemId 物品唯一定义 ID (u32 LE, [esi + 8])
 * @param category 物品分类 (u8, [esi + 0x14], 3=表情/动作, 10=装扮背景, 12=角色装扮/特效, 13=背包, 14=水泡, 15=消耗品, 16=进阶角色卡, 18=旗帜/标牌, 29=气球背景, 31=情侣戒指, 36=进阶卡槽位)
 * @param name 内部标识/模型资源路径 (u32 LE 长度 + ASCII 字符串, [esi + 0x20], 如 "Dao", "Item/CharDeco", "Item/Bomb")
 * @param skinId 进阶角色皮肤 ID / 背景序号 (u32 LE, [esi + 0x38], 如蓝妹妹为 10, 未指定时为 -1 或 0)
 * @param targetCharId 目标基础角色 ID (u32 LE, [esi + 0x44], 如 4 对应 Marid/蓝蓝, 0 为通用/全角色/Bazzi)
 * @param param 附加参数/槽位序号/水泡序号 (u32 LE, [esi + 0x50])
 * @param desc 物品描述/显示名称 (u32 LE 长度 + ASCII 字符串, [esi + 0x5C], 如 "CharDeco E0001", "Bomb 001")
 * @param flags 属性标志/状态 (u16 LE, [esi + 0x74], 通常为 0 或 1)
 * @param status 启用/生效状态 (u8, [esi + 0x8C], 通常为 1 或 0)
 */
data class ItemDefEntry(
    val itemId: Int,
    val category: Int,
    val name: String,
    val skinId: Int = 0,
    val targetCharId: Int = 0,
    val param: Int = 0,
    val desc: String = "",
    val flags: Int = 0,
    val status: Int = 1,
) {
    fun encode(out: ByteArrayOutputStream) {
        out.u32le(itemId)
        out.u8(category)
        val nameBytes = name.toByteArray(ACP)
        out.u32le(nameBytes.size)
        out.write(nameBytes)
        out.u32le(skinId)
        out.u32le(targetCharId)
        out.u32le(param)
        val descBytes = desc.toByteArray(ACP)
        out.u32le(descBytes.size)
        out.write(descBytes)
        out.u16le(flags)
        out.u8(status)
    }

    companion object {
        fun decode(buf: ByteBuffer): ItemDefEntry {
            val itemId = buf.int
            val category = buf.get().toInt() and 0xFF
            val nameLen = buf.int
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            val name = String(nameBytes, ACP)
            val skinId = buf.int
            val targetCharId = buf.int
            val param = buf.int
            val descLen = buf.int
            val descBytes = ByteArray(descLen)
            buf.get(descBytes)
            val desc = String(descBytes, ACP)
            val flags = buf.short.toInt() and 0xFFFF
            val status = buf.get().toInt() and 0xFF
            return ItemDefEntry(itemId, category, name, skinId, targetCharId, param, desc, flags, status)
        }
    }
}

/**
 * 物品定义目录管理器 (Opcode 60, S→C)
 */
object ItemDef {
    /**
     * 从基础数据文件加载历史 882 条基础物品定义
     */
    fun loadBaseItems(): List<ItemDefEntry> {
        val stream = ItemDef::class.java.getResourceAsStream("/data/item_def_base.bin")
            ?: File("src/main/resources/data/item_def_base.bin").takeIf { it.exists() }?.inputStream()
        if (stream == null) {
            throw RuntimeException("Failed to load item_def_base.bin")
        }
        val raw = stream.use { it.readAllBytes() }
        return decodeBlock(raw)
    }

    /**
     * 将明文 SSD 数据块解析为 ItemDefEntry 列表
     */
    fun decodeBlock(bytes: ByteArray): List<ItemDefEntry> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.get().toInt() and 0xFF
        require(magic == 0x18) { "Invalid SSD magic: 0x${magic.toString(16)}" }
        val blockLen = buf.short.toInt() and 0xFFFF
        // 3 字节保留位
        buf.get(); buf.get(); buf.get()
        val count = buf.short.toInt() and 0xFFFF
        val items = ArrayList<ItemDefEntry>(count)
        for (i in 0 until count) {
            items.add(ItemDefEntry.decode(buf))
        }
        return items
    }

    /**
     * 将条目列表序列化组装为明文 SSD 数据块
     */
    fun buildBlock(items: List<ItemDefEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        out.u8(0x18)
        out.u16le(0) // blockLen 占位 (组装完成后回填)
        out.u8(0); out.u8(0); out.u8(0) // 3 字节保留位
        out.u16le(items.size)
        for (item in items) {
            item.encode(out)
        }
        val ba = out.toByteArray()
        val blockLen = (ba.size - 5) and 0xFFFF
        ba[1] = (blockLen and 0xFF).toByte()
        ba[2] = ((blockLen shr 8) and 0xFF).toByte()
        return ba
    }

    /** 组装条目列表为明文块, 再交给统一的 [ssdEncode] 下发 (尾部余数由其负责)。 */
    fun encode(items: List<ItemDefEntry>): ByteArray = ssdEncode(buildBlock(items))

    /**
     * 基准定义表 (882 条) —— 与 `cap/chan_s2c_op60_46885B_0.bin` 解码后逐字节一致。
     * ⚠️ 这份是**皮蛋 (Dao) 变体**: 角色卡是 itemId 1 / name "Dao" / skinId 0,
     *    所有 category=3 (表情/动作) 定义也是 name "Dao" / skinId 0。
     *    真实服务器会按玩家当前角色重建整张表 (见 [itemsFor])。
     */
    private val BASE_ITEMS: List<ItemDefEntry> by lazy { loadBaseItems() }

    /**
     * 角色卡定义模板。
     * 取自 `cap/practice_s2c_op60_46883B_0.bin` (宝宝会话) 的 itemId 5:
     *   category 15 / name "Boz" / **skinId 5** / targetCharId 0 / param 0 / flags 1 / status 0
     */
    private fun charCardDef(bomber: Int): ItemDefEntry {
        val (itemId, model) = bomberCharCard(bomber)
        return ItemDefEntry(
            itemId = itemId,
            category = 15,
            name = model,
            skinId = bomber,
            targetCharId = 0,
            param = 0,
            desc = "",
            flags = 1,
            status = 0,
        )
    }

    /**
     * 按角色生成 S→C op 60 的物品定义表。
     *
     * 逆向/抓包证据 (chan 皮蛋 46885B vs practice 宝宝 46883B, 同 882 条同长度):
     *   - 角色卡: 皮蛋 = itemId **1** / name "Dao" / skinId **0**
     *             宝宝 = itemId **5** / name "Boz" / skinId **5**
     *   - 所有 category=3 (表情/动作, itemId 30000~37555):
     *             皮蛋 = name "Dao" / skinId 0；宝宝 = name "Boz" / skinId 5
     *   ⇒ **op 60 是按玩家当前角色重建的**, 角色模型名与 skinId 都在这里。
     *     必要性待确认。发错也不会把当前选中角色改成别的
     *
     * 做法: 把基准表的皮蛋卡换成当前角色的卡, 并把所有 cat=3 定义的 name/skinId 换成当前角色。
     */
    fun itemsFor(bomber: Int): List<ItemDefEntry> {
        val (charItemId, model) = bomberCharCard(bomber)
        val hasOwnCard = charItemId != 1 && BASE_ITEMS.any { it.itemId == charItemId && it.category == 15 }

        val list = ArrayList<ItemDefEntry>(BASE_ITEMS.size)
        BASE_ITEMS.forEach { e ->
            when {
                // 基准表里的皮蛋卡
                e.itemId == 1 -> when {
                    charItemId == 1 -> list += e.copy(name = model, skinId = bomber)
                    // 目标角色有独立定义 (如蓝妹妹 2082 进阶卡) 时, 只把皮蛋卡去掉
                    hasOwnCard -> Unit
                    else -> list += charCardDef(bomber)
                }
                // 表情/动作模型跟角色走: name = 角色模型目录, skinId = 角色 ID
                e.category == 3 -> list += e.copy(name = model, skinId = bomber)
                else -> list += e
            }
        }

        // 激活蓝妹妹 (Posh Evie / LuxuryMarid, ID 10) 角色进阶卡:
        // 对应 Item 2082: Category = 16 (0x10), skinId = 10, targetCharId = 4 (Marid)
        val idx2082 = list.indexOfFirst { it.itemId == 2082 }
        if (idx2082 != -1) {
            list[idx2082] = list[idx2082].copy(
                category = 0x10,
                skinId = 10,
                targetCharId = 4,
            )
        }
        return list
    }

    /** 按角色缓存的已加密 op 60 数据块 (46KB, 每位角色只算一次) */
    private val blobs = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()

    fun blobFor(bomber: Int): ByteArray = blobs.computeIfAbsent(bomber) { encode(itemsFor(it)) }

    /** 默认 (皮蛋) 定义表, 供不关心角色的调用方使用 */
    val DEFAULT_ITEMS: List<ItemDefEntry> by lazy { itemsFor(0) }

    /** 默认 (皮蛋) 的已加密数据块 */
    val CACHED_BLOB: ByteArray by lazy { blobFor(0) }

    /**
     * 玩家可见的 op 60 定义表 (当前角色版本)。JoinSessionReq / StageInitReq 必须用这个。
     */
    fun itemsForPacket(bomber: Int): ByteArray = blobFor(bomber)

    /**
     * itemId → 定义 的查询表（服务端内部用: 背包条目取 name 令牌、装备槽分组等）。
     * 以皮蛋基准表为准, 另外补齐 1..7 角色卡 (客户端那张表里同一时刻只存在一个角色卡,
     * 但服务端要能解析任意角色卡 itemId)。
     */
    private val byId: Map<Int, ItemDefEntry> by lazy {
        val m = HashMap<Int, ItemDefEntry>(DEFAULT_ITEMS.size + 8)
        DEFAULT_ITEMS.forEach { m[it.itemId] = it }
        for (b in 0..7) {
            val def = charCardDef(b)
            if (!m.containsKey(def.itemId) || m[def.itemId]!!.category != 15) m[def.itemId] = def
        }
        m
    }

    fun find(itemId: Int): ItemDefEntry? = byId[itemId]
}

/**
 * 进频道批次回应: 物品唯一定义表 (Opcode 60, S→C)
 *
 * 先传玩家当前角色，按角色重建 (角色卡 itemId/skinId + 所有 cat=3
 * 表情/动作定义的模型名)。传错不会更改当前选中的角色类型。
 */
fun ItemDef(bomber: Int = 0): PKT = PKT {
    val blob = ItemDef.itemsForPacket(bomber)
    Encode4(blob.size)
    EncodeBuffer(blob)
}

fun ItemDef(items: List<ItemDefEntry>?) = PKT {
    val blob = if (items == null) ItemDef.CACHED_BLOB else ItemDef.encode(items)
    Encode4(blob.size)
    EncodeBuffer(blob)
}
