package bnbemu.codec

import bnbemu.ACP
import java.io.ByteArrayOutputStream

// ---- 塊拼裝小工具 (塊內欄位皆為明文, 勿用會 XOR 的 Encode1/2/4 DSL; op13 共用) ----

internal fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)

internal fun ByteArrayOutputStream.u16le(v: Int) {
    u8(v); u8(v shr 8)
}

internal fun ByteArrayOutputStream.u32le(v: Int) {
    u8(v); u8(v shr 8); u8(v shr 16); u8(v shr 24)
}

internal fun ByteArrayOutputStream.ipv4(ip: String) = ip.split('.').forEach { u8(it.toInt()) } // BE

// SIM 字符串格式 = [u32 LE 串长][字节]（客户端 CISmartStream::ReadString sub_78CE60），
// op1 MultiServerInit 的 multisvr.ssd 体也用这一套
internal fun ByteArrayOutputStream.ss(s: String) {
    val a = s.toByteArray(ACP)
    u32le(a.size)
    write(a)
}
