package pingu.packet

import pingu.netty.PKT

/**
 * S->C op 259: 商城连线与进入许可响应 (EnterShopResult)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 对应 CShopConnectionMan::OnPacket (0x005BE76B)
 * - 接收解码:
 *   - Decode1(): resultCode (1=成功, 0=失败并弹窗 "CannotConnectToShopServer")
 * - 成功处理逻辑:
 *   - 当 [CShopConnectionMan + 0x10] == 2 (等待进入商城锁) 时:
 *     - 清除等待锁 [esi + 0x10] = 0;
 *     - 触发 0x005BE647 发送 op 37 及 op 317;
 *     - 触发 0x004F73B2 发送 op 97 (Encode1(1)).
 */
fun EnterShopResult(success: Boolean = true) = PKT {
    Encode1(if (success) 1 else 0)
}
