package bnbemu.packet

import bnbemu.Ver
import bnbemu.isEN
import bnbemu.netty.PKT

// 握手
// client = CMainSystem::OnConnect
// server = CMainSystem::OnConnEstablished
fun ConnEstablished() = PKT {
    if (isEN) {
        // 美服 PopTag (EN, Ver=18): 20 字节明文 Banner 动态构建
        // 对应 CA.exe 0x006CE38B (ConnectBanner/ClientInit) 逆向解析
        Encode1(0)          // status / magic (0x00)
        Encode2(Ver)        // minVersion: 最低支持协议版本 (18)
        Encode2(Ver)        // curVersion: 当前服务端协议版本 (18)
        Encode4(0)          // opcodeOffset: 报文偏移 / 加密参数 (0)
        Encode1Bool(true)   // hasExtraOptions: 开启扩展握手选项 (1)
        Encode1(0)          // reserved (0)
        EncodeStr("")       // extraConfigStr: 扩展配置字串 (空字串，占 2B 长度 0)
        Encode4(55)         // ssdVersion: fxdynimginfo.ssd 版本号 (55, 0x37)
        Encode1Bool(false)  // extraFlag / authOption (0)
        Encode2(0)          // extraFeatureWord: 特征选项字 (0)
    } else {
        Encode2() // 好像是能更新的最低版本
        Encode2(Ver)
        Encode4() //  ReceivedPacket Opcode offset
        EncodeStr() // PatchURL

        // NDA
//        if (isNA) {
//            Encode1Bool()
//        }
    }
}