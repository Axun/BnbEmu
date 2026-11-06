package bnbemu.packet.shop

import bnbemu.netty.PKT

/**
 * S->C op 201: 商城点券 (NX Cash) 余额同步 —— 客户端的正规通道
 *
 * 逆向确认 (CA.exe EN v18):
 * - 商城舞台 CShopStageNew::OnPacket (sub_5D5CBA, 0x5D5CBA, CA.exe.c:445623) 的 case 201
 *   (CA.exe.c:445654) 转给 sub_5CFE29 (0x5CFE29, CA.exe.c:441186):
 *     `v3 = Packet_ReadUInt32(a2);`
 *     `sub_6E4AFE(dword_993E70, *(this + 31544), v3);`  ← 写点券
 *     `*(_DWORD *)(this + 31608) = 1;`                  ← UI 刷新标志
 *     ... `sub_71DC38();`                                ← 刷新余额显示
 * - sub_6E4AFE (0x6E4AFE, CA.exe.c:678158) = `sub_44B4D1(this + 3*idx + 18, value)`,
 *   即写 CUserDynamicInfo 单例 dword_993E70 的 `+72 + 12*idx`,
 *   与商城 UI 读取点券余额的 sub_6E4E59 (0x6E4E59, 读 `+72 + 12*idx`) 完全对应。
 *   这是客户端专门写 Cash 数组的函数, 不需要像 op 122 那样靠槽位越界复用。
 *
 * 包体:
 *   1. Encode4(cash)  —— 点券余额 (必读, 写进 Cash 数组)
 *   2. Encode4(extra) —— sub_5CFE29 尾部按本地 feature 开关追加读取:
 *      `if (sub_6C05A2(4)) { E1(flag) [+字符串] } else if (sub_6C05A2(16)) { E4(v) -> sub_6E4B18(+96 数组) }`
 *      sub_6C05A2 是 sub_6C04F0 (0x6C04F0, CA.exe.c:647327) 的 thunk, mask 取自
 *      CNewConfig+4, 为 0 时回落 16 → 默认走 flag 16 分支, 故补一个 E4(0) 让它读到安全值。
 *      ⚠️ 若客户端本地 feature mask 含 4 会改走 E1 分支并可能尝试读字符串 (未实测);
 *      抓包中无 op 201 样本可比, 模拟器客户端默认配置未启用该特性。
 */
fun CashBalance(cash: Int, extra: Int = 0) = PKT {
    Encode4(cash)
    Encode4(extra)
}
