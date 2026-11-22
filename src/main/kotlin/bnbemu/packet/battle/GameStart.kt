package bnbemu.packet.battle

import bnbemu.domain.MAX_SESSION_SLOTS
import bnbemu.netty.PKT

// ============================================================================
// S→C op 136: GameStart —— 開局令 (真端 DoStartGame + UDP 對端表)
// ============================================================================
// 逆向確認 (2026-11-22, CA.exe):
//   局內分發表 case 136 → 0x00556B1D → 0x00553AA0 (唯一解析函數, 讀序即下方封包體)。
//   同族 S→C op 171 走 0x00558654, 只讀前 3 個 E4 + 1 個被丟棄的 E1 (多人倒計時 UI);
//   本包是它的完整版: 多帶局次密鑰與 UDP 對端表, 並真正啟動對局時鐘
//   (0x0053291C 啟動 CGameClock, 逐槽 vtable+12/+92 激活 bomber)。
//
// ⚠️ 這個包**不含棋盤/地形**。地圖名與出生點在 op 112 GameStartMapInfo, 方塊表由客戶端
//    按 resource/fx/Map/*.bin 模板本地生成 (服務端沒有方塊模型)。舊名 PracticeBoard 是誤稱。
//
// 真端對照 (reference/古早真端ts实现): TW FxServer 把它拆成兩個包 ——
//   CGameSession::DoStartGame   (S2C 150) = E4 clockStart, E4 serial^version,
//                                            E4 ghostStartSec, E4 ghostFreqSec
//   CGameSession::OnUDPPeerInfo (S2C 120) = N×[E1 occupied + 3×(E4 ip, E2 port)]
//   EN 合併成一個 op 136, 並在前面多帶本機 UDP 端點與對端超時 (0x6E3CE9 / 0x6E3F27)。
//
// 封包體 (抓包 178B: cap/practice_s2c_op136_178B_0.bin; 180B 檔多 2B opcode):
//   - Encode4: clockBaseMs    伺服器 timeGetTime() 毫秒 (真端 m_GameClock.m_dwStartTime)。
//                             客戶端只拿它「啟動」時鐘: epoch = timeGetTime() - ping/2,
//                             值本身寫進 CGameClock+5 後全文件無人讀取。
//   - Encode4: sessionKey     真端 = 局次 serial ^ version (每局 serial++)。
//                             客戶端 0x0054C3E8 用**低 8 位**校驗局內包: 包內字節 == key ^ user+12
//                             (0x00686084 讀 CUser+12 的賬號值)。EN 抓包為 0。
//   - Encode4: timerASec      秒級時長 A, 客戶端 ×1000 寫入 +13808 (0x35F0) 後**無讀取點**。
//                             語義未定案, 兩個候選:
//                               · 真端同位置是地圖模板的 ghostStart (TW DoStartGame 第 3 欄)
//                               · 抓包值 180 = 3 分鐘, 更像對局時限; 參考分支
//                                 (reference/修复房间锁死) 正是按「對局時限」用它做練習房無限時間
//                             兩種讀法對客戶端行為都沒有影響 (寫後不讀)。
//   - Encode4: timerBSec      秒級時長 B, 寫入 +13812, 同樣無人讀取。
//                             真端同位置是 ghostFreq (抓包 0 = 不生成 ghost)。
//   - Encode1: tickFlag       非 0 → 局內 tick 固定 30ms, 否則用設定值
//                             (0x00553AA0: 寫進 CGameSession+63505, 供 op112 位置校驗使用)。
//   - Encode4: localIp        本機 UDP 端點, 客戶端為它建一個 cHost (UDP socket)。
//   - Encode2: localPort
//   - Encode1: peerTimeoutSec 對端存活窗口秒數, 客戶端 ×1000 存進 peer+136 (0x006E347D),
//                             0x006E3491 用它判斷對端是否超時。
//   - GAME_START_SLOT_ENTRIES (= MAX_SESSION_SLOTS + 2 = 10) 項:
//       Encode1: occupied     1 = 該槽名冊裡有人; 0 = 空槽 (只佔 1B)
//       occupied 時再跟 UDP_PEER_ENDPOINTS 組 (Encode4 ip + Encode2 port)
//
// ⚠️ occupied 位是**名冊校驗**, 不是「有沒有 UDP」: 0x00553AA0 對 flag=1 但名冊無人的槽
//    回 C→S op 149 (0x00549C35(-1,0,-1), 即文檔裡的 Ready Ack), 對 flag=0 但名冊有人的槽
//    直接刪除該用戶 (0x006892A2)。所以這一列必須與 op 112 的參戰名單同源。
// ⚠️ 客戶端固定讀 8+2 項 (0x0053350A 取 session 槽數 8, 迴圈跑到 i+2), 最後兩項抓包恆為 0;
//    每槽地址在抓包裡是同一個 relay 端點重複 3 次 (埠 = 4151 + 槽號)。
// ============================================================================

