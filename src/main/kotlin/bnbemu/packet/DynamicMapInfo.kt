package bnbemu.packet

import bnbemu.data.FxdynMapInfo
import bnbemu.netty.PKT

// ============================================================================
// S→C op 210：CDynamicMapMan 动态地图表（进频道握手推送 #8, C→S 81 空包后, seq=121）
// ============================================================================
// CA.exe 大厅分发器 0x502F94 的 op 210 case 为 CDynamicMapMan 加载动态地图表并写盘 fxdynmapinfo.ssd），见 docs/client/ssd-file.md。
//
// 2026-11-04 起不再回放抓包常量：包体由唯一事实源
//   src/main/resources/data/fxdynmapinfo.json 的 342 条 records 实时序列化，
//   与 C→S op 85/92 的 mapId→地图名反查共用同一内存对象（handler/room/StartGameReq.kt）。
//   改 JSON 后重启即可同时改变下发内容与 mapId 编号，无需再同步第二张表。
//
// 包体 = Encode2(210) [框架写入] + Encode4(24117) + EncodeBuffer(SSD 块)；
//   SSD 块未压缩: [0x18][u24 LE 24112][00][compress=0] + 24111 B 记录表, 共 24117 B。
//   EncodeBuffer 对尾部 1 B 余数做 XOR 0x5A，故线上末字节为 0x5A（明文末字节 0x00），
//   并非额外的 Encode1(0) 字段。客户端收到后自行加 ssi1 头、Adler-32 变体校验和并 zlib 压缩写盘。
//   基准: cap/chan_s2c_op210_24123B_0.bin / decoded_dump.txt:152（Kotlin 输出已逐字节对齐）。
//
// 命名约束：OpcodeManager 的 filteredName 由编译产物
// `DynamicMapInfoKt$DynamicMapInfo$$inlined$PKT$1` 取到函数名 DynamicMapInfo，
// 所以 4 份 send.yaml 的 key 必须同步（EN/TW/JP=210、CN=209），且函数名不能带下划线
// （filteredName 会 substringBefore('_') 截断）。非 EN 客户端仍按抓包行为只推一个 bool。
fun DynamicMapInfo() = PKT {
    EncodeBuffer(FxdynMapInfo.packetBlob)
}
