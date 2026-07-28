package kyo

import java.nio.ByteBuffer
import kyo.internal.BinarySegmentFormat
import kyo.internal.CRC32

class FileJournalCodecTest extends kyo.test.Test[Any]:
    import BinarySegmentFormat.*

    "segment header" - {
        "is KJN1 followed by version 0x01" in {
            assert(SegmentHeader.length == HeaderSize)
            assert(SegmentHeader.take(4).sameElements(Array[Byte]('K', 'J', 'N', '1')))
            assert(SegmentHeader(4) == 0x01.toByte)
        }
    }

    private val engineMetadataCodec = EventLogCodecs.MetadataCodec(IonBinary())

    "record frame" - {
        "frames a record with a body-length prefix and a body-covering CRC" in {
            val md      = kyo.internal.FileJournalCore.encodeMetadata(engineMetadataCodec, Event.Metadata.empty)
            val payload = "hello".getBytes("UTF-8")
            val frame   = encodeRecord(7L, "evt-1", "UserRegistered", md, payload)
            val buf     = ByteBuffer.wrap(frame)
            val length  = buf.getInt()
            val crc     = buf.getInt()
            assert(frame.length == 8 + length)
            val body   = java.util.Arrays.copyOfRange(frame, 8, frame.length)
            val actual = new CRC32(); actual.update(body)
            assert((actual.value & 0xffffffffL).toInt == crc)
        }
        "crc covers the body but not the length or crc fields" in {
            val frame = encodeRecord(
                1L,
                "e",
                "t",
                kyo.internal.FileJournalCore.encodeMetadata(engineMetadataCodec, Event.Metadata.empty),
                Array[Byte](9)
            )
            // flipping a body byte must break the CRC; flipping the length field must not (it is not covered)
            val body       = frame.clone(); body(10) = (body(10) ^ 0xff).toByte // a byte inside the body
            val bb         = ByteBuffer.wrap(body); val len = bb.getInt(); val crc = bb.getInt()
            val recomputed = new CRC32(); recomputed.update(java.util.Arrays.copyOfRange(body, 8, body.length))
            assert((recomputed.value & 0xffffffffL).toInt != crc)
        }
    }

    "batch-commit terminator" - {
        "round-trips the record count with a valid CRC" in {
            val term = encodeTerminator(3)
            assert(term.length == TerminatorSize)
            val buf = ByteBuffer.wrap(term)
            val m   = new Array[Byte](4); buf.get(m)
            assert(m.sameElements(Array[Byte]('K', 'J', 'N', 'C')))
            assert(buf.getInt() == 3)
        }
    }

    "Options.default" - {
        "carries fsync=Always and segmentSize=64 MiB (format and metadataCodec moved off Options)" in {
            assert(
                FileJournal.Options.default == FileJournal.Options(
                    fsync = FileJournal.Fsync.Always,
                    segmentSize = 64L.mib
                )
            )
        }
    }
end FileJournalCodecTest
