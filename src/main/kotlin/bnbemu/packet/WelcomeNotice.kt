package bnbemu.packet

import bnbemu.netty.PKT

/**
 * S→C op 423：服务端下发的广告 / 公告横幅文案（大厅和房间顶部滚动展示）。
 *
 * 逆向结论（抓包基准 cap/practice_s2c_op423_26B_0.bin、cap/chan_s2c_op423_26B_0.bin 逐字节相同，
 * 文案为 "Welcome to PopTag!"；下方默认 text 已改为自定义广告文案）：
 * ```
 * a4 ce                                            opcode = 0xA4CE ^ 0xA569 = 423
 * a5 7b                                            Encode2(18) 令牌串长度
 * f5 a6 36 c2 b6 af 3e fa c6 ea 3c e1 f7 9e 23 fa 84 0e   18B 令牌串
 * 96 ca db 2d                                      Encode4(35000)
 * ```
 * 18B 令牌串经客户端 sub_75DAF0（reference/CA.exe.c:744226）逆变换后是
 * `"Welcome to PopTag!"`：每 4B 以 BE 读 ^0x96CA5395 按 LE 写回（sub_75DA30），
 * 尾 2B ^0xA569（sub_75DA10）、尾 1B ^0x5A（sub_75DA00）；读取入口
 * sub_78F1E0 → sub_78E2F0。本包用的 EncodeCipherStr 就是它的逆变换：传入抓包原文
 * 可逐字节复现 24B body。
 *
 * @param text 广告/公告文案，长度不固定，
 *   EncodeCipherStr 会按实际字节数生成 E2 长度与 SSD 变换，客户端按同一变换解码显示。
 * @param displayMs 末尾 E4 值，抓包恒 35000。推测为显示时长/轮播间隔（客户端公告轮播
 *   sub_4E3F66 用 25000 / 40000 ms 计时），也可能是消息 ID/版本号；语义未实测，
 *   改动会偏离抓包基准，需自行验证客户端表现。
 */
fun WelcomeNotice(
    text: String = "Server Emulator https://github.com/krrr/BnbEmu",
    displayMs: Int = 35000,
) = PKT {
    EncodeCipherStr(text)
    Encode4(displayMs)
}
