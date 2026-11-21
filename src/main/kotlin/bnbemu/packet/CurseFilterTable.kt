package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.netty.buildSsdBlock
import bnbemu.netty.ssdEncode
import java.io.ByteArrayOutputStream

// ============================================================================
// S→C op 51：CurseFilterTable（首连推送 #5，start.pcap sq=55）— filterstr.ssd 敏感词库
// ============================================================================
// 旧名 MultiServerTail 是按推送顺序猜的（排在 op 1 MultiServerInit 之后），与 multiserver 无关；
// 2026-11-18 按客户端 CCurseFilter 正名（同 DynamicMapInfo 从 EnableShop 正名的先例）。
// opcode 绑定靠编译产物类名 `CurseFilterTableKt$CurseFilterTable...` 推导，opcode_send.yaml 的 key 必须同名。
//
// 载荷（39 B = 2 B opcode + 37 B，基准 cap/firstconn_s2c_op51.bin）：
//   Encode1Bool(true)        // 1 = 后面跟资料块（客户端写 filterstr.ssd）
//   Encode4(32)              // 资料块长度 = 下面 SSD 块的字节数
//   EncodeBuffer(32 B 块)    // = ssdEncode(buildSsdBlock(词条流))
// ⚠ 与 op 1 不同，本包**没有**结尾的第二个 Encode1（op 1 是 info/notice 双块，这里只有一块）。
//
// ── 客户端侧（reference/CA.exe.c）────────────────────────────────────────
//   op 51 分发 = sub_6B976E (:605774)，逐字节读法：
//     if (ReadUInt8() != 0) { sub_78F1E0 读块 → sub_6B920C 解析 → sub_6B8F7E 校验和比对后写盘 }
//     else                  { sub_78BDD0 用本地缓存流 → sub_6B920C }   // flag=0 时回落到本地 filterstr.ssd
//   词条流解析 = sub_6B920C (:605472)，即下面 curseFilterStream 的结构来源：
//     if (sub_78BB40(流) != 0) return;        // sub_78BB40 = (ReadPos == Len)，流空则不解析
//     count = ReadUInt16()                    // u16 LE 词条数（sub_78BE30）
//     count × {
//        group = ReadUInt8()                  // u8 分组（sub_78BE00）
//        word  = ReadString()                 // u32 LE 串长 + 字节（CISmartStream::ReadString sub_78CE60）
//        extra = ReadString()
//        条目 = new(0x20) 两个 CMyStr（sub_6B8B6F）；
//        bucket = (首字符小写化) % 6（sub_6B8BA1）→ 按 group 落进 this+37 / this+19 / this+55
//        三组数组的 bucket 槽（sub_6B9105 追加）
//     }
//   匹配 = sub_6B8FD2 (:605295)：用 _mbsnbicmp 大小写不敏感比较条目的**第一个串 word**；
//   第二个串 extra 只被存进条目 +16 后原样保留，匹配路径从不读取（语义未钉死，抓包 "Normal"）。
//   三组数组对应 sub_6B8FD2 的 a8 场景参数 0/1/2 —— 同一份词表按 group 分给不同检查场景，
//   某组为空则该场景不做屏蔽（模拟器只下发 group 0，与抓包一致）。
//   版本判定：sub_6B8F7E 用 sub_78BB50（SSD 块明文上的 Adler-32 变体）与本地缓存比对，
//   不同才写盘 —— 与 op 212 同一套校验和，客户端不会因为重复下发而重写文件。
//
// ── 块编码 ─────────────────────────────────────────────────────────────
//   块头沿用 op1/op11 同一条规律：`[0x18][u32 LE 块长-5][compress]`（buildSsdBlock 负责），
//   线上变换由 ssdEncode 负责。compression 交给 buildSsdBlock 判定：抓包 26 B 明文压不小，
//   走未压缩分支（块 32 B / compress=0），与基准逐字节一致；词表很长时客户端同样支持 compress=1
//   （sub_78C120 的 inflate 分支）。
//
// ── 抓包基准（cap/firstconn_s2c_op51.bin，39 B）────────────────────────
//   块明文 32 B = `18 1b 00 00 00 00 | 01 00 | 00 | 09 00 00 00 "Channel 1" | 06 00 00 00 "Normal"`
//   → 1 条词条：group=0 / word="Channel 1" / extra="Normal"（词条流 26 B 恰好读空）。
//   旧实现把这 32 B 当 hex 常量回放，注释还误标成「频道名资料块」；实际是 CCurseFilter 的
//   敏感词库（聊天与起名的屏蔽词），真服当年只下发这一条占位词。
//   同一份表的版本戳就是 C→S op 55 上报的 curseFilterRev（抓包 0x380a0583）：客户端
//   sub_6B8E15 / sub_6B8F7E 用 sub_78BB50 对块明文算 Adler-32 变体当 revision，
//   BnbEmu 目前不读该值（见 handler/StringTableRevisionReq.kt），故未与这里联动。
// ============================================================================

