package bnbemu.packet

import bnbemu.ACP
import bnbemu.netty.PKT
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

    /**
     * 组装条目列表并加密为 SSD 格式
     * (每 DWORD LE 读取 ^ 0x96CA5395 按 BE 写回, 尾部不足 4B 按 LE 读 ^ 0xA569 按 BE 写回 / ^ 0x5A)
     */
    fun encode(items: List<ItemDefEntry>): ByteArray {
        val plain = buildBlock(items)
        val out = ByteArray(plain.size)
        var i = 0
        while (i + 4 <= plain.size) {
            val le = (plain[i].toInt() and 0xFF) or
                    ((plain[i + 1].toInt() and 0xFF) shl 8) or
                    ((plain[i + 2].toInt() and 0xFF) shl 16) or
                    ((plain[i + 3].toInt() and 0xFF) shl 24)
            val enc = le xor 0x96CA5395.toInt()
            out[i] = (enc ushr 24).toByte()
            out[i + 1] = (enc ushr 16).toByte()
            out[i + 2] = (enc ushr 8).toByte()
            out[i + 3] = enc.toByte()
            i += 4
        }
        var rem = plain.size - i
        if (rem >= 2) {
            val w = (plain[i].toInt() and 0xFF) or ((plain[i + 1].toInt() and 0xFF) shl 8)
            val encW = (w xor 0xA569) and 0xFFFF
            out[i] = (encW ushr 8).toByte()
            out[i + 1] = encW.toByte()
            i += 2
            rem -= 2
        }
        if (rem == 1) {
            out[i] = (plain[i].toInt() xor 0x5A).toByte()
        }
        return out
    }

    /**
     * 默认下发物品定义目录：基础 882 条物品 + 蓝妹妹 (Posh Evie / LuxuryMarid, ID 10) 角色进阶卡定义
     */
    val DEFAULT_ITEMS: List<ItemDefEntry> by lazy {
        val list = loadBaseItems().toMutableList()
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
        list
    }

    /**
     * 缓存的默认已加密数据块 (避免进频道时重复计算 46KB)
     */
    val CACHED_BLOB: ByteArray by lazy {
        encode(DEFAULT_ITEMS)
    }

    /**
     * itemId → 定义 的查询表（按需构建一次）。
     * 用途：把「背包里的物品」拼成 S→C op 198 的条目时需要拿 name 令牌（如 "Item/ShopDeco"）。
     */
    private val byId: Map<Int, ItemDefEntry> by lazy { DEFAULT_ITEMS.associateBy { it.itemId } }

    fun find(itemId: Int): ItemDefEntry? = byId[itemId]
}

/**
 * 进频道批次回应: 物品唯一定义表 (Opcode 60, S→C)
 */
fun ItemDef(): PKT = ItemDef(null)

fun ItemDef(items: List<ItemDefEntry>?) = PKT {
    val blob = if (items == null) ItemDef.CACHED_BLOB else ItemDef.encode(items)
    Encode4(blob.size)
    EncodeBuffer(blob)
}
