package bnbemu.packet.lobby

import bnbemu.netty.PKT

// ============================================================================
// S→C op 254: 本机玩家自身信息 / 等级面板数据 (进频道批次回应之一, seq=70, 54B body)
// ============================================================================
// 客户端处理链 (CA.exe EN v18):
//   0x005030E1 (CStage::OnPacket) → 0x004F83B9 → 0x004E09D6 (CUserMap::OnPacket)
//   → 逐槽 sub_4E061C (UserSimpleInfo::Read, 0x004E061C) → 尾部 2 字段在 4E09D6 内读
//   落点: CUserMap (0x00998414) 的「自身」数组 base+10132, 每槽 404B; 访问器 sub_4E01B2
//   ⚠️ 条目数 = GetLocalPlayerCount(1~2)，即**每个本机玩家一条**（不是别的玩家！）。
//      绘制点 0x0050E83B (大厅左上等级面板) 就是 sub_4E01B2(CUserMap, 本机槽) 取这条:
//      名牌 + 段位图标 + 等级数字 + 名字 + 经验环，全部来自本包。
//      op 72 频道用户列表 / op 81 名册 复用同一个 UserSimpleInfo::Read，字段布局相同，
//      但**它们不读 tail4/tail1**（op72 每槽 41B 就结束，抓包 396B/8 人逐字节吻合）。
//
// 字段表 (偏移 = UserSimpleInfo 内偏移；Evidence = 客户端读取点)
//   off    编码  名称              证据 / 语义
//   +0x008 E2+串 会话令牌          角色名令牌；4E09D6 另把它拷进 CUserInfoMan (sub_6CFA62)
//   +0x020 E1    unk32             未见读取点，官方包恒 0
//   +0x02C E1    grade             段位码：用户 tooltip (0x0064139C) 用它判 "Grade/PreGrade"
//                                   (sub_6E451E: 100..106 为特殊段位)；本模拟器 0
//   +0x038 E4    unk56             未见读取点，官方包恒 0
//   +0x044 E1    unk68             未见读取点，官方包恒 0
//   +0x050 E4    unk80             未见读取点，官方包恒 0
//   +0x05C E2    level             **等级**：0x50E83B 用 "Lv.%3d" 绘制 (sub_44B790(v+92))
//   +0x068 E4    levelIconEffect   等级图标特效组：sub_694982 → 查表得
//                                   "Level/Rotate%03d" + "Level/Twinkle%03d" 两个动画号；
//                                   官方服务器对**所有**玩家都发 50（抓包 level=1 与 326 均 50）
//   +0x0D4 E4    nameplate         **名牌背景 (ID deco)**：sub_694232 → 载入
//                                   "IDDeco/IDDeco_C%03d" 贴图，CIDDecoEffector 画在名字底框；
//                                   序号来自 ItemDef category 18 的 skinId (Icon/ItemIDdeco_C###)，
//                                   0 = 未穿戴。抓包样本 16/17 (等级 326/317 的两名玩家)
//   +0x100 E4    unk256            feature 6 开启时 >0 表示 +0x11C/+0x120 处的账号信息有效
//                                   (0x0064139C: sub_6C054E(6) && *(v+256) > 0 才显示两行式 tooltip)
//   +0x104 E4    unk260            客户端内部另有 setter (sub_45BFF4)，未见绘制读取点
//   +0x110 E4    unk272            未见读取点，官方包恒 0
//   +0x11C E4    unk284            4E09D6 → sub_6CF1CA(CUserInfoMan, 槽, 值)，但该 setter
//                                   只收 char ⇒ 客户端只用低 8 位；官方**恒 0**，语义未定
//                                   (账号 userId 走 op 4 的 E4，不经这里)
//   +0x138 E2    unk312            未见读取点，官方包恒 0
//   +0x13C E2    gradeStep         段位子档：0x50E83B → sub_6936DD(..., grade, gradeStep)
//                                   → "GradeDeco_S%03d_%02d" 的 S### 部分 (0 = 不画)
//   +0x140 E2    unk320            未见读取点，官方包恒 0
//   +0x16C E2    unk364            feature 0x2F(47) 才写入 (+0x16C)，本客户端关闭，官方包恒 0
//   +0x17C E4    expPercent        **经验千分比 0..999**：0x50E83B 末尾
//                                   `v13 = sub_44B790(v+380); sub_5056EB(ctx, v13)`，
//                                   后者 `60 * v / 999` 画经验条/环 (贴图 "Etc/ExpImg")；
//                                   满级玩家发 999（本地经济模型里 exp 就是等级内千分比）
//   +0x188 E1    unk392            未见读取点，官方包恒 0
//
// 条件字段 (本 EN 客户端特性全关，**不可补发**，否则后续字段错位):
//   · feature 6  → 在 +0x11C 之后追加 E2+串 (账号名)，用于 tooltip 的 "  %s\n%d" 两行样式
//   · feature 47 → 在 tail4/tail1 **之前**追加 3×E1 = 胜/负/平
//                  (sub_6E4C72/6E4C8C/6E4CA6 → CUserDynamicInfo 的 3 个数组，
//                   与 op 4 第 18 项同一组 setter)
//   抓包 54B body 逐字节吻合「无追加」，且 sub_6C054E 读的是本地 CNewConfig，不随服务器变。
//
// 本机玩家自己的资料；其它玩家在 op 72。
data class SelfUserInfoEntry(
    /** +0x008 E2(len)+串: 角色名令牌 (encodeName) */
    val session: ByteArray = ByteArray(0),
    /** +0x020 E1: 未知（官方恒 0） */
    val unk32: Int = 0,
    /** +0x02C E1: 段位码（0=无；100..106 = 特殊段位，客户端 tooltip 用 sub_6E451E 判定） */
    val grade: Int = 0,
    /** +0x038 E4: 未知（官方恒 0） */
    val unk56: Int = 0,
    /** +0x044 E1: 未知（官方恒 0） */
    val unk68: Int = 0,
    /** +0x050 E4: 未知（官方恒 0） */
    val unk80: Int = 0,
    /** +0x05C E2: 等级 */
    val level: Int = 5,
    /** +0x068 E4: 等级图标特效组 (Level/Rotate + Level/Twinkle)，官方恒 50 */
    val levelIconEffect: Int = 50,
    /** +0x0D4 E4: 名牌背景 IDDeco 序号（ItemDef category 18 的 skinId；0=无） */
    val nameplate: Int = 0,
    /** +0x100 E4: feature 6 时为「账号名有效」标志 */
    val unk256: Int = 0,
    /** +0x104 E4: 未知（客户端内部 setter 存在，未见绘制读取） */
    val unk260: Int = 0,
    /** +0x110 E4: 未知（官方恒 0） */
    val unk272: Int = 0,
    /** +0x11C E4: 未知 —— 客户端只取低 8 位写进 CUserInfoMan `+3*槽+88`（byte 型 CVar），
     *  与名字串一起下发；官方抓包为 0（不是账号 userId，账号数值走 op 4 / op 227） */
    val unk284: Int = 0,
    /** +0x138 E2: 未知（官方恒 0） */
    val unk312: Int = 0,
    /** +0x13C E2: 段位子档（GradeDeco_S%03d_%02d 的 S###；0=不画） */
    val gradeStep: Int = 0,
    /** +0x140 E2: 未知（官方恒 0） */
    val unk320: Int = 0,
    /** +0x16C E2: feature 47 才写入；未知（官方恒 0） */
    val unk364: Int = 0,
    /** +0x17C E4: 经验千分比 0..999（等级面板经验环） */
    val expPercent: Int = 0,
    /** +0x188 E1: 未知（官方恒 0） */
    val unk392: Int = 0,
)

fun SelfUserInfo(
    user: SelfUserInfoEntry? = null
) = PKT {
    if (user == null) {
        Encode1(0)
    } else {
        Encode1(1)
        Encode2(user.session.size)
        EncodeBuffer(user.session)
        Encode1(user.unk32)
        Encode1(user.grade)
        Encode4(user.unk56)
        Encode1(user.unk68)
        Encode4(user.unk80)
        Encode2(user.level)
        Encode4(user.levelIconEffect)
        Encode4(user.nameplate)
        Encode4(user.unk256)
        Encode4(user.unk260)
        Encode1(user.unk272)
        Encode4(user.unk284)
        Encode2(user.unk312)
        Encode2(user.gradeStep)
        Encode2(user.unk320)
        Encode2(user.unk364)
        Encode4(user.expPercent)
        Encode1(user.unk392)
    }
}
