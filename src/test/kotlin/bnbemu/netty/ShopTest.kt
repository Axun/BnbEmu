package bnbemu.netty

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import bnbemu.packet.EnterShopResult
import bnbemu.packet.EnterShopStage

class ShopTest {

    @Test
    fun testShopOpcodeBindings() {
        val recv315 = OpcodeManager.recvOps[315]
        val recv317 = OpcodeManager.recvOps[317]
        val recv37 = OpcodeManager.recvOps[37]
        val recv258 = OpcodeManager.recvOps[258]
        val recv278 = OpcodeManager.recvOps[278]

        assertEquals("EnterShopReq", recv315)
        assertEquals("ShopSyncReq", recv317)
        assertEquals("ShopUserSyncReq", recv37)
        assertEquals("ShopPackageMapReq", recv258)
        assertEquals("ShopStringTableReq", recv278)

        assertNotNull(handlerArray[315], "Handler 315 must be registered")
        assertNotNull(handlerArray[317], "Handler 317 must be registered")
        assertNotNull(handlerArray[37], "Handler 37 must be registered")
        assertNotNull(handlerArray[258], "Handler 258 must be registered")
        assertNotNull(handlerArray[278], "Handler 278 must be registered")

        val enterShopResultOp = OpcodeManager.getSendOp(EnterShopResult()::class.java)
        val enterShopStageOp = OpcodeManager.getSendOp(EnterShopStage()::class.java)

        assertEquals(259, enterShopResultOp, "EnterShopResult send opcode must be 259")
        assertEquals(17, enterShopStageOp, "EnterShopStage send opcode must be 17")
    }

    @Test
    fun testEnterShopResultPacket() {
        val pktSuccess = EnterShopResult(true)
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        pktSuccess.encode(sendPkt)

        val inBuf = Unpooled.wrappedBuffer(buf.array(), 0, buf.writerIndex())
        val result = inBuf.Decode1
        assertEquals(1, result)

        val pktFail = EnterShopResult(false)
        val bufFail = Unpooled.buffer()
        val sendPktFail = SendPacketBase(bufFail, 3)
        pktFail.encode(sendPktFail)

        val inBufFail = Unpooled.wrappedBuffer(bufFail.array(), 0, bufFail.writerIndex())
        val resultFail = inBufFail.Decode1
        assertEquals(0, resultFail)
    }

    @Test
    fun testEnterShopStagePacket() {
        val pkt = EnterShopStage()
        val buf = Unpooled.buffer()
        val sendPkt = SendPacketBase(buf, 3)
        pkt.encode(sendPkt)

        val inBuf = Unpooled.wrappedBuffer(buf.array(), 0, buf.writerIndex())

        val mode = inBuf.Decode1
        val subMode = inBuf.Decode1
        val f3 = inBuf.Decode4
        val f4 = inBuf.Decode4
        val f5 = inBuf.Decode4
        val catalogRev = inBuf.Decode4
        val rev2 = inBuf.Decode4
        val rev3 = inBuf.Decode4
        val msg = inBuf.DecodeStr
        val bonusCount = inBuf.Decode4

        assertEquals(0, mode)
        assertEquals(0, subMode)
        assertEquals(0, f3)
        assertEquals(0, f4)
        assertEquals(0, f5)
        assertEquals(bnbemu.packet.ShopCatalog.CATALOG_HASH, catalogRev)
        assertEquals(0, rev2)
        assertEquals(0, rev3)
        assertEquals("", msg)
        assertEquals(0, bonusCount)
        assertEquals(0, inBuf.readableBytes())
    }

    @Test
    fun testShopExitStageStateAndChangeStagePacket() {
        // 商城退出时客户端发送: 5A (Encode1(0))
        val buf = Unpooled.wrappedBuffer(byteArrayOf(0x5A.toByte()))
        val stageState = buf.Decode1
        assertEquals(0, stageState, "Decoded stageState must be 0 for shop exit")
        assertEquals(0, buf.readableBytes())

        // 服务端应答 S->C op 97 ChangeStage(11)
        val pkt = bnbemu.packet.ChangeStage(11)
        val op = OpcodeManager.getSendOp(pkt::class.java)
        assertEquals(97, op, "ChangeStage send opcode must be 97")

        val outBuf = Unpooled.buffer()
        val sendPkt = SendPacketBase(outBuf, 3)
        pkt.encode(sendPkt)

        val inBuf = Unpooled.wrappedBuffer(outBuf.array(), 0, outBuf.writerIndex())
        val stageId = inBuf.Decode2
        assertEquals(11, stageId, "Stage ID must be 11 (Lobby stage)")
        assertEquals(0, inBuf.readableBytes())
    }
}
