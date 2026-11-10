package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.packet.room.SelectMap
import bnbemu.packet.room.SetPlayer
import bnbemu.server.User
import bnbemu.server.Room
import bnbemu.server.bomber

// ============================================================================
// 进练习房 (C→S op 75) 回应链 — 按 tst.pcap stream 4 真实线序 (TCP seq) 原样重放
// ============================================================================
// 客户端在练习房界面点"开始"发出 op 75 (body = E2(0) E2(0) E1(0) E1(1) E1(5) E1(15) E4(66)),
// 服务器连发房间设置; 随后客户端自动跟发 297/302 → 服务器回 [108, 106×8];
// 客户端再发 op 92 → 服务器回 [111, 112 SelectMap, 100(77B), 136] 启动游戏;
// 之后对局完全由客户端本地 AI 推进 (服务端只在 C→S op 106 时回 S→C op 139 / 2.5s 后 op 138)。
// ⚠️ 真实服务器另会按地图事件时间轴发 S→C op 172 (MapEventSync, 驱动 "Wave/Game/AirPlane1"),
//    它既不是 "ItemThrow" 也与 AI 行动无关, 见 packet/room/MapEventSync.kt 与 tools/rev_op172.py。
// 工具: tools/extract_op75.py / tools/gen_practice_kotlin.py; 基准 cap/practice_s2c_op*.bin


// ---- op 113 (2B body): E2(0) 空字符串 —— S→C「房间名 / 会话名同步」(2026-11-08 反编译钉死) ----
// EN_OP113 = a5 69 不是「数值 0」: 它是 EncodeStr(房名) 的 2B 长度前缀 = 0 加 0B 内容
// （完整包 cap/practice_s2c_op113_4B_0.bin = a5 18 a5 69, 前 2B 是 Encode2(113) 的 opcode）。
// 客户端三份逐字节相同的 handler 副本, 说明这是各房间阶段都能收的通用包:
//   CSlotStage::OnPacket     0x004F480C  case 113 → 0x004F0B07
//   CPrepareStage::OnPacket  0x0052A949  case 113 → 0x0051F86F
//   对局阶段 OnPacket        0x00616D59  case 113 → 0x0061273B
// handler 内部:
//   sub_78FDF0 → sub_78F910  读 [E2 长度][内容] 到 std::string（长度恒 Encode2）
//   长度 > 0x16 时 sub_421EE6(begin+21, end) 截到 21 字符（房名长度上限）
//   sub_68872B(g_Session, str) → sub_44886E(this+113, str) 把名字赋给全局会话对象 +0x1C4 的字符串
// 同一字段的其它写入源反证语义:
//   0x005147B9 建房: 先把玩家输入的房名存进去, 再用同一字符串发 C→S op 75 第 1 字段（房名）;
//   0x00506513 (S→C op 83 进房结果): 用房号到房间缓存取条目的名字写进去;
//   0x004EC7F8 case 3: 用空串清空该字段并切场景（byte_88AF4F = '\0'）。
// ⇒ op 113 = 服务端权威回显 / 变更当前房间名（可覆盖客户端输入, 例如敏感词过滤后的房名）。
//   练习房不带房名（op 75 第 1 字段也是 E2(0)）, 所以抓包为空串; 做普通房时应发真实房名。
//   等价结构化写法 EncodeStr(name), 空串即本 blob; 现保留原样重放以对齐抓包基准。
//   函数名沿用 Unk113（EN_18_send.yaml 绑定名）, 语义已确认, 勿再当未知数值字段。
fun Unk113() = PKT {
    EncodeBuffer(EN_OP113)
}

// ---- op 100 房间设置版 (78B) / 游戏开始版 (77B) — 官方命名 MakeSetPlayerPacket / SetPlayer ----
fun PracticeRoomInfo(user: User? = null, level: Int = user?.level ?: 5) =
    SetPlayer(slotId = 0, user = user, level = level, isStart = false)

fun PracticeStartInfo(user: User? = null, level: Int = user?.level ?: 5) =
    SetPlayer(slotId = 0, user = user, level = level, isStart = true)

// ---- op 108 练习房开局选图回应 (8B): E2(result=0) E2(themeId=0) E4(mapId=172) ----
//    CA.exe CPrepareStage::OnPacket (0x52A949) 的 case 108 = 0x51EFE0 是选图结果处理;
//    这里复用结构化 SelectMap 生成, 与 cap/practice_s2c_op108_10B_0.bin 的 body 逐字节一致。
fun PracticeSelectMap() = SelectMap(result = 0, themeId = 0, mapId = 172)

// ---- op 111 空体 (1B): E1(0) —— 开局时的房间 UI 开关通知 ----
// ⚠️ 历史误标：函数名 PracticeAiLevel / yaml 的 AILevelChange: 111 都沿袭 TW_5 的猜测，
//    它跟 AI 难度档毫无关系。CA.exe sub_5285BD (reference/CA.exe.c:249938) 只读 1B 参数，
//    case 0/1 调虚表 +0x1C4(this, 0) 关掉房间 UI 按钮，完全不碰全局槽位结构
//    (0x685E9B / 0x686A4C，也就是 op 103 写 AI 等级的地方)。
fun PracticeAiLevel() = PKT {
    EncodeBuffer(EN_OP111)
}

// ---- op 136 棋盘/开局数据块 (178B) — 原样重放 ----
fun PracticeBoard() = PKT {
    EncodeBuffer(EN_OP136)
}
