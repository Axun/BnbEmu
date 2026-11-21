package bnbemu.packet.shop

import bnbemu.netty.PKT
import bnbemu.codec.buildSsdBlock
import bnbemu.codec.ssdEncode

/**
 * S→C op 204: 商城字串表 (ShopStringTable / stringtable.ini 快取)
 *
 * 客户端链 (CA.exe EN v18):
 *   CStage::OnPacket 的 op 204 分支 → sub_4F8183 → CShopItemMan::sub_670A9D (0x00670A9D)
 *   → 写 stringtable.ini、刷新版本；回应 C→S op 278。
 *
 * 包体布局与 op 66 同构：`E4(块大小) + ssdEncode(0x18 智能流块)`；
 * 流数据 = `u16le(条数) + N × [u32le(id) + 字串]`，字串 = `u32le(长度) + 原文`
 * （客户端 sub_78CE60 先读 u32 长度再拷字节）。
 *
 * 目前只掌握空表样本：cap/chan_s2c_op204_14B_0.bin 与 op66 抓包除 opcode 外完全相同，
 * 故这里只下发 0 条；将来要下发真实字串表时按上述布局补 encode 即可。
 */
fun ShopStringTable() = PKT {
    val block = buildSsdBlock(byteArrayOf(0, 0)) // u16le(0)：0 条
    Encode4(block.size)
    EncodeBuffer(ssdEncode(block))
}
