package bnbemu.packet.user

import bnbemu.domain.MAX_LUCCI
import bnbemu.netty.PKT

// ============================================================================
// S→C op 20 (0x14): 本机玩家动态档案快照 (整包)
// ============================================================================
// 命名依据: 客户端处理入口就是 `CUserDynamicInfo::OnPacket` (0x006E6109)，本包一次性把
// 该单例里"会变的玩家属性"（等级 / Lucci / 天梯段位与积分 / 胜负场 / 身上道具 / 角色 / 名字）
// 全部刷一遍；op 122 只是它的单值 setter 端点（见 packet/UserDynamicInfoValue.kt）。
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
// ---- 记录内字段表 (2026-11-22 逐个跟 setter 复核；s = 本机玩家槽) ----
//   CUDI 里这些槽多数是 12B 的"加密安全值"对象（+8 是堆上的密文缓冲）；下表"落点"给的是
//   访问器算出的 dword 下标，字节地址 = CUDI + 4×下标 + 12×s（布局不保证 12B 对齐，#19 就落在
//   对象 +4 处）。
//   #   偏移 宽度 落点                            setter    含义
//   1   1    E2   CUDI + 3s + 6                    0x6E4AAD  等级 (游戏开始时作为 level 传给 sub_629E2A)
//   2   3    E1   CLadderInfo + 3s + 7             0x6E473B  天梯段位
//   3   4    E4   CLadderInfo + 3s + 1             0x6E4757  天梯数值 A
//   4   8    E1   CLadderInfo + 3s + 25            0x6E478F  天梯数值 E
//   5   9    E4   CLadderInfo + 3s + 19            0x6E47AB  天梯数值 D
//   6   13   E1   CLadderInfo + 3s + 13            0x6E4773  天梯积分/点数
//   7   14   E1   CUDI + 3s + 175                  0x665DB6  Lucci 上限倍率 (0 视作 1；上限 = 3,000,000 × 它)
//   8   15   E4   CUDI + 3s + 12                   0x6E4E23  **Lucci 金币值** (与 op 122 同一 setter，见下)
//   9   19   E4   CUDI + 3s + 69 / + 63            0x6E4AC7  复合槽位 id = 24×大类+子槽，并缓存 值/24 作大类
//   10  23   E1   CUDI + 3s + 75                   0x61C352  **特权/优惠类别** (CSpecialBenefitFactory)
//   11  24   E4   CUDI + 3s + 87                   0x6E4B51  权限项 id (查 CPermissionMan 权限表，op48 建表)
//   12  28   E1   CUDI + 3s + 111                  0x6E4BAB  **角色(bomber) id**，经 CBomberMan 归一化
//   13  29   E2   CUDI + 3s + 123                  0x663F28  **背包等级**，见下方说明
//   14  31   E1   CUDI + 3s + 129                  0x663F42  **第二段道具槽容量计数** (槽数 = 8 + 4×值)
//   15  32   E1   CUDI + 3s + 135                  0x5CE04A  **QMark(问号盒) 待开启标志**
//   16  33   Str  CUDI + 6s + 162 (std::string)    0x6E5246  **情侣/搭档名字**，抓包为空串
//   17  35   E1   byte[CUDI + 0x2B8 + s]           裸字节写  **情侣心等级** (0 = 无情侣，1..6)
//   18  36   E1   byte[CUDI + 0x2BA + s]           裸字节写  **情侣状态码** (0/1/2)
//   19  37   E4   CUDI + 3s + 199                  0x6E4C58  未定 (本版本无读取点，见下)
//   20  41   E1   CUDI + 3s + 36                   0x6E4C72  胜场 (sub_6E504C 用它算 胜/(胜+负))
//   21  42   E1   CUDI + 3s + 42                   0x6E4C8C  负场
//   22  43   E1   CUDI + 3s + 48                   0x6E4CA6  场次统计第三项
//
// ---- 可选尾部 (本抓包不含, 服务器不要发) ----
//   · 当特性 sub_6C054E(11) != 0 时, 循环后还会读 1 个 E4 → CUDI + 3s + 211
//   · 之后再无条件读 1 个字符串 → 写入 CUDI + 6s + 217
//   抓包在 44B 处恰好到 EOF，这两段被读成 0/空串，因此**多打任何字节都会污染字段对齐**。
//
// ⚠️ 旧实现原样重放皮蛋版 (整个 44B blob)，于是 op 81 批次一到，
//    客户端就把本机角色改回皮蛋 → 之后 C→S op 73 上报 `bomber=0`
//    个人资料 / 纸娃娃的角色全部回退。所以第 12 项必须是当前角色。
//
// ---- 第 8 项 = Lucci（本包内唯一的金币写入点） ----
//   解析处 0x6E61E9 `call 0x6E4E23`，即 CUserDynamicInfo::Set：
//     上限 = sub_5062E9(slot) = 倍率(第 7 项) × 0x2DC6C0(3,000,000)，倍率 0 时按 1 算；
//     再 `if (v < 0) → 0; if (v > 上限) → 上限;` 写入 CUDI + 12*slot + 0x30。
//   ⇒ op 20 第 8 项与 op 122（`[E1 slot][E4 value]` → 0x4F80D0 → **同一个 0x6E4E23**）
//     写同一个槽、套同一套钳制，两种下发完全等价。
//   ⇒ 想超过 300 万必须先给第 7 项倍率（第 7 项在记录里排第 8 项之前，同一包即可生效）。
//   ⇒ 本包是"整包快照"：第 8 项发 0 就会把客户端金币清零。所以凡是要发 op 20 的路径都必须
//     带上真实余额，不要再靠"紧跟一发 op 122 补回"——在 op 122 排在前面的批次里
//     （进频道握手就是 122 在前、20 在后）那种补救反而会把刚写入的金币抹回 0。
//   ⇒ 服务端 MAX_LUCCI = 3,000,000 与客户端默认上限一致，故此处按 [MAX_LUCCI] 钳制。
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
//
// ---- 第 14 项 = 第二段道具槽容量（与第 13 项是两个独立计数） ----
//   CUserInventory 的找空位/取槽 (0x658AC2 / 0x658B89 / 0x65A5CD) 一律按
//   `容量 = 8 + 4 × Get(129)` 扫 200B 槽位（含 ≥192 号段）；
//   商城"限购类型 0"的道具也把它当计数 +1（0x663F42 的调用点 0x535150 / 0x543653）：
//   限购类型 1 → 第 13 项背包等级，类型 2 → 第 7 项 Lucci 倍率，其余 → 本项。
//   ⚠️ 与第 13 项的 `8 × 值` 不是同一套容量，别混用。
//
// ---- 第 15 项 = QMark(问号盒) 待开启标志 ----
//   0x65F4C0 买到/收到问号盒时写 1（字串键 "QMarkSysMsgGet"）；
//   0x5CFAF0 开盒前要求它非 0，否则不弹窗；
//   开出 Lucci 的回包 0x408D07 先写 0 再 `Add(0x6E4E23, 当前+奖励)`（"QMarkSysMsgGetLucci"）。
//   ⇒ 服务器下发 0 = 客户端认为没有可开的盒子。
//
// ---- 第 16~18 项 = 情侣(搭档)块 ----
//   24B 字符串 + 两个按槽排列的裸字节 (0x2B8/0x2BA)，与 CCoupleManager (0x993E74：
//   `this + 6*slot + 34` 的 24B 串 + `+slot+46/48`) 布局同构；CCoupleManager 每次刷新都会把
//   `+48 → 0x2B8`、`+46 → 0x2BA` 镜像过来 (0x69A4BA / 0x69A6AE)，故这两个字节是客户端侧真相。
//     · 0x2B8：0 = 无情侣；1..6 = 情侣心等级。
//       0x5DB26B 判 `v4 != 7 || 0x2B8 != 0`，否则弹 "NotHaveCouple"；
//       0x4EB22D 判 `!= 0 && < 7` 才给聊天包挂 "ValentineEventHeart"。
//     · 0x2BA：情侣状态码 0/1/2（`+46 == 1` 表"已配对"，== 2 为另一态；
//       商城 0x541651 / 0x535597 用它拦"仅情侣可买"的道具）。
//   ⇒ #16 在本版本客户端只写不读（无读取点），按同块布局推断为情侣名。
//
// ---- 第 10 项 = 特权/优惠类别 ----
//   两个"用户详情"包 (0x61E678 / 0x6220A8) 与进频道握手都走：
//     sub_6E52EC(CUDI, slot, 1);  if (Get(75) != 0) sub_6E52EC(CUDI, slot, 2);
//   sub_6E52EC 从 `TSingleton<CSpecialBenefitFactory>` (0x9F0570) 按类别把特权条目拷进
//   CUDI + 324 的表 ⇒ 本项 = 该玩家适用的特权类别，0 = 无特权（只装类别 1）。
//   名片槽位视图 CSlotViewerStrategy (0x5F60A0) 也把它拷进 +0x40 参与渲染。
//
// ---- 第 19 项 = 未定 ----
//   与第 11 项同一套 4 字节安全值写入 (0x45BFF4)，落点 CUDI + 12*slot + 0x31C。
//   getter 0x6E4FF4 存在但全 exe 无调用者，除本包外也无其他写入者 ⇒ 写它不影响任何已知
//   逻辑，服务器发 0 即可。
const val DEFAULT_BAG_LEVEL = 50

