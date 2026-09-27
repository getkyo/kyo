package kyo.internal.crypto

class BytesTest extends kyo.test.Test[Any]:

    "xor" - {
        "combines equal-length operands element-wise" in {
            val a = Array[Byte](0x00, 0xff.toByte, 0x0f, 0xf0.toByte, 0x55)
            val b = Array[Byte](0xff.toByte, 0xff.toByte, 0xf0.toByte, 0xf0.toByte, 0xaa.toByte)
            assert(Bytes.xor(a, b).toSeq == Seq[Byte](0xff.toByte, 0x00, 0xff.toByte, 0x00, 0xff.toByte))
        }

        "reads only the first operand's length of a longer second operand" in {
            val a = Array[Byte](0x01, 0x02)
            val b = Array[Byte](0x10, 0x20, 0x30, 0x40)
            assert(Bytes.xor(a, b).toSeq == Seq[Byte](0x11, 0x22))
        }

        "of empty operands is empty" in {
            assert(Bytes.xor(Array.emptyByteArray, Array[Byte](1, 2)).isEmpty)
        }

        "is its own inverse and leaves both operands unchanged" in {
            val a        = Array.tabulate[Byte](32)(i => (i * 7).toByte)
            val b        = Array.tabulate[Byte](32)(i => (255 - i * 3).toByte)
            val (ac, bc) = (a.clone(), b.clone())
            assert(Bytes.xor(Bytes.xor(a, b), b).sameElements(a))
            assert(a.sameElements(ac) && b.sameElements(bc))
        }
    }

end BytesTest
