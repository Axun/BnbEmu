package bnbemu.packet.shop

import bnbemu.netty.PKT
import bnbemu.netty.buildSsdBlock
import bnbemu.netty.ssdEncode
import bnbemu.packet.u16le
import bnbemu.packet.u32le
import java.io.ByteArrayOutputStream

/**
 * S→C op 66: 商城套餐映射表 (ShopPackageMap / packagemap.ini 快取)
 *
 * 客户端链 (CA.exe EN v18):
 *   CStage::OnPacket 0x00502AE1 的 op 66 分支 (0x00502DE9) → sub_4F8233 (0x004F8233)
 *   → sub_78F1E0 / sub_78E2F0 读 `E4(len) + 密文块`，sub_75DAF0 反混淆
 *   → sub_78C120 校验 0x18 容器并解出智能流 → CShopItemMan::sub_670BD6 (0x00670BD6)
 *   → 写 packagemap.ini、刷新版本、置 [CShopItemMan+0x30] 已加载
 *
 * 线上载荷 = `E4(加密块字节数) + ssdEncode(块)`，块由 [buildSsdBlock] 生成：
 *   `[0x18][u32le(体长)][压缩标志][体]`
 * 体（智能流数据） = `u16le(条数) + N × [u32le(套餐 itemId) + u32le(子商品 itemId)]`
 * （客户端 sub_660E12 用 sub_78BE60 连读两个 u32；sub_666D3A/sub_666CCE 表明 key 是套餐
 *   自身的 itemId、value 是套餐包含的子商品 itemId，供商城把套餐展开成单品。）
 *
 * 空表（默认）体 = `00 00` → 块 = `18 03 00 00 00 00 00 00`，线上 12 B：
 *   `96 CA 53 9D | 96 CA 50 8D | 96 CA 53 95`，与 cap/chan_s2c_op66_14B_0.bin 逐字节一致。
 *
 * ⚠️ 空表落盘后 adler32Zero(体) = 0，而 sub_670D66 要求缓存版本非 0，所以客户端每次进商城
 *    都会重新发 C→S op 258 再要一次本包（见 ShopPackageMapReq）——预期行为，不是漏包。
 * ⚠️ 别与 C→S op 66 UserRestrictReq（My Page 名片请求）混淆，两者方向与语义无关。
 */
data class PackageMapEntry(
    /** 套餐/礼包自身的商城 itemId（客户端映射表的 key） */
    val packageItemId: Int,
    /** 套餐展开后包含的子商品 itemId（value） */
    val memberItemId: Int,
)

fun ShopPackageMap(entries: List<PackageMapEntry> = emptyList()): PKT {
    require(entries.size <= 0xFFFF) { "PackageMap 条数超出 u16 上限: ${entries.size}" }
    return PKT {
        val body = ByteArrayOutputStream().apply {
            u16le(entries.size)
            entries.forEach {
                u32le(it.packageItemId)
                u32le(it.memberItemId)
            }
        }.toByteArray()
        val block = buildSsdBlock(body)
        Encode4(block.size)
        EncodeBuffer(ssdEncode(block))
    }
}
