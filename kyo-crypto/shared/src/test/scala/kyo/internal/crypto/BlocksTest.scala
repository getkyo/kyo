package kyo.internal.crypto

class BlocksTest extends kyo.test.Test[Any]:

    "count" - {
        "is the ceiling of the division" in {
            assert(Blocks.count(0, 32) == 0)
            assert(Blocks.count(1, 32) == 1)
            assert(Blocks.count(31, 32) == 1)
            assert(Blocks.count(32, 32) == 1)
            assert(Blocks.count(33, 32) == 2)
            assert(Blocks.count(64, 32) == 2)
            assert(Blocks.count(20, 20) == 1)
            assert(Blocks.count(21, 20) == 2)
        }

        "does not overflow within the block size of Int.MaxValue" in {
            assert(Blocks.count(Int.MaxValue, 32) == 67108864)
            assert(Blocks.count(Int.MaxValue - 30, 32) == 67108864)
            assert(Blocks.count(Int.MaxValue - 31, 32) == 67108863)
            assert(Blocks.count(Int.MaxValue, 20) == 107374183)
            assert(Blocks.count(Int.MaxValue - 6, 20) == 107374183)
            assert(Blocks.count(Int.MaxValue - 7, 20) == 107374182)
        }
    }

    "writers" - {
        "write the bytes in the named order" in {
            val big = new Array[Byte](12)
            Blocks.writeIntBigEndian(big, 0, 0x01020304)
            Blocks.writeLongBigEndian(big, 4, 0x1112131415161718L)
            assert(big.toSeq == Seq[Byte](1, 2, 3, 4, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18))
            val little = new Array[Byte](12)
            Blocks.writeIntLittleEndian(little, 0, 0x01020304)
            Blocks.writeLongLittleEndian(little, 4, 0x1112131415161718L)
            assert(little.toSeq == Seq[Byte](4, 3, 2, 1, 0x18, 0x17, 0x16, 0x15, 0x14, 0x13, 0x12, 0x11))
            val signed = new Array[Byte](8)
            Blocks.writeIntBigEndian(signed, 0, 0xfffefdfc)
            Blocks.writeIntLittleEndian(signed, 4, 0xfffefdfc)
            assert(signed.toSeq == Seq[Byte](-1, -2, -3, -4, -4, -3, -2, -1))
        }
    }

    "Buffer64" - {
        def blocksOf(inputs: Seq[Array[Byte]], lengthWriter: (Array[Byte], Int, Long) => Unit): Seq[Seq[Byte]] =
            val seen   = Seq.newBuilder[Seq[Byte]]
            val buffer = new Blocks.Buffer64((input, offset) => seen += input.slice(offset, offset + 64).toSeq)
            inputs.foreach(buffer.update)
            buffer.finish(lengthWriter)
            seen.result()
        end blocksOf

        val data = Array.tabulate[Byte](200)(i => (i * 7 + 3).toByte)

        "feeds whole 64-byte blocks whatever the split, then the padded tail" in {
            val whole = blocksOf(Seq(data), Blocks.writeLongBigEndian)
            Seq(
                Seq(data.take(63), data.slice(63, 64), data.drop(64)),
                Seq(data.take(64), data.drop(64)),
                Seq(data.take(65), Array.emptyByteArray, data.drop(65)),
                Seq(data.take(1), data.slice(1, 129), data.drop(129)),
                data.grouped(1).toSeq
            ).foreach { split =>
                assert(blocksOf(split, Blocks.writeLongBigEndian) == whole)
            }
            assert(whole.size == 4)
            assert(whole.take(3).flatten == data.take(192).toSeq)
            val tail = whole(3)
            assert(tail.take(8) == data.drop(192).toSeq)
            assert(tail(8) == 0x80.toByte)
            assert(tail.slice(9, 56).forall(_ == 0))
            assert(tail.drop(56) == Seq[Byte](0, 0, 0, 0, 0, 0, 6, 64))
        }

        "pads into a second block when the 0x80 leaves fewer than 8 bytes, at 56 bytes but not at 55" in {
            val fits = blocksOf(Seq(data.take(55)), Blocks.writeLongLittleEndian)
            assert(fits.size == 1)
            assert(fits.head(55) == 0x80.toByte)
            assert(fits.head.drop(56) == Seq[Byte]((55 * 8).toByte, 1, 0, 0, 0, 0, 0, 0))
            val spills = blocksOf(Seq(data.take(56)), Blocks.writeLongLittleEndian)
            assert(spills.size == 2)
            assert(spills.head.take(56) == data.take(56).toSeq)
            assert(spills.head(56) == 0x80.toByte)
            assert(spills.head.drop(57).forall(_ == 0))
            assert(spills(1).take(56).forall(_ == 0))
            assert(spills(1).drop(56) == Seq[Byte]((56 * 8).toByte, 1, 0, 0, 0, 0, 0, 0))
            val empty = blocksOf(Seq.empty, Blocks.writeLongBigEndian)
            assert(empty == Seq(Seq[Byte](0x80.toByte) ++ Seq.fill[Byte](63)(0)))
        }

        "counts the bytes in a Long, so a total past the array limit pads with the right bit length" in {
            val part    = new Array[Byte](1048576)
            val buffer  = new Blocks.Buffer64((_, _) => ())
            var written = -1L
            (0 until 2049).foreach(_ => buffer.update(part))
            buffer.finish((_, _, bits) => written = bits)
            assert(written == 2049L * 1048576L * 8L)
            assert(written > Int.MaxValue.toLong * 8L)
        }

        "the length field at 2^29 bytes, where a 32-bit bit count wraps, and at 2^61 - 1 bytes, the largest a 64-bit field holds" in {
            // Arithmetic stand-ins for what finish writes (byteLength << 3), since no test feeds 2^61 bytes.
            def field(byteLength: Long, writer: (Array[Byte], Int, Long) => Unit): Seq[Byte] =
                val block = new Array[Byte](64)
                writer(block, 56, byteLength << 3)
                block.drop(56).toSeq
            end field
            assert(field(1L << 29, Blocks.writeLongBigEndian) == Seq[Byte](0, 0, 0, 1, 0, 0, 0, 0))
            assert(field((1L << 32) - 1, Blocks.writeLongBigEndian) == Seq[Byte](0, 0, 0, 7, -1, -1, -1, -8))
            assert(field((1L << 61) - 1, Blocks.writeLongBigEndian) == Seq[Byte](-1, -1, -1, -1, -1, -1, -1, -8))
            assert(field((1L << 61) - 1, Blocks.writeLongLittleEndian) == Seq[Byte](-8, -1, -1, -1, -1, -1, -1, -1))
            assert(field(1L << 29, Blocks.writeLongLittleEndian) == Seq[Byte](0, 0, 0, 0, 1, 0, 0, 0))
        }
    }

end BlocksTest
