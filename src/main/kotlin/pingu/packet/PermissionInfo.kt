package pingu.packet

import pingu.netty.PKT

// ============================================================================
// S→C op 48: 玩家權限設定 / 功能開關 (線序 seq=43, 0B 或列表體)
// ============================================================================
// 逆向確認:
//   CA.exe 0x00502D2C (CStage::OnPacket) -> 0x004DA64B (CPermissionMan::OnPacket).
//   單例 0x009950D4: TSingleton<CPermissionMan> (虛表 0x0089DB60).
//   若載荷為空 (read_ptr >= end_ptr), 直接返回完成 (進頻道握手 op 7 後回此空包).
//   若載荷非空, 循環讀取: Encode4(permissionId) + 16 × Encode1(flags), 存入 CPermissionMan.
fun PermissionInfo(body: ByteArray? = null) = PKT {
    if (body != null) {
        EncodeBuffer(body)
    }
}
