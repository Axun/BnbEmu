package pingu.handler

import pingu.netty.PKTHandler
import pingu.packet.FirstInfoInit
import pingu.packet.FirstInfoTail
import pingu.packet.FirstSessionNotify
import pingu.packet.MultiServerInit
import pingu.packet.MultiServerTail

// 會話連線(首連)打開請求: EN 客戶端連上 3838 後發的第一個業務包
// (start.pcap stream 0, op 0, payload 22B = 5×Encode4, 內容伺服器端不使用)
// 真實伺服器收到後在同一連線上主動推送啟動序列: 193 | 2 FirstInfoInit | 67 | 1 MultiServerInit | 51
// (NA_12 同構: AskMultiServer 後推 192 | 2 | 67 | 61 | 1 | 51, 見 AskMultiServer.kt 註解)
// 缺少 op 1 MultiServerInit 客戶端就拿不到 login 伺服器地址, 報"無法連接登入伺服器"
// server = C???Pool::OnFirstSession (原始名稱未知, opcode 對應見 EN_0_recv.yaml)
val AskFirstSession = PKTHandler { c ->
    c.send(
        FirstSessionNotify(),
        FirstInfoInit(),
        FirstInfoTail(),
        MultiServerInit(),
        MultiServerTail(),
    )
}
