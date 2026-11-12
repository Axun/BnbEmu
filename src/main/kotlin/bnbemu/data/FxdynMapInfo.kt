package bnbemu.data

import bnbemu.crypto.Adler32
import bnbemu.netty.ssdEncode
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.yamlList
import com.charleskorn.kaml.yamlMap
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

// ============================================================================
// fxdynmapinfo 单一事实源：src/main/resources/data/fxdynmapinfo.json
// ============================================================================
// 这份 JSON 的 records 数组同时驱动三处，共用同一份内存对象，禁止再生成第二张表：
//   1. S→C op 210 (packet/DynamicMapInfo.kt)：本模块实时序列化成明文 → SSD 块 → 完整包；
//   2. S→C op 212 (packet/FxConfig.kt) 首字段：同一份明文的 SSD 流校验戳（见 checksum），
//      客户端拿它判断 fxdynmapinfo 快取/下发数据是否为当前版本；op210 内容一变此处必须同步；
//   3. C→S op 85/92 的 mapId：客户端把 flag==0 的记录按数组顺序装进 CDynamicMapMan，
//      0-based 下标即 mapId，themeId 等于 sort_order（详见 handler/room/StartGameReq.kt）。
//
// 记录顺序具有协议含义，禁止排序、去重、按 name/id 作 key。
// JSON 是 YAML 1.2 子集，项目已有的 kaml 可直接解析，无需新增依赖。
// Python 侧等价实现与字节验证：tools/fxdynmapinfo_codec.py、tools/build_fxdynmapinfo.py。
//
// 包体编码（与 netty/PKT.kt 的 Encode2/Encode4 一致, SSD 块统一复用 netty/PKT.kt 的 ssdEncode）：
//   Encode2(210)   BE16 写入 210 xor 0xA569
//   Encode4(len)   BE32 写入 len xor 0x96CA5395
//   SSD 块         ssdEncode: 4B 分组 LE 读 → XOR 0x96CA5395 → BE 写；余 2B XOR 0xA569；余 1B XOR 0x5A
// SSD 块长 24117 B (4×6029+1)，最后一字节正是 1B 余数分支：明文末字节 0x00 编码成 0x5A。
// 网络块 compress=0（无 zlib、无 ssi1 头、无 Adler 校验和）；客户端收包后自行压缩写盘成 .ssd。
// ============================================================================

private const val SOURCE_PATH = "data/fxdynmapinfo.json"
private const val SCHEMA = "bnbemu/fxdynmapinfo@1"
private const val OPCODE = 210
private const val BLOCK_MARKER = 0x18
private const val KEY32 = 0x96CA5395.toInt()
private const val KEY16 = 0xA569

/** 与 JSON 中每条记录的 15 个字段一一对应；hex 字段保留字符串形态，序列化时再解码。 */
data class FxdynRecord(
    val id: Int,
    val flag: Int,
    val name: String,
    val display: String,
    val subtype: Int,
    val themeId: Int,
    val group: String,
    val stage: String,
    val tail0: Int,
    val tail1: Int,
    val tailRgb: String,       // 3 B
    val tailFlags: String,     // 4 B
    val sortOrder: Int,        // u16 LE；同时是 themeId 与显示顺序
    val tailType: Int,
    val tailReserved: String,  // 4 B
)

object FxdynMapInfo {
    /** 342 条记录，顺序即协议。进程内只解析一次，改 JSON 后重启生效。 */
    val records: List<FxdynRecord> by lazy { parseRecords(readSourceText()) }

    /** u16 count + 逐条记录；长度随 records 变化（抓包基准 24111 B）。 */
    val plain: ByteArray by lazy { serializePlain(records) }

    /**
     * S→C op 212 首字段：客户端 COSmartStream +0x14 的 fxdynmapinfo 校验戳（版本戳）。
     *
     * 反编译链：sub_532337 (CA.exe.c:258183) 写入 CEffectMan+0x24 → sub_531F79 (:258038)
     * 以 `sub_78BB50(stream) == this+9` 决定是否解析记录表；sub_78BB50 = 流 +0x14，
     * 由 sub_78BB60 (:747998) 对 SSD 块 (a2+5, u24 len) 算 sub_74EE50(0, ...)。
     * 抓包基准 0x36CEE587 已用 cap/fxdynmapinfo_plain.bin (24111 B) 复算一致。
     * 这里从同一份 plain 实时派生，保证 op210 与 op212 永不脱节。
     */
    val checksum: Int by lazy { ssdStreamChecksum(buildBlock(plain)) }

