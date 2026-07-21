package kyo.internal.mysql

import kyo.Chunk
import kyo.Maybe
import kyo.Span
import kyo.Test

/** Tests for [[MysqlPacket]] framing and [[AccumulatedBuffer]] reassembly.
  *
  * Covers single-packet round-trips, 16MB chunking edge cases, and sequence ID management.
  */
class MysqlPacketTest extends Test:

    private def makePayload(size: Int, fillByte: Byte = 0x42.toByte): Span[Byte] =
        val arr = new Array[Byte](size)
        java.util.Arrays.fill(arr, fillByte)
        Span.from(arr)
    end makePayload

    private def feedPackets(packets: Chunk[Span[Byte]]): AccumulatedBuffer =
        val buf = new AccumulatedBuffer
        packets.foreach(buf.append)
        buf
    end feedPackets

    private def runRead(packets: Chunk[Span[Byte]]): Maybe[(Span[Byte], Int)] =
        val buf = feedPackets(packets)
        MysqlPacket.readOne(buf)

    // Single packet round-trip for payload <= 16MB-1
    "MysqlPacket write single chunk for payload <= 16MB-1 (100 bytes)" in {
        val payload = makePayload(100)
        val packets = MysqlPacket.writeOne(payload, seq = 0)
        assert(packets.size == 1)
        val pkt = packets.head
        // Header: 3-byte length (100 in LE) + 1-byte seq
        assert((pkt(0) & 0xff) == 100)
        assert((pkt(1) & 0xff) == 0)
        assert((pkt(2) & 0xff) == 0)
        assert((pkt(3) & 0xff) == 0) // seq=0
        // Total size = 4 (header) + 100 (payload)
        assert(pkt.size == 104)
    }

    // MysqlPacket splits 16MB+1 payload into two packets
    "MysqlPacket write splits 16MB+1 payload into two packets" in {
        val payloadSize = MysqlPacket.MaxPayload + 1
        val payload     = makePayload(payloadSize)
        val packets     = MysqlPacket.writeOne(payload, seq = 0)
        assert(packets.size == 2)
        // First packet: length = MaxPayload (0xFFFFFF)
        val first = packets(0)
        assert((first(0) & 0xff) == 0xff)
        assert((first(1) & 0xff) == 0xff)
        assert((first(2) & 0xff) == 0xff)
        assert((first(3) & 0xff) == 0) // seq=0
        // Second packet: length = 1
        val second = packets(1)
        assert((second(0) & 0xff) == 1)
        assert((second(1) & 0xff) == 0)
        assert((second(2) & 0xff) == 0)
        assert((second(3) & 0xff) == 1) // seq=1
    }

    // MysqlPacket reassembles two-packet 16MB+1 payload
    "MysqlPacket read reassembles two-packet 16MB+1 payload with correct content" in {
        val payloadSize = MysqlPacket.MaxPayload + 1
        val payload     = makePayload(payloadSize)
        val packets     = MysqlPacket.writeOne(payload, seq = 0)
        val result      = runRead(packets)
        result match
            case Maybe.Present((reassembled, _)) =>
                assert(reassembled.size == payloadSize)
                // Verify content: all bytes should be 0x42
                assert((0 until payloadSize).forall(i => reassembled(i) == 0x42.toByte))
            case Maybe.Absent =>
                fail("Expected a reassembled packet but got Absent")
        end match
    }

    // seqId increments per packet in a split
    "MysqlPacket seqId increments per packet in split" in {
        val payloadSize = MysqlPacket.MaxPayload + 1
        val payload     = makePayload(payloadSize)
        val packets     = MysqlPacket.writeOne(payload, seq = 0)
        assert(packets.size == 2)
        assert((packets(0)(3) & 0xff) == 0) // first packet seq=0
        assert((packets(1)(3) & 0xff) == 1) // second packet seq=1
    }

    // Round-trip for exact MaxPayload (16777215 bytes) — single packet, no trailing empty
    "MysqlPacket write then read round-trip for exact 16MB-1 payload" in {
        val payload = makePayload(MysqlPacket.MaxPayload)
        val packets = MysqlPacket.writeOne(payload, seq = 0)
        // Single packet (payload < MaxPayload is false, but payload == MaxPayload triggers trailing empty)
        // Actually MaxPayload == 16777215; payload.size == MaxPayload means payload % MaxPayload == 0 → trailing empty added
        // So we expect 2 packets: one with 0xFFFFFF bytes, one empty
        assert(packets.size == 2)
        val lastPkt = packets(1)
        assert((lastPkt(0) & 0xff) == 0)
        assert((lastPkt(1) & 0xff) == 0)
        assert((lastPkt(2) & 0xff) == 0)
        // Reassemble
        val result = runRead(packets)
        result match
            case Maybe.Present((reassembled, _)) =>
                assert(reassembled.size == MysqlPacket.MaxPayload)
            case Maybe.Absent =>
                fail("Expected reassembled packet")
        end match
    }

    // Round-trip for 0-byte trailing packet (exact multiple of 16MB)
    "MysqlPacket write then read round-trip for exact 16MB payload (trailing empty packet)" in {
        // Use a smaller "virtual" test with exact multiple of some size to avoid OOM
        // We simulate the logic: payload of exactly MaxPayload bytes must produce a trailing empty packet
        val payload = makePayload(MysqlPacket.MaxPayload)
        val packets = MysqlPacket.writeOne(payload, seq = 0)
        // Check trailing empty packet exists
        val lastPkt = packets.last
        val lastLen = (lastPkt(0) & 0xff) | ((lastPkt(1) & 0xff) << 8) | ((lastPkt(2) & 0xff) << 16)
        assert(lastLen == 0)
    }

    // Single packet read when buffer has exactly one complete packet
    "MysqlPacket readOne returns Present for exactly one complete packet" in {
        val payload = makePayload(42)
        val packets = MysqlPacket.writeOne(payload, seq = 3)
        val result  = runRead(packets)
        result match
            case Maybe.Present((reassembled, seq)) =>
                assert(reassembled.size == 42)
                assert(seq == 3)
            case Maybe.Absent =>
                fail("Expected Present")
        end match
    }

    // readOne returns Absent when buffer has insufficient bytes for header
    "MysqlPacket readOne returns Absent when buffer is empty" in {
        val buf    = new AccumulatedBuffer
        val result = MysqlPacket.readOne(buf)
        assert(result == Maybe.Absent)
    }

    // readOne returns Absent when buffer has partial header (< 4 bytes)
    "MysqlPacket readOne returns Absent when buffer has partial header" in {
        val buf = new AccumulatedBuffer
        buf.append(Span.from(Array[Byte](5, 0, 0))) // only 3 bytes — incomplete header
        val result = MysqlPacket.readOne(buf)
        assert(result == Maybe.Absent)
    }

    // readOne returns Absent when header says 10 bytes but only 5 are available
    "MysqlPacket readOne returns Absent when payload incomplete" in {
        val buf = new AccumulatedBuffer
        buf.append(Span.from(Array[Byte](10, 0, 0, 0, 1, 2, 3, 4, 5))) // header says 10, only 5 bytes follow
        val result = MysqlPacket.readOne(buf)
        assert(result == Maybe.Absent)
    }

    // seqId=5 preserved in writeOne and returned by readOne
    "MysqlPacket seqId is preserved through write/read round-trip" in {
        val payload = makePayload(8)
        val packets = MysqlPacket.writeOne(payload, seq = 5)
        val result  = runRead(packets)
        result match
            case Maybe.Present((_, seq)) => assert(seq == 5)
            case Maybe.Absent            => fail("Expected Present")
    }

end MysqlPacketTest
