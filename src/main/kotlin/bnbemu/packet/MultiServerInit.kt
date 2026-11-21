package bnbemu.packet

import bnbemu.codec.*
import bnbemu.netty.PKT
import bnbemu.codec.buildSsdBlock
import bnbemu.codec.ssdEncode
import java.io.ByteArrayOutputStream

// ============================================================================
// S→C op 1：MultiServerInit（首连推送 #4，start.pcap sq=52）— 登录服务器地址表
// ============================================================================
// 载荷（54 B = 2 B opcode + 52 B）：
//   Encode1Bool(true)        // 1 = 后面跟 info 资料块（写 multisvr.ssd）
//   Encode4(46)              // 资料块长度 = 下面 SSD 块的字节数
//   EncodeBuffer(46 B 块)    // = ssdEncode(buildSsdBlock(info 流))
//   Encode1(0)               // 0 = 不带 notice 资料块（multisvrn.ssd）
//
// ── 客户端侧（reference/CA.exe.c）────────────────────────────────────────
//   op 1 处理 = sub_6CDA60 (:622998)，逐字节读法：
//     flag1 = ReadUInt8();  if (flag1 == 1) { 块 → 解析 → 写 multisvr.ssd }
//     flag2 = ReadUInt8();  if (flag2 == 1) { 块 → 解析 → 写 multisvrn.ssd }
//   块的读取 sub_78F1E0 → sub_78E2F0 (:750130)：degree 1..3 的流读 `[E4 len][len B]`，
//   拿到 len B 后做 sub_75DAF0 反混淆（= 本项目的 ssdEncode 逆变换），再交 sub_78C120。
//   sub_78C120 (:748271) 校验块头 0x18 → `[0x18][u32 LE 块长][compress]...`：
//     compress=0 → 其后就是明文；compress=1 → `[u32 LE 解压后长度][zlib 流]`（sub_78BB60）。
//   info 流解析 = sub_6CD446 (:622698)，写盘 = sub_78C4A0（外面再套 `ssi1` 头 + Adler-32 变体）。
//   ⚠ 两个 flag 是否都读取决于配置掩码：sub_6C04F0(0x44)（CNewConfig 掩码含 0x04/0x40）为真才走
//     双块分支，否则只读 info 块；两条分支都接受本包布局（末字节 flag2=0 最坏情况被忽略）。
//
// ── info 流（全部明文，SIM 内部串长是 u32 LE 而非包层的 Encode2）──────────────
//   u8  mCnt                                  // multiserver 条数（抓包 1）
//   mCnt × [ str name ][ str label ][ u32 param ]
//   u16 lCnt                                  // login 服务器条数（抓包 1）
//   lCnt × [ 4 B IPv4 顺序字节 ][ u16 port ][ u8 flag ]
//   u8  currentServer                         // 抓包 0
//   其中 str = `[u32 LE 串长][字节]`（客户端 CISmartStream::ReadString sub_78CE60 :748981）。
//
// ── 抓包基准（cap/firstconn_s2c_op1.bin）────────────────────────────────
//   name="PopTag" / label="Main Server"（这一栏是显示文案，**不是 IP**）/ param=0 /
//   lCnt=1 / 66.135.19.7 / 3838(0x0EFE) / flag=0 / current=0 → info 流 40 B。
//   40 B 压不小，客户端服务端自身的判定（sub_78BC60：压缩后 +4 < 原长才压缩）走未压缩分支，
//   故块 = `[0x18][u32 LE 41][0x00]` + 40 B = 46 B；再经 ssdEncode 得线上 46 B
//   （44 B 的 11 个 dword + 尾部 2 B Encode2 形态 `a5 69`）。
//   模拟器默认把 IP 换成 127.0.0.1（改地址需与 tools/client/patch_ip.py 同步），其余字段与抓包一致，
//
// ============================================================================

/** 服务器组条目（客户端 `_MULTISERVER`，60 B = SN 4 + 两个 std::string 28×2） */
data class MultiServerEntry(
    /** strName：服务器组名，登录界面下拉框文本来源（客户端 sub_4D969F :183663 取结构 +4 的串） */
    val name: String,
    /**
     * strIp：原服务端这一栏放的是显示文案（抓包 = "Main Server"），**不是 IP**；
     * 客户端 sub_6CD446 只把它当普通串存进列表，连接地址一律取 [LoginServerEntry.ip]。
     */
    val label: String,
    /** dwParam：抓包 0，客户端未见使用，语义未钉死 */
    val param: Int = 0,
)

/** 登录服务器条目（客户端 `_LOGINSERVER`，36 B = std::string 28 + u16 port + u8 flag） */
data class LoginServerEntry(
    /** 登录服务器 IPv4。改地址须与 `tools/client/patch_ip.py`（客户端二进制侧）同步 */
    val ip: String = "127.0.0.1",
    /** 端口，抓包 3838（0x0EFE） */
    val port: Int = 3838,
    /**
     * nFlag：抓包 0。客户端 sub_6CAFB8 (:621165) 用 `i[8] == a2` 把该字段与传入的服务器序号
     * 比对后筛选出候选登录服务器，语义未钉死，保持抓包值即可。
     */
    val flag: Int = 0,
)

/** 抓包 multiserver 表（只有一条；strIp 栏原文就是 "Main Server"） */
internal val EN_MULTI_SERVERS = listOf(
    MultiServerEntry(name = "PopTag", label = "Main Server"),
)

/** 抓包 login 服务器表（一条 3838）；IP 已按模拟器需要改成回环地址 */
internal val EN_LOGIN_SERVERS = listOf(
    LoginServerEntry(ip = "127.0.0.1", port = 3838, flag = 0),
)

/** info 流末字节：当前服务器序号（抓包 0） */
internal const val EN_CURRENT_SERVER = 0

// server = CMultiServer::Init / GetInfoPacket
fun MultiServerInit(
    multiServers: List<MultiServerEntry> = EN_MULTI_SERVERS,
    loginServers: List<LoginServerEntry> = EN_LOGIN_SERVERS,
    currentServer: Int = EN_CURRENT_SERVER,
) = PKT {
    // 块 = SSD 容器（[0x18][u32 块长][compress] + info 流）→ ssdEncode 上线上字节
    val block = ssdEncode(buildSsdBlock(multisvrInfoStream(multiServers, loginServers, currentServer)))
    Encode1Bool(true)
    Encode4(block.size)
    EncodeBuffer(block)
    Encode1(0)
}

/**
 * 组装 multisvr.ssd 缓存体（info 流，抓包 40 B）。字段顺序见文件头注释，串长恒为 u32 LE。
 * 只产出纯明文；块头/压缩由 `buildSsdBlock` 负责（与客户端 sub_78BC60 同一条判定），
 * 线上变换由 `ssdEncode` 负责。
 */
internal fun multisvrInfoStream(
    multiServers: List<MultiServerEntry>,
    loginServers: List<LoginServerEntry>,
    currentServer: Int,
): ByteArray {
    val plain = ByteArrayOutputStream()
    plain.u8(multiServers.size)
    for (m in multiServers) {
        plain.ss(m.name)
        plain.ss(m.label)
        plain.u32le(m.param)
    }
    plain.u16le(loginServers.size)
    for (l in loginServers) {
        plain.ipv4(l.ip)
        plain.u16le(l.port)
        plain.u8(l.flag)
    }
    plain.u8(currentServer)
    return plain.toByteArray()
}