/**
 * filterstr.ssd 的一条词条（客户端 CCurseFilter 条目，0x20 B = 两个 CMyStr）。
 *
 * 只有 [word] 参与实际匹配；[group] 决定词条进哪一组数组，[extra] 客户端只存不读。
 */
data class CurseFilterEntry(
    /** 过滤词：客户端 _mbsnbicmp 大小写不敏感比较的串（条目第一个 CMyStr） */
    val word: String,
    /**
     * 分组（线上 u8）：0/1/2 对应客户端三组词表数组，也就是 sub_6B8FD2 的场景参数 a8=0/1/2；
     * 落到哪一组只影响「哪个检查场景会命中它」。抓包为 0，模拟器只下发 0 组。
     */
    val group: Int = FILTER_GROUP_DEFAULT,
    /**
     * 伴随串（条目第二个 CMyStr）：客户端存进条目 +16 后从不读取，匹配逻辑不参与，语义未钉死。
     * 抓包值 "Normal"，原样保留以便与基准逐字节一致。
     */
    val extra: String = "",
)

/** 抓包词条用的分组号（0 = 客户端第一组数组；另两组抓包未下发，含义未钉死） */
internal const val FILTER_GROUP_DEFAULT = 0

/** 抓包 filterstr 词表（就一条占位词；要加屏蔽词往这里加即可，块长与包长自动跟着变） */
internal val EN_CURSE_FILTER = listOf(
    CurseFilterEntry(word = "Channel 1", group = FILTER_GROUP_DEFAULT, extra = "Normal"),
)

// server = CCurseFilter::OnPacket 的下发端（客户端处理函数 sub_6B976E，opcode 对应见 opcode_send.yaml）
fun CurseFilterTable(entries: List<CurseFilterEntry> = EN_CURSE_FILTER) = PKT {
    // 块 = SSD 容器（[0x18][u32 块长-5][compress] + 词条流）→ ssdEncode 上线上字节
    val block = ssdEncode(buildSsdBlock(curseFilterStream(entries)))
    Encode1Bool(true)
    Encode4(block.size)
    EncodeBuffer(block)
}

/**
 * 组装 filterstr.ssd 缓存体（词条流，抓包 26 B）。字段顺序见文件头注释，串长恒为 u32 LE。
 * 只产出纯明文；块头/压缩由 `buildSsdBlock` 负责，线上变换由 `ssdEncode` 负责。
 */
internal fun curseFilterStream(entries: List<CurseFilterEntry>): ByteArray {
    require(entries.size <= 0xFFFF) { "filterstr 词条数 ${entries.size} 超出 u16 上限" }
    val plain = ByteArrayOutputStream()
    plain.u16le(entries.size)
    for (e in entries) {
        require(e.group in 0..2) { "filterstr 分组 ${e.group} 超出客户端数组范围 0..2" }
        plain.u8(e.group)
        plain.ss(e.word)
        plain.ss(e.extra)
    }
    return plain.toByteArray()
}
