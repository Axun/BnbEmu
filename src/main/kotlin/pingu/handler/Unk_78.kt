package pingu.handler

import pingu.netty.PKTHandler
import pingu.packet.SessionInfoResult
import pingu.packet.SessionList
import pingu.packet.Unk254
import pingu.packet.Unk227
import pingu.packet.Unk293
import pingu.packet.Unk302
import pingu.packet.Unk310
import pingu.packet.Unk60
import pingu.packet.Unk78Result
import pingu.packet.UserSimpleInfoResult

// 進頻道批次 (297/302/310/64/293/78) 的最後一包, 空載荷 —— 真實伺服器在此時點
// 按線序 (enter-channel.pcap seq=64~97) 回整套回應, 這是頻道在線用戶列表 (op 72 SessionList)
// 顯示的資料來源; 此前批次各包只消費不回應 (見 Unk_302 註釋)
// server = C???::On??? (原始名稱未知, 綁定見 EN_0_recv.yaml Unk_78)
val Unk_78 = PKTHandler { c ->
    c.send(
        Unk310(), Unk302(),
        Unk254(),
        SessionList(),
        Unk293(), Unk78Result(),
        UserSimpleInfoResult(), SessionInfoResult(),
        Unk227(), Unk60(),
        Unk310(), Unk302(),
    )
}
