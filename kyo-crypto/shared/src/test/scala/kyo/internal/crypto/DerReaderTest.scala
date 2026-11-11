package kyo.internal.crypto

import kyo.*
import kyo.crypto.Rsa.DerFailure

class DerReaderTest extends kyo.test.Test[Any]:

    "readBytes" - {
        "reads the next bytes and moves past them" in {
            val reader = new DerReader(Array[Byte](0x06, 0x03, 0x2a, 0x86.toByte, 0x48, 0x02, 0x01, 0x05))
            assert(reader.readTag(0x06) == Result.unit)
            assert(reader.readLength() == Result.succeed(3))
            assert(reader.readBytes(3).map(_.toSeq) == Result.succeed(Seq[Byte](0x2a, 0x86.toByte, 0x48)))
            assert(reader.readInteger() == Result.succeed(BigInt(5)))
        }

        "reads 0 bytes as an empty array, at the end as elsewhere" in {
            val reader = new DerReader(Array[Byte](0x05))
            assert(reader.readBytes(0).map(_.length) == Result.succeed(0))
            assert(reader.skip(1) == Result.unit)
            assert(reader.readBytes(0).map(_.length) == Result.succeed(0))
        }

        "past the end is SkipPastEnd with the count and the offset, and reads nothing" in {
            val reader = new DerReader(Array[Byte](0x01, 0x02, 0x03))
            assert(reader.skip(1) == Result.unit)
            assert(reader.readBytes(3).map(_.toSeq) == Result.fail(DerFailure.SkipPastEnd(3, 1)))
            assert(reader.readBytes(2).map(_.toSeq) == Result.succeed(Seq[Byte](0x02, 0x03)))
        }

        "returns a copy, which a change to the input does not reach" in {
            val der    = Array[Byte](0x0a, 0x0b)
            val reader = new DerReader(der)
            val read   = reader.readBytes(2)
            der(0) = 0x7f
            assert(read.map(_.toSeq) == Result.succeed(Seq[Byte](0x0a, 0x0b)))
        }
    }

end DerReaderTest
