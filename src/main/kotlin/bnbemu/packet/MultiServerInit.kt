package bnbemu.packet

import bnbemu.isEN
import bnbemu.netty.PKT
import bnbemu.netty.toBA

// server = CMultiServer::Init
fun MultiServerInit() = PKT {
    if (isEN) {
        // EN 真實伺服器首連推送 #4 (start.pcap sq=52): 伺服器列表
        // 客戶端據此取得 login 伺服器地址並寫 multisvr.ssd 快取, 缺它客戶端報"無法連接登入伺服器"
        // 結構: Encode1Bool(true) + Encode4(46) + 44B SSD 資料塊 + Encode2(0) + Encode1(0)
        // SSD 資料塊解碼 (每 dword BE ^ 0x96CA5395 後 LE 寫回) = multisvr.ssd 快取體:
        //   18290000 | 00 00 01 06 00 00 00 "PopTag" | 0b 00 00 00 "Main Server"
        //   | 00 00 00 00 | 01 00 | IPv4 | port(0x0EFE=3838, LE)
        // IP 原抓包為 66.135.19.7, 已改寫為 127.0.0.1 (與 tools/patch_ip.py 一致)
        Encode1Bool(true)
        Encode4(46)
        EncodeBuffer(EN_MULTISVR_BLOB)
        Encode2(0)
        Encode1(0)
    } else {
        Encode1Bool()
        // TODO
        Encode1Bool()
        // TODO
    }
}

// 44B 編碼塊 (其後的 Encode2(0) 原樣尾 = 抓包 a5 69, 共 46B 子塊)
// 生成工具: tools/extract_first_push.py
private val EN_MULTISVR_BLOB = """
    96ca7a8d 90cb5395 c6ca5395 f79e23fa
    96ca58f2 ffab1e95 f39973fb e4af25e7
    96ca5395 96b55394 98345295
""".toBA()
