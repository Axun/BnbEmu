package pingu.handler

import pingu.netty.PKTHandler
import pingu.packet.ChannelsState

// 頻道狀態輪詢: 客戶端在頻道列表界面週期性發送 (~1s 一次, live 日誌可見連發)
// payload = [E2 序號/校驗(每次變化)][E4 頻道hash][E4 頻道hash] (兩個 hash 同值,
// 與 op 7 OnConnEstablished 內攜帶的雙 hash 一致)
// 真實伺服器的直接回應未被乾淨抓到 (login.pcap +3.281s 發出後 ~0.9s 才出現本就週期性的 op 86;
// enter-channel.pcap 內它是最後一個包即斷網) —— 回 op 13 ChannelsState 與語義吻合
// (請求"狀態"→回各頻道開關/狀態塊), 客戶端靠它刷新頻道列表
// server = CChannelCache::OnChannelsStateReq
val ChannelsStateReq = PKTHandler { c ->
    c send ChannelsState()
}
