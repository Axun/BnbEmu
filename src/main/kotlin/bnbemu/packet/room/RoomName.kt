package bnbemu.packet.room

import bnbemu.ACP
import bnbemu.netty.PKT

// ============================================================================
// S→C op 113 — 房间名 / 会话名同步
// ============================================================================
// 线上 body 只有一个字段：`EncodeCipherStr(房名)`，即
//   `E2(len ^ 0xA569)` + `len` 字节经 0x75DAF0 令牌变换的内容
// —— 与 C→S op 75 的房名 / 密码同一套编码（见 handler/CreateSessionReq.kt）。
//
// 客户端三份逐字节相同的 handler（各房间阶段都能收，所以是通用包）：
//   CSlotStage::OnPacket     0x004F480C  case 113 → 0x004F0B07
//   CPrepareStage::OnPacket  0x0052A949  case 113 → 0x0051F86F
//   对局阶段 OnPacket        0x00616D59  case 113 → 0x0061273B
// handler 内部（三份完全一致）：
//   sub_78FDF0 → sub_78F910       读 [E2 长度][内容] 到 std::string；
//                                 degree 1..3 时长度走 sub_75DA10（^0xA569）、
//                                 内容走 sub_75DAF0（4B BE^0x96CA5395→LE / 余2B ^0xA569 / 余1B ^0x5A）
//   sub_421EE6(begin+21, end)     长度 > 0x16 时截到 21 字节（房名上限）
//   sub_68872B(g_Session, str)    → sub_44886E(this+113, str)，即会话对象 +0x1C4 的房名字段
//                                 （sub_687C94 是同一字段的 getter，房间标题等处据此取值）
//   sub_71DC38()                  刷新 UI
//
// 同一字段的其它写入源反证语义：
//   0x005147B9 建房：先把玩家输入的房名存进去，再用同一字符串发 C→S op 75 第 1 字段；
//   0x00506513 (S→C op 83 进房结果)：用房号到房间缓存取条目的房名写进去；
//   0x004EC7F8 case 3：用空串清空该字段并切场景。
// ⇒ op 113 是**服务端权威回显 / 覆盖当前房名**。练习房没有房名（op 75 第 1 字段也是 E2(0)），
//   所以 tst.pcap 抓到的 body 只有 2B `a5 69`（= E2(0) 空串）；普通房必须发真实房名，
//   否则空串会把客户端已从 op 83 / op 87 拿到的房名清掉（房间标题变空白）。
//
// 回归基准：cap/practice_s2c_op113_4B_0.bin = `a5 18 a5 69`（前 2B 为 Encode2(113)），
// 由 tools/replay_test.py 的 practice 用例逐字节断言；空房名时本函数产出与之一致的 2B body。
// ============================================================================

/** 客户端 sub_421EE6 把房名截到 21 字节；服务端先截断，保证下发的就是客户端最终存下的值。 */
const val ROOM_NAME_MAX_BYTES = 21

/**
 * @param name 房名明文（`Room.name`）。超长按字符边界截到 [ROOM_NAME_MAX_BYTES] 字节，
 *   不会把 CN/TW/JP 码页的双字节字符劈成半个。
 */
fun RoomName(name: String) = PKT {
    EncodeCipherStr(name.fitToBytes(ROOM_NAME_MAX_BYTES))
}

/**
 * 按字符边界截断到 [maxBytes] 个 ACP 字节。
 *
 * 只在超长时逐字符累计 UTF-16 code point 的编码长度，避免 `String.take(21)` 这类按 UTF-16
 * 字符裁剪在双字节码页上仍可能越界、或在代理对上劈开 emoji。
 */
private fun String.fitToBytes(maxBytes: Int): String {
    if (toByteArray(ACP).size <= maxBytes) return this
    val sb = StringBuilder(length)
    var used = 0
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        val chunk = String(Character.toChars(cp))
        val n = chunk.toByteArray(ACP).size
        if (used + n > maxBytes) break
        sb.append(chunk)
        used += n
        i += Character.charCount(cp)
    }
    return sb.toString()
}