/** 客戶端每槽讀取的 UDP 對端地址組數 (真端 UDP_HOST_CAPACITY = 3)。 */
internal const val UDP_PEER_ENDPOINTS = 3

/** 槽位表項數: session 8 槽 + 客戶端多讀的 2 項 (0x00553AA0 的 `i + 2`)。 */
internal const val GAME_START_SLOT_ENTRIES = MAX_SESSION_SLOTS + 2

/**
 * 一組 UDP 對端地址 (大端 IPv4 + 埠)。
 *
 * [NONE] (0.0.0.0:0) 是佔位值: 客戶端 0x00789FD0 對 `ip == 0 || port == 0` 直接返回 null,
 * 因此不會建 socket、也不會產生任何 UDP 流量 —— 純 TCP 部署用它即可。
 * 將來要接 UDP 通道時再填真實端點 (同槽三組地址, 真端是 NAT 穿透的候選地址表)。
 */
data class UdpEndpoint(val ip: Int, val port: Int) {
    companion object {
        val NONE = UdpEndpoint(0, 0)

        /** 由點分十進制四段構造, 例如 `of(127, 0, 0, 1, 3838)`。 */
        fun of(a: Int, b: Int, c: Int, d: Int, port: Int): UdpEndpoint = UdpEndpoint(
            ((a and 0xFF) shl 24) or ((b and 0xFF) shl 16) or ((c and 0xFF) shl 8) or (d and 0xFF),
            port and 0xFFFF,
        )
    }
}

/**
 * 開局時某個槽位的 UDP 對端表 (快照, 不讀 Room 單例)。
 *
 * [endpoints] 不足 [UDP_PEER_ENDPOINTS] 組時以 [UdpEndpoint.NONE] 補齊, 多餘忽略。
 */
data class GameStartPeer(
    val slotId: Int,
    val endpoints: List<UdpEndpoint> = emptyList(),
)

/** 對應真端 timeGetTime(): 單調遞增毫秒 (JVM 起點任意, 客戶端不消費該值)。 */
private fun monotonicMs(): Int = (System.nanoTime() / 1_000_000L).toInt()

/**
 * S→C op 136: GameStart (開局令)。
 *
 * [peers] 是必填的參戰名冊 (與 op 112 的 `slotConfigs` 同一批槽位), 沒有預設值:
 * 空名冊會讓客戶端把所有人從名冊裡刪掉, 猜一個默認值只會製造更難查的錯位。
 *
 * @param clockBaseMs    伺服器毫秒時鐘 (客戶端只用來啟動 CGameClock, 值本身不讀)
 * @param sessionKey     局次密鑰 (serial ^ version; 局內包低 8 位校驗, 純 TCP 部署填 0)
 * @param timerASec      秒級時長 A (真端 ghostStart / 疑似對局時限; 客戶端不消費)
 * @param timerBSec      秒級時長 B (真端 ghostFreq; 客戶端不消費)
 * @param tickFlag       非 0 → 局內 tick 固定 30ms
 * @param localEndpoint  本機 UDP 端點 (無 UDP 通道時填 [UdpEndpoint.NONE])
 * @param peerTimeoutSec 對端存活窗口秒數
 * @param peers          參戰槽位及其 UDP 對端地址 (按槽號升序, 不重排)
 */
fun GameStart(
    clockBaseMs: Int = monotonicMs(),
    sessionKey: Int = 0,
    timerASec: Int = 0,
    timerBSec: Int = 0,
    tickFlag: Int = 0,
    localEndpoint: UdpEndpoint = UdpEndpoint.NONE,
    peerTimeoutSec: Int = 1,
    peers: List<GameStartPeer>,
) = PKT {
    Encode4(clockBaseMs)
    Encode4(sessionKey)
    Encode4(timerASec)
    Encode4(timerBSec)
    Encode1(tickFlag)

    Encode4(localEndpoint.ip)
    Encode2(localEndpoint.port)
    Encode1(peerTimeoutSec)

    // 客戶端固定按槽號 0..9 逐項讀, 缺項必須顯式寫 Encode1(0), 不能只發參戰槽。
    val bySlot = peers.asSequence()
        .filter { it.slotId in 0 until MAX_SESSION_SLOTS }
        .associateBy { it.slotId }

    for (slot in 0 until GAME_START_SLOT_ENTRIES) {
        val peer = bySlot[slot]
        if (peer == null) {
            Encode1(0)
            continue
        }
        Encode1(1)
        for (i in 0 until UDP_PEER_ENDPOINTS) {
            val endpoint = peer.endpoints.getOrNull(i) ?: UdpEndpoint.NONE
            Encode4(endpoint.ip)
            Encode2(endpoint.port)
        }
    }
}
