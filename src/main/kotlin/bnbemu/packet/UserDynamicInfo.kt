package bnbemu.packet

import bnbemu.netty.PKT

// ============================================================================
// S→C op 20 (0x14): 本机玩家动态档案快照 (整包)
// ============================================================================
// 命名依据: 客户端处理入口就是 `CUserDynamicInfo::OnPacket` (0x006E6109)，本包一次性把
// 该单例里"会变的玩家属性"（等级 / Lucci / 天梯段位与积分 / 胜负场 / 身上道具 / 角色 / 名字）
// 全部刷一遍；op 122 只是它的单值 setter 端点（见 packet/UserDynamicInfoValue.kt），只用于改金币。
//
// ---- 分发链 (地址实证) ----
//   CLobbyStage::OnPacket (0x00510287, 虚表 0x8A250C slot 48)
//     └ 未命中 → sub_4EEB50 (0x004EEB50) → 未命中 → sub_502AE1 (0x00502AE1)  ← 大厅二级分派器
//           v3 = Decode2(); v9 = v3-19; if (v9-1 == 0) → (*(*this + 236))(this, packet)   ← opcode 20
//     └ 虚表 +236 = sub_4F9485 (0x004F9485)
//           CUserDynamicInfo::OnPacket (0x006E6109) 逐字段解析
//           解析完: if (CUserDynamicInfo::HasFeature(7)) → 置 dword_993E64+36 = 1
//
// ---- 包体结构 ----
//   E1(count) + count × 43B 记录        count = 本机玩家数，有效 0..2
//   （两个客户端单例都是 2 槽：CUserDynamicInfo = dword_993E70，
//     CLadderInfo = dword_9982D0；getter 一律 `if (idx > 1) return 0`）
//   单条记录 43B 与抓包完全一致：
//     cap/chan_s2c_op20_46B_0.bin      (皮蛋会话) 第 29B = 0x5A → bomber 0 (Dao)
//     cap/practice_s2c_op20_44B_0.bin  (宝宝会话) 第 29B = 0x5F → bomber 5 (Boz)
//
// ---- 记录内字段表 (序号/偏移/宽度/落地位置) ----
//   #   偏移 宽度 写入目标                        含义
//   1   1    E2   CUDI + 3s + 6                    等级 (游戏开始时作为 level 传给 sub_629E2A)
//   2   3    E1   CLadderInfo + 3s + 7             天梯段位
//   3   4    E4   CLadderInfo + 3s + 1             天梯数值 A
//   4   8    E1   CLadderInfo + 3s + 25            天梯数值 E
//   5   9    E4   CLadderInfo + 3s + 19            天梯数值 D
//   6   13   E1   CLadderInfo + 3s + 13            天梯积分/点数 (与段位一起喂 UI sub_4D5AAC)
//   7   14   E1   CUDI + 3s + 175                  Lucci 上限倍率 (0 视作 1；上限 = 3,000,000 × 它)
//   8   15   E4   CUDI + 3s + 12                   Lucci 金币值 (与 op 122 同一存储/同一上限钳制)
//   9   19   E4   CUDI + 3s + 69 / +63             复合槽位 id = 24×大类+子槽，并缓存 值/24 作大类
//   10  23   E1   CUDI + 3s + 75                   仅登出/重置路径读（非 0 才清标志），见 op4 #14
//   11  24   E4   CUDI + 3s + 87                   权限项 id（查 CPermissionMan 权限表，op48 建表）
//   12  28   E1   CUDI + 3s + 111                  **角色(bomber) id**，经 CBomberMan 归一化；==13 另置标记
//   13  29   E2   CUDI + 3s + 123                  **背包等级**，见下方说明
//   14  31   E1   CUDI + 3s + 129                  未知
//   15  32   E1   CUDI + 3s + 135                  未知
//   16  33   Str  CUDI + 6s + 162 (std::string)    字符串，抓包为空串
//   17  35   E1   byte[CUDI + 0x2B8 + s]           未知
//   18  36   E1   byte[CUDI + 0x2BA + s]           未知
//   19  37   E4   CUDI + 3s + 199                  未知 (与 #11 同类 setter)
//   20  41   E1   CUDI + 3s + 36                   胜场 (sub_6E504C 用它算 胜/(胜+负))
//   21  42   E1   CUDI + 3s + 42                   负场
//   22  43   E1   CUDI + 3s + 48                   场次统计第三项
//   (CUDI = CUserDynamicInfo 单例, 偏移单位为 dword, s = 本机玩家槽)
//
// ---- 可选尾部 (本抓包不含, 服务器不要发) ----
//   · 当特性 sub_6C054E(11) != 0 时, 循环后还会读 1 个 E4 → CUDI + 3s + 211
//   · 之后再无条件读 1 个字符串 → 写入 CUDI + 6s + 217
//   抓包在 44B 处恰好到 EOF，这两段被读成 0/空串，因此**多打任何字节都会污染字段对齐**。
//
// ⚠️ 旧实现原样重放皮蛋版 (整个 44B blob)，于是 op 81 批次一到，
//    客户端就把本机角色改回皮蛋 → 之后 C→S op 73 上报 `bomber=0`
//    个人资料 / 纸娃娃 / op 198 的默认角色卡全部回退。所以第 12 项必须是当前角色。
//
// ---- 第 13 项 = 背包等级 (2026-11-10 反汇编 CA.exe 钉死) ----
//   op 20 解析函数 loc_6E6109 里 0x6E6233 `call 0x78F090` (Decode2) 之后紧跟
//   0x6E623C `call 0x663F28`，即 sub_663F28(CUDI, playerIdx, v) →
//   `this + 3*playerIdx + 123` (dword 下标) = 字节 492 = dyn+0x1EC。
//   同一个字段被 sub_651445 读出，供 CUserInventory::GetBagSize (0x659361)
//   算 `容量 = 8 × 背包等级`；IsBagFull (0x659520) 判定 `已用 >= 容量`。
//   ⇒ 本包写 0 会让容量变 0，空背包也判满，商城购买恒弹
//     "Your item bag is full. Purchase an item bag?"。
//
//   等级本来只由 S→C op 413 的 50 条 10007 槽位逐条 +1 抬到 50 (见 UserInventoryInfo.kt)。
//   sub_663F28 的调用点 0x570079 会先判「当前等级 < skinId(50)」才写 min(skinId, 当前+1)，
//   即上限钳制到 50，所以本包先写 50 不会被 op 413 累加成 100。
//   ⇒ 默认就填 DEFAULT_BAG_LEVEL：任何单独下发 op 20 的路径都不会把容量清成 0，
//     不必再依赖「后面必须跟一发 op 413」这个隐含约定。
const val DEFAULT_BAG_LEVEL = 50

