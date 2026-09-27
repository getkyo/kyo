package kyo.internal.crypto

import java.nio.charset.StandardCharsets
import kyo.*

class Sha512Test extends kyo.test.Test[Any]:

    private val cavp = "nist-cavp-shs"

    private def hex(bytes: Array[Byte]): String =
        bytes.map(b => f"${b & 0xff}%02x").mkString

    private def bytes(hex: String): Array[Byte] =
        Array.tabulate(hex.length / 2)(i => Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte)

    private def generated(size: Int): Array[Byte] =
        Array.tabulate(size)(index => ((index * 31 + size * 17) & 0xff).toByte)

    private def fields(text: String): Seq[(String, String)] =
        text.linesIterator
            .map(_.trim)
            .filter(line => line.contains(" = ") && !line.startsWith("#") && !line.startsWith("["))
            .map { line =>
                val at = line.indexOf(" = ")
                line.substring(0, at) -> line.substring(at + 3)
            }
            .toSeq

    final private case class MessageVector(bits: Int, message: Array[Byte], digest: String)

    private def messageVectors(file: String): Seq[MessageVector] =
        fields(TestVectors.text(cavp, file)).grouped(3).map {
            case Seq(("Len", len), ("Msg", msg), ("MD", md)) =>
                MessageVector(len.toInt, bytes(msg).take(len.toInt / 8), md)
            case other => throw new IllegalStateException(s"unexpected CAVP record in $file: $other")
        }.toSeq

    "embedded NIST CAVP files match their MANIFEST digests" in {
        assert(TestVectors.names(cavp) == Seq("SHA512ShortMsg.rsp", "SHA512LongMsg.rsp", "SHA512Monte.rsp"))
        TestVectors.names(cavp).foreach { name =>
            val embedded = TestVectors.text(cavp, name).getBytes(StandardCharsets.UTF_8)
            assert(hex(Sha256.hash(embedded)) == TestVectors.sha256(cavp, name))
        }
    }

    "NIST CAVP" - {
        "SHA512ShortMsg: every message from 0 to 128 bytes" in {
            val vectors = messageVectors("SHA512ShortMsg.rsp")
            assert(vectors.map(_.bits) == (0 to 1024 by 8))
            vectors.foreach { v =>
                assert(v.message.length * 8 == v.bits)
                assert(hex(Sha512.hash(v.message)) == v.digest)
            }
        }

        "SHA512LongMsg" in {
            val vectors = messageVectors("SHA512LongMsg.rsp")
            assert(vectors.size == 128)
            vectors.foreach { v =>
                assert(v.message.length * 8 == v.bits)
                assert(hex(Sha512.hash(v.message)) == v.digest)
            }
        }

        "SHA512Monte: 100 checkpoints of 1000 chained digests" in {
            val records  = fields(TestVectors.text(cavp, "SHA512Monte.rsp"))
            val seed     = records.collectFirst { case ("Seed", value) => value }.getOrElse(fail("no Seed in SHA512Monte.rsp"))
            val expected = records.collect { case ("MD", value) => value }
            assert(expected.size == 100)
            val checkpoints = expected.indices.scanLeft(bytes(seed)) { (previous, _) =>
                val last = (3 to 1002).foldLeft((previous, previous, previous)) { case ((md0, md1, md2), _) =>
                    (md1, md2, Sha512.hashChunks(Seq(md0, md1, md2)))
                }
                last._3
            }.tail
            assert(checkpoints.map(hex) == expected)
        }
    }

    "padding boundaries of the 1024-bit block" - {
        "digests of generated inputs, 111 bytes fitting one block and 112 needing two" in {
            val vectors = Seq(
                0 ->
                    "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
                1 ->
                    "4dab249e3ef1f27d323fea6d443dd6f2ab3655e2b4faf1078c7ad6af83a18f2efbbed88b2a63b1afff3d196f8b9707f28cfa8cc08579db5428d2a5dc1c5c9d63",
                111 ->
                    "ce0aa2dd9039fd51617a8220534cd18afaf2d804063719d61ad5fb1affc78d8b4dd20af582bcd3c3091587baf9e216895674639ee04c9991c8ec9af9c3241cd7",
                112 ->
                    "d2a41957e85e8dca4f642ffa10ceced900ad64035184c23df105c7c89509c4fc853800a3c5951ab849ec075d116c782d561a24264de7395d0b2efb5e28d2b562",
                113 ->
                    "1b8625d27330c27a7e55a0f8a18e90032584090380f9bfe4e574d81cec01a24e8cab3406f938e6a7d4bda425e665437a327018034055f9bb499abf3c034a2cbe",
                127 ->
                    "beaf1b4c8b2c4e2aef144b648a0fc68ac41041d51b7b7fc13a564beeac9b5b23eea278ba23917956590482336ae02d18474d782753db8300cc6ddd51c866a729",
                128 ->
                    "19a5ca60c1b81d070e81980f48f2b856f0fa8bf1e85b79e4796c68cafcc92b5a7b1da3d7a6c96e3ee831b32d9655df5ce14a16822e72aaf7555c88c1b2b49b9d",
                129 ->
                    "5730727433a9a2eb01a329f67e604f7b3e1cdea1b0c672ad398e1acbe81c26e13a9d0f5e45abf46958d3dbb36a00e3d5de7b9e7fdea5308a376f39d65c79048b",
                239 ->
                    "908664c0cb2b9aa48374e2f2126fd14f1b6569b3930f59a18e54361e7a27099adae9af48859b9cbd889ca831d2c3159d000629924e99cbbddb0b6e1b4cdb63c8",
                240 ->
                    "f363fe35f0f5241b78368e99e9f9981855e0b8260a1f6386ca385dcc26b44bba9d63a013b7285e772449f593887b0bb62e1080d842ae85159788273aec43fa57",
                255 ->
                    "c4fb47a52633ffb33316b250e46d1b0bd1b4cc9d4e1d2e38cf8190ba90c59ec9b64e0a453528c725658f2d9b947f09af83bf5d8939cebdd93504cd5799e48574",
                256 ->
                    "59aea8bd5b3b2cf76a24a4fb598eb57778b617c51d4fd749663126fcb7acd2a427981d1b6f54411d07e9db1b74c8d1bf69a6989ca4d56cfdf887708f9d4f103e",
                257 ->
                    "eda27f3eebaece18fb4f5678d85630bb1585178fa6e11360abc428e1bc81b0cf9a4b5836f2d361fb9488991105ce5a832e8dc16cc86c6e58ea917e0f944d0978"
            )
            vectors.foreach { case (size, expected) =>
                assert(hex(Sha512.hash(generated(size))) == expected)
            }
        }

        "padding size switches to a second block at 112 bytes" in {
            assert(Sha512.paddingSize(0) == 128)
            assert(Sha512.paddingSize(1) == 127)
            assert(Sha512.paddingSize(111) == 17)
            assert(Sha512.paddingSize(112) == 144)
            assert(Sha512.paddingSize(113) == 143)
            assert(Sha512.paddingSize(127) == 129)
            assert(Sha512.paddingSize(128) == 128)
            assert(Sha512.paddingSize(239) == 17)
            assert(Sha512.paddingSize(240) == 144)
        }

        "the 128-bit length field carries the bits a 64-bit shift would drop" in {
            assert(Sha512.bitLengthHigh(0L) == 0L)
            assert(Sha512.bitLengthLow(1L) == 8L)
            assert(Sha512.bitLengthHigh((1L << 61) - 1) == 0L)
            assert(Sha512.bitLengthLow((1L << 61) - 1) == -8L)
            assert(Sha512.bitLengthHigh(1L << 61) == 1L)
            assert(Sha512.bitLengthLow(1L << 61) == 0L)
            assert(Sha512.bitLengthHigh(Long.MaxValue) == 3L)
            assert(Sha512.bitLengthLow(Long.MaxValue) == -8L)
            assert(Sha512.paddingSize(Long.MaxValue) == 129)
        }
    }

    "multi-chunk hashing" - {
        "equals one-shot hashing for every split into two chunks" in {
            Seq(130, 257).foreach { size =>
                val input    = generated(size)
                val expected = hex(Sha512.hash(input))
                (0 to size).foreach { i =>
                    assert(hex(Sha512.hashChunks(Seq(input.take(i), input.drop(i)))) == expected)
                }
            }
        }

        "equals one-shot hashing for every split into three chunks" in {
            val size       = 130
            val input      = generated(size)
            val expected   = hex(Sha512.hash(input))
            val mismatches =
                for
                    i <- 0 to size
                    j <- i to size
                    if hex(Sha512.hashChunks(Seq(input.take(i), input.slice(i, j), input.drop(j)))) != expected
                yield (i, j)
            assert(mismatches.isEmpty)
        }

        "empty chunks around a message change nothing" in {
            val input = generated(129)
            val empty = Array.emptyByteArray
            assert(hex(Sha512.hashChunks(Seq(empty, input, empty, empty))) == hex(Sha512.hash(input)))
            assert(hex(Sha512.hashChunks(Seq.empty)) == hex(Sha512.hash(empty)))
        }
    }

    "leaves the input arrays unchanged" in {
        val first    = generated(200)
        val second   = generated(57)
        val original = (first.clone(), second.clone())
        discard(Sha512.hash(first))
        discard(Sha512.hashChunks(Seq(first, second)))
        assert(first.sameElements(original._1))
        assert(second.sameElements(original._2))
    }

    "returns a 64-byte digest" in {
        assert(Sha512.hash(generated(10)).length == 64)
    }

end Sha512Test
