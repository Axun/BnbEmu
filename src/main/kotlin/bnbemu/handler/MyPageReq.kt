package bnbemu.handler

import bnbemu.netty.PKTHandler

/**
 * C->S op 301: 打开个人中心阶段通知 (MyPageReq)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 发包点: CA.exe 0x00501B6D (CStage::OnMyPageButton) push 0x12D -> 301, 空包.
 * - 服务端处理: 空消费即可.
 */
val MyPageReq = PKTHandler { _ ->
}
