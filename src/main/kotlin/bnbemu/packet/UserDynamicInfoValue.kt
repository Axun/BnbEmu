package bnbemu.packet

import bnbemu.netty.PKT

/**
 * S->C op 122: 本机玩家动态属性 —— **单值同步通道**
 *
 * 逆向分析 (CA.exe EN v18):
 * - 派发: `CStage`/大厅阶段分派器 `0x00502AE1` 命中 case 122 → `0x004F80D0` 解析本包。
 * - 包体: `Encode1(key) + Encode4(value)`
 * - 落地: `0x004F80D0` 读完后调 `CUserDynamicInfo::Set(key, value)` (`0x006E4E23`)：
 *   先取该槽的数值上限 `0x005062E9`（= 3,000,000 × 上限倍率，倍率字段见 op 20 第 7 项），
 *   再钳制并写入 `CUserDynamicInfo + 3*key + 12`。
 *
 * ⚠️ `key` 是**本机玩家槽 0/1**，不是"货币种类"：
 *   op 20（同名单例的整包解析 `0x006E6109`）里同一个下标明确是遍历"第几个本机玩家"，
 *   两个槽的容量由 `eh vector constructor iterator(size=0xC, count=2)` 决定，
 *   getter 一律 `if (idx > 1) return 0`。旧注释写的"0=常规 Lucci / 1=段位赛 Lucci"是误判。
 *   抓包 `[E1(0)][E4(5000)]` = 本机玩家 0 的初始 Lucci 5000，与 op 4 的 v31=5000、
 *   passport 0x2E 初始金币完全对应。
 *
 * 命名说明: 客户端里 `CUserDynamicInfo` 的整包解析入口是 op 20（见 `packet/UserDynamicInfo.kt`，
 * 与客户端类同名），本包只是它的一个 setter 端点，故加 `Value` 后缀区分。
 */
fun UserDynamicInfoValue(key: Int = 0, value: Int = 1234567) = PKT {
    Encode1(key)
    Encode4(value)
}