fun UserDynamicInfo(
    bomber: Int = 0,
    level: Int = 1,
    playerCount: Int = 1,
    bagLevel: Int = DEFAULT_BAG_LEVEL,
) = PKT {
    Encode1(playerCount)          //   记录条数
    repeat(playerCount) {
        Encode2(level)          //   1  #1  等级 (=1, 新账号; 抓包 a5 68 即 Encode2(1))
        Encode1(0)          //   3  #2  天梯段位
        Encode4(0)          //   4  #3  天梯数值 A
        Encode1(0)          //   8  #4  天梯数值 E
        Encode4(0)          //   9  #5  天梯数值 D
        Encode1(0)          //  13  #6  天梯积分
        Encode1(0)          //  14  #7  Lucci 上限倍率
        Encode4(0)          //  15  #8  Lucci 金币值
        Encode4(0)          //  19  #9  复合槽位 id
        Encode1(0)          //  23  #10 未知
        Encode4(0)          //  24  #11 权限项 id (sub_6E5182 按 id 查 CPermissionMan)
        Encode1(bomber)     //  28  #12 角色 ID (皮蛋=0 / 宝宝=5 …)
        Encode2(bagLevel)   //  29  #13 背包等级 (默认 50; 抓包回放需显式传 0)
        Encode1(0)          //  31  #14 未知
        Encode1(0)          //  32  #15 未知
        Encode2(0)          //  33  #16 字符串 (长度 0 = 空串)
        Encode1(0)          //  35  #17 未知
        Encode1(0)          //  36  #18 未知
        Encode4(0)          //  37  #19 未知
        Encode1(0)          //  41  #20 胜场
        Encode1(0)          //  42  #21 负场
        Encode1(0)          //  43  #22 场次统计第三项
    }
}
