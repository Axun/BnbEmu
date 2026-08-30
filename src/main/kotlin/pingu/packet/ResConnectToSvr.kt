package pingu.packet

import pingu.isEN
import pingu.netty.PKT

// 選擇頻道的回應
// EN 真實格式 (enter-channel.pcap stream0 登入連線收尾, 半截流暴力對齊 seq 解出):
//   C 發 op 6 ReqConnectToSvr 後, 伺服器回 **S→C op 7** (非 6):
//   E1(res) + E1(ChannelID) + E4(flags, 回顯請求值) + E4(IP) + E2(port)
//   客戶端收到後關閉登入連線, 新開連線到 IP:port 並以 op 7 OnConnEstablished 為首包
// server = CLoginPool::OnCenterResConnectToSvr
fun ResConnectToSvr(res: Int, ChannelID: Int, flags: Int) = PKT {
    Encode1(res)
    if (res == 1) {
        Encode1(ChannelID)
        Encode4(flags)
        Encode4(0x7F000001)
        Encode2(
            when {
                // EN 單埠拓撲: 進頻道連線同樣走 3838 (真實抓包回應 E2(3838))
                isEN -> 3838
                ChannelID != 11 -> 4848
                else -> 4849
            }
        )
    }
}