    /** op 210 的 payload（不含框架写入的 2 B opcode），长度随 records 变化。 */
    val packetBlob: ByteArray by lazy {
        buildPacket(plain).let { it.copyOfRange(2, it.size) }
    }

    /** 完整 S→C 包，24123 B；仅用于回归验证，实际发送由框架补 opcode。 */
    fun buildFullPacket(): ByteArray = buildPacket(plain)

    /** 读取同一份源 JSON 文本，供回归测试做「解析 → 序列化」往返验证。 */
    fun readSourceText(): String = DataResource.text(SOURCE_PATH)

    // ------------------------------------------------------------------
    // 读取与解析（fail-fast：数据文件是唯一事实源，缺失/损坏必须立刻失败）
    // ------------------------------------------------------------------

    fun parseRecords(text: String): List<FxdynRecord> {
        val root = Yaml.default.parseToYamlNode(text).yamlMap
        field(root, "schema").let {
            check(it == SCHEMA) { "fxdynmapinfo.json schema 不符: 期望 $SCHEMA, 实得 $it" }
        }
        val list = root.entries.entries.firstOrNull { it.key.content == "records" }?.value?.yamlList
            ?: error("fxdynmapinfo.json 缺少 records 数组")
        check(list.items.isNotEmpty()) { "fxdynmapinfo.json 的 records 为空" }
        check(list.items.size <= 0xFFFF) { "记录数 ${list.items.size} 超过 u16 count 上限" }
        return list.items.mapIndexed { i, node ->
            val r = node.yamlMap
            FxdynRecord(
                id = u32(r, "id"),
                flag = u8(r, "flag"),
                name = latin1(r, "name"),
                display = latin1(r, "display"),
                subtype = u8(r, "subtype"),
                themeId = u8(r, "theme_id"),
                group = latin1(r, "group"),
                stage = latin1(r, "stage"),
                tail0 = u8(r, "tail0"),
                tail1 = u8(r, "tail1"),
                tailRgb = hex(r, "tail_rgb", 3),
                tailFlags = hex(r, "tail_flags", 4),
                sortOrder = u16(r, "sort_order"),
                tailType = u8(r, "tail_type"),
                tailReserved = hex(r, "tail_reserved", 4),
            ).also { check(r.entries.size == 15) { "records[$i] 字段数 ${r.entries.size} != 15" } }
        }
    }

    private fun field(map: YamlMap, key: String): String {
        val node = map.entries.entries.firstOrNull { it.key.content == key }?.value
            ?: error("fxdynmapinfo.json 记录缺少字段 $key")
        return (node as? YamlScalar)?.content
            ?: error("fxdynmapinfo.json 字段 $key 不是标量")
    }

    private fun u8(map: YamlMap, key: String) = int(map, key, 0xFF)
    private fun u16(map: YamlMap, key: String) = int(map, key, 0xFFFF)
    private fun u32(map: YamlMap, key: String) = int(map, key, -1)

    private fun int(map: YamlMap, key: String, max: Int): Int {
        val raw = field(map, key)
        val v = raw.toIntOrNull() ?: error("fxdynmapinfo.json 字段 $key 不是整数: $raw")
        check(v >= 0 && (max == -1 || v <= max)) { "fxdynmapinfo.json 字段 $key 越界: $v" }
        return v
    }

    private fun latin1(map: YamlMap, key: String): String {
        val s = field(map, key)
        check(s.all { it.code <= 0xFF }) { "fxdynmapinfo.json 字段 $key 含非 latin1 字符: $s" }
        return s
    }

    private fun hex(map: YamlMap, key: String, size: Int): String {
        val s = field(map, key)
        check(s.length == size * 2 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "fxdynmapinfo.json 字段 $key 应为 ${size} 字节 hex: $s"
        }
        return s
    }

    // ------------------------------------------------------------------
    // 序列化：records → 记录表明文
    // ------------------------------------------------------------------

