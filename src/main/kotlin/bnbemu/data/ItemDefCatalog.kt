package bnbemu.data

import bnbemu.ACP
import bnbemu.domain.CharacterRules
import bnbemu.netty.buildSsdBlock
import bnbemu.netty.ssdEncode
import bnbemu.packet.u16le
import bnbemu.packet.u32le
import bnbemu.packet.u8
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * 物品定义条目 (与 CA.exe CUserItem / UserItem 0x00676CFA 一一对应)
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
 * 物品定义主数据目录（与 [MapCatalog] 平级）。
 * 负责加载 `data/item_def_base.bin`、按角色构建物品定义表及服务端查询。
 */
object ItemDefCatalog {
    fun loadBaseItems(): List<ItemDefEntry> = decodeBlock(DataResource.bytes("data/item_def_base.bin"))

    fun decodeBlock(bytes: ByteArray): List<ItemDefEntry> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.get().toInt() and 0xFF
        require(magic == 0x18) { "Invalid SSD magic: 0x${magic.toString(16)}" }
        buf.short // blockLen
        buf.get(); buf.get(); buf.get()
        val count = buf.short.toInt() and 0xFFFF
        val items = ArrayList<ItemDefEntry>(count)
        for (i in 0 until count) {
            items.add(ItemDefEntry.decode(buf))
        }
        return items
    }

    fun buildBlock(items: List<ItemDefEntry>, compress: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16le(items.size)
        for (item in items) {
            item.encode(out)
        }
        return buildSsdBlock(out.toByteArray(), compress)
    }

    fun encode(items: List<ItemDefEntry>): ByteArray = ssdEncode(buildBlock(items))

    private val BASE_ITEMS: List<ItemDefEntry> by lazy { loadBaseItems() }

    private fun charCardDef(bomber: Int): ItemDefEntry {
        val (itemId, model) = CharacterRules.bomberCharCard(bomber)
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

    fun itemsFor(bomber: Int): List<ItemDefEntry> {
        val (charItemId, model) = CharacterRules.bomberCharCard(bomber)
        val hasOwnCard = charItemId != 1 && BASE_ITEMS.any { it.itemId == charItemId && it.category == 15 }

        val list = ArrayList<ItemDefEntry>(BASE_ITEMS.size)
        BASE_ITEMS.forEach { e ->
            when {
                e.itemId == 1 -> when {
                    charItemId == 1 -> list += e.copy(name = model, skinId = bomber)
                    hasOwnCard -> Unit
                    else -> list += charCardDef(bomber)
                }
                e.category == 3 -> list += e.copy(name = model, skinId = bomber)
                else -> list += e
            }
        }

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

    private val blobs = ConcurrentHashMap<Int, ByteArray>()

    fun blobFor(bomber: Int): ByteArray = blobs.computeIfAbsent(bomber) { encode(itemsFor(it)) }

    val DEFAULT_ITEMS: List<ItemDefEntry> by lazy { itemsFor(0) }

    val CACHED_BLOB: ByteArray by lazy { blobFor(0) }

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
