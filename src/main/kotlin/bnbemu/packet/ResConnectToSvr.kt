package bnbemu.packet

import bnbemu.netty.PKT

// 選擇頻道的回應
// EN 真實格式 (enter-channel.pcap stream0 登入連線收尾, 半截流暴力對齊 seq 解出):
//   C 發 op 6 ReqConnectToSvr 後, 伺服器回 **S→C op 7** (非 6):
//   E1(res) + E1(ChannelID) + E4(flags, 回顯請求值) + E4(IP) + E2(port)
//   客戶端收到後關閉登入連線, 新開連線到 IP:port 並以 op 7 OnConnEstablished 為首包
// ⚠️ E4(IP) 就是客戶端進頻道時要連的位址（它不會拿 op1/op11 裡的位址來覆蓋），
//    所以換網段時它必須跟 MultiServerInit.EN_LOGIN_SERVERS 的 IP 一致。
// server = CLoginPool::OnCenterResConnectToSvr


fun ResConnectToSvr(success: Boolean, channelId: Int, idx: Int, serverIp: String = "127.0.0.1") = PKT {
    Encode1(if (success)  1 else 0)
    if (!success) {  // 让客户端弹框进入频道失败
        return@PKT
    }

    val parts = serverIp.split('.')
    require(parts.size == 4) { "不是IPv4位址：$serverIp" }
    val b = parts.map { it.toInt().also { v -> require(v in 0..255) { "ServerIp 段越界：$serverIp" } } }
    val serverIpBin = (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]

    Encode1(channelId)
    Encode4(idx)
    Encode4(serverIpBin)
    // EN 單埠拓撲: 進頻道連線同樣走 3838 (真實抓包回應 E2(3838))
    Encode2(3838)
}