    fun serializePlain(records: List<FxdynRecord>): ByteArray {
        check(records.isNotEmpty() && records.size <= 0xFFFF) { "记录数非法: ${records.size}" }
        val w = BinWriter()
        w.u16le(records.size)
        for (r in records) {
            w.u32le(r.id)
            w.u8(r.flag)
            w.str(r.name)
            w.str(r.display)
            w.u8(r.subtype)
            w.u8(r.themeId)
            w.str(r.group)
            w.str(r.stage)
            // 16 B 尾块
            val start = w.size
            w.u8(r.tail0)
            w.u8(r.tail1)
            w.raw(decodeHex(r.tailRgb, 3))
            w.raw(decodeHex(r.tailFlags, 4))
            w.u16le(r.sortOrder)
            w.u8(r.tailType)
            w.raw(decodeHex(r.tailReserved, 4))
            check(w.size - start == 16) { "记录 ${r.name} 尾块长度 ${w.size - start} != 16" }
        }
        return w.toByteArray()
    }

    private fun decodeHex(s: String, size: Int): ByteArray = ByteArray(size) { i ->
        s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    /** [0x18][u24 LE payloadLen][0x00][compress=0][plain]，共 6+plain.size 字节。 */
    fun buildBlock(plain: ByteArray): ByteArray {
        val w = BinWriter()
        w.u8(BLOCK_MARKER)
        w.u24le(plain.size + 1)
        w.u8(0)
        w.u8(0)
        w.raw(plain)
        return w.toByteArray()
    }

    /**
     * 客户端 sub_78BB60 (CA.exe.c:747998) 的校验值：对 SSD 块从 offset 5 起
     * （[compress][plain]）取块头 u24 长度，算 Adler-32 变体（a/b 初值均为 0，
     * 模 65521；标准 zlib.adler32 的 a 初值是 1）。未压缩块前导 0x00 不改变 a/b，
     * 因此等价于对 plain 本身求值。
     *
     * 返回 32 位位模式（可能为负 Int），直接交给 Encode4 即可。
     */
    fun ssdStreamChecksum(block: ByteArray): Int = Adler32.computeSsdBlock(block)

    /** 完整包：Encode2(opcode) + Encode4(blockLen) + EncodeBuffer(SSD 块)。 */
    fun buildPacket(plain: ByteArray, opcode: Int = OPCODE): ByteArray {
        val block = buildBlock(plain)
        val enc = ssdEncode(block)
        val out = ByteArray(6 + enc.size)
        val op = opcode xor KEY16
        out[0] = (op ushr 8 and 0xFF).toByte()
        out[1] = (op and 0xFF).toByte()
        val len = enc.size xor KEY32
        out[2] = (len ushr 24 and 0xFF).toByte()
        out[3] = (len ushr 16 and 0xFF).toByte()
        out[4] = (len ushr 8 and 0xFF).toByte()
        out[5] = (len and 0xFF).toByte()
        System.arraycopy(enc, 0, out, 6, enc.size)
        return out
    }

}

private class BinWriter {
    private val out = ByteArrayOutputStream()
    val size: Int get() = out.size()

    fun u8(v: Int) {
        require(v in 0..0xFF) { "u8 越界: $v" }
        out.write(v)
    }

    fun u16le(v: Int) {
        require(v in 0..0xFFFF) { "u16 越界: $v" }
        u8(v and 0xFF)
        u8(v ushr 8 and 0xFF)
    }

    fun u24le(v: Int) {
        require(v in 0..0xFFFFFF) { "u24 越界: $v" }
        u8(v and 0xFF)
        u8(v ushr 8 and 0xFF)
        u8(v ushr 16 and 0xFF)
    }

    fun u32le(v: Int) {
        u8(v and 0xFF)
        u8(v ushr 8 and 0xFF)
        u8(v ushr 16 and 0xFF)
        u8(v ushr 24 and 0xFF)
    }

    fun str(s: String) {
        val b = s.toByteArray(StandardCharsets.ISO_8859_1)
        u32le(b.size)
        raw(b)
    }

    fun raw(b: ByteArray) = out.write(b, 0, b.size)

    fun toByteArray(): ByteArray = out.toByteArray()
}