fun UserDynamicInfo(
    bomber: Int = 0,
    level: Int = 1,
    lucci: Int = 0,
    playerCount: Int = 1,
    bagLevel: Int = DEFAULT_BAG_LEVEL,
    win: Int = 0,
    lose: Int = 0,
    draw: Int = 0,
) = PKT {
    Encode1(playerCount)          //   记录条数
    repeat(playerCount) {
        Encode2(level)          //   1  #1  等级 (=1, 新账号; 抓包 a5 68 即 Encode2(1))
        Encode1(0)          //   3  #2  天梯段位
        Encode4(0)          //   4  #3  天梯数值 A
        Encode1(0)          //   8  #4  天梯数值 E
        Encode4(0)          //   9  #5  天梯数值 D
        Encode1(0)          //  13  #6  天梯积分
        Encode1(0)          //  14  #7  Lucci 上限倍率 (0 = 按 1 倍 → 上限 MAX_LUCCI)
        Encode4(lucci.coerceIn(0, MAX_LUCCI)) //  15  #8  Lucci (与 op 122 同一 setter/同一钳制)
        Encode4(0)          //  19  #9  复合槽位 id
        Encode1(0)          //  23  #10 特权/优惠类别 (0 = 无特权)
        Encode4(0)          //  24  #11 权限项 id (sub_6E5182 按 id 查 CPermissionMan)
        Encode1(bomber)     //  28  #12 角色 ID (皮蛋=0 / 宝宝=5 …)
        Encode2(bagLevel)   //  29  #13 背包等级 (默认 50; 抓包回放需显式传 0)
        Encode1(0)          //  31  #14 第二段道具槽容量计数 (槽数 = 8 + 4×值)
        Encode1(0)          //  32  #15 QMark 待开启标志 (0 = 没有可开的问号盒)
        Encode2(0)          //  33  #16 字符串 (长度 0 = 空串; 情侣名)
        Encode1(0)          //  35  #17 情侣心等级 (0 = 无情侣)
        Encode1(0)          //  36  #18 情侣状态码 (0/1/2)
        Encode4(0)          //  37  #19 未定 (无读取点, 保持 0)
        Encode1(win.coerceIn(0, 255))   //  41  #20 胜场
        Encode1(lose.coerceIn(0, 255))  //  42  #21 负场
        Encode1(draw.coerceIn(0, 255))  //  43  #22 场次统计第三项
    }
}
