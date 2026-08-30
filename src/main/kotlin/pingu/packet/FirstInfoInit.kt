package pingu.packet

import pingu.isEN
import pingu.netty.PKT
import pingu.netty.toBA

// server = CLoginPool::OnAskFirstInfo
fun FirstInfoInit() = PKT {
    if (isEN) {
        // EN 真實伺服器在首連 (會話連線) 上主動推送, 不等客戶端請求 (start.pcap sq=46)
        // 結構: Encode1Bool(true) + Encode4(1854) + 1854B 資料塊
        // 資料塊解碼 (每 dword BE 讀取 ^ 0x96CA5395 後以 LE 寫回) 為字串表
        // (頻道/房間系統訊息: "wins"/"the"/"Channel"/"Normal"/"Room" 等片段), 原樣重放
        Encode1Bool(true)
        Encode4(1854)
        EncodeBuffer(EN_FIRST_INFO_BLOB)
    } else {
//    if (Decode4 == 0)
        EncodeStr("http://mail.nexonclub.com/clubadmin/confirmuser")
        EncodeStr("http://id.nexonclub.com/nxidquery.idsocno")
    }
}

// 生成工具: tools/extract_first_push.py (cap/firstconn_s2c_op2.bin)
private val EN_FIRST_INFO_BLOB = """
    96cd6a8d96ec539596ca5392f1a321c2c4af3ff2c2ca5395b6ba32e7b6ae3df4
    b6ba3ce5f4a53cf2b6b936fce5ea3ce1b6ae3df0fbaf3be1b6a527b5b6af3be1
    f3a227faf3be73e79ceb3ef4f2a436c6f3a227b5e5a53eb5f9ad73e1f3a331fa
    f9be73e6f8a324b596ca42b4e5a31e95f8a53ae6f1a321c2dbaf3ff2968334e6
    f99a5395f9ad73e5f3a331faf9be73e6f8af20b5febe73f1e2ea3ef0febe73fa
    e2a573f0b6b836fdfbab36e1f39959b4e2ea37fbfbea36fdb6be20faf4a53cf2
    b6b936fce1ea3ce192eb3dfcd5ca53959efe62fdc6ca5395b6a220fa92a532d1
    d5ca53959ef262fdc6ca5395b6a220fa92b03ad1d5ca53959ffd62fdc6ca5395
    b6a220fae5b93cd896ca539ee3b226d9f3812ae79ca33be5c6ca5395b6a220fa
    f8bf3fc596ca5bfee6bf0095f89f21f096ca5bfce5a50395fd8373fd96ca4af0
    f9a50195fbab14f8e6b307f0faaf00f0c2be30f0c2a63cfa96d823fcff8c5395
    e4af27f9c5ea37f0f5af3ff0f8a53ae196ca5399f8ab3bd6d2a636fba6a920f0
    96ca539ef3a73cd6b6a43cb59beb3dfcc1ca5395f1ad3ae7f79836f9fba537fb
    96ca53c7e6ab21c1f2a432b5e6a523b5f9a534b5e5af3af7b6a527b5f2a436e6
    f3a227b5f9be73f8f3a227b5febe3cb5e2ea21f0b7a732f0f8af009ffebe73f1
    f9a773f0f1ea27e6ffa83cfae2ea20f0ffbd73fa96c172fbf9845395fbaf27dc
    f8bf3cd396ca1cf1f3a20795f3be3ab5f9b373f8e4ab73e0f9a673f0f8a338fa
    f9ac73f2f7a273e7f9a473e6f3a873e1f0ea3df0f2a426fab6b83cb5f8ea20fc
    f5ea27faf3b821e0efa627fbf7bc32b5f4ab3ffcb6e436f996ca539fe5af26c4
    f7be00e196cf27e7e29953959fbe21f4c7ca5395e2b936e0e6a527c696ca5391
    e6a527c696ca5385f7bf22c6f99e36e7ff9e3ffad48814e596ca5386fda93cc7
    e6ab03b5c5ea21f0e5b93af68cb921fac5ca5395e4ab26e4f9a507f0e6a307f9
    f7881ad4f3a627e1f2a326d296ca43f0e2ab1195b6af3fe1f3ae3cd8e6a307b5
    96ca46e6e3bb0095c2af21f4c2a63cfafa9a23fcf78d2af496c036f8ff8c5395
    dbea37fbfea927f496ca538cf7bf22c6f99e36e7ff9e3ffaffb815e5c5ae3df0
    f5b832f096ca5efdffb81595b6ae3df0e4ab36c696ce3bf6e386539596d832f6
    e3865395ffea32f6febe73e6d38873f0b7eb07c696ca5381f7af21d6fa8e36e1
    fbab14f2e6b307f0faa612f096ca53968ca63fd4d5ca5395e2ab36e7f1a617f0
    f3a732d2f3ba2ac1d4a923dbfabe27f496ca54f0f8a51e95e4af27e696ca5381
    f8a432c2f7af1ff4f78636e3e4af37f1fba53cc796ca53c0cc8b01d6d59812cc
    b68f17d4e5a821fafaa530b5e2a936f9ffea37f0febe73fbe4ea20fcb6a73cfa
    faa63ae2b6af31b5e2b93cf9e48b73bbf9b373f0e3b973e0efea36e7e1ea26fa
    b6be3df4faea3ce1f3bc32f096ca5eaaf7a21095faaf3dfbf5b936d196da63a0
    f39e5395efea27e6b6b826fafaa338e696c772f9f78d5395e4af3be1f78862a4
    bfb93ff9d5ca5395f3a63ffaf7ea27f6d5ea3ff9cf9012c7d78901d4f9ea16d1
    b6b931e7b6b83cf3f9a873f4b7b926fb96ca5386e1a53bc6e2a332c2fba53cc7
    faa53cc182ba3ac1d9ca5395b6b33ffbe1a53be6f3ba3cb5f9b873fb84b93efa
    c5ca5395d7bd3cfdf9983ff9f99e3efaff9e3ffa96ca5de5f9a20095faab73e2
    f9b873f98fb93efad5ca5395e2ab36e7f7b803f0f5a327f6f9a501f0f9a507f8
    e6a307f996ca5381f5ab21c5f3a93ae1e3a52ab5f9a773e7b7b936e396ca5384
    e2ab3bd6f9a434dcf99e36e7ff9e3ffa96ca58e5f8ad1a95b6af21fae2b93ad9
    96ca5399e3a53bc6f9a507e1e6a307f996ca539cf7ad36d8f8a53be596ca46f0
    f3b90695fcaf01e7dcbe30f0c5a43afaffb920f096d03dfaf9935395f7a973e0
    e2a53dfbffa539b5febe73fbe4ea20fcb8a73cfa96ca539bdb8400c6f7a720fc
    f3a230e1968861f1f9935395ffea21e0f8af25fbefb83ce1b6b93ab5faa626f3
    fa9a73bbf3b932f0faaf37b5b6af27f0fbaf27fcf8a373e6f2b83cb5e2ea21f0
    e3a873faf9a773ecb6e436e796ca5386f7bf22c6e58736e7e5a317f2f8a43cf6
    bfbe30f0d2ca5395f9a920fcf5af3dfbb6ae36e1fba521f3e1a507b5c6ea7dfb
    e5ab36f9e4be73f0f1ab73ecb8a43af496ca539ef4a83cd9e2bf11ec99a43ce1
    c4ca5395e4bf27f0f99e73fbf4a51fb596c12af7f9895395f5af3dfbc2ae36e1
    96ca58fafaa41c95b6af3dfce5ef73b896ca5399f8a43cd6f3be30f0a4a507f1
    96ca5385ffa63ddabbea36fbb6b976b5cbb976ce96ca539ef8a33cdfe5b936c6
    99a43cfcb3ca5395f7a273e6f9a073e6f2af3dfc96c659bbf3865395c5af25f4
    ffb920f096c73dfae5ef5395e5ab3bb5f0af3fb587c07de1dbca5395f3be20f4
    ffa519e7e5af00fbf8a53ae696ca53b4f2ab1fceb6b836f1f2ab21d2f2ef73f0
    b6b976b5b6b932fdf8a33cffcbe437f096ca419ff8ab1095dcbe3cfbd9a43afa
    f0b836e3a5bd3cf9c2ca5395b6b93afdf8ab3bf6b6a636fbf0ea20fcb8a63fe0
    f3a603b5b6af20f4f8a33cfff9a432b5e4af3be1f7a230b5faaf3dfb8547
""".toBA()
