package kyo.crypto

import java.nio.charset.StandardCharsets
import kyo.*

/** [[RsaOaep.encryptSha1]]: the ciphertext's shape, its pinned value under a pinned seed, the encoding's structure, and the two failures.
  *
  * The pinned values were computed in Java from the same seed:
  * {{{
  *   java.util.Random(42L).nextBytes(20) => 359d41baf78afe0de1bbe7ae28c0450ce43c084f
  *   OAEP-encode("hello", k=256, seed=above) first 4 bytes of EM => 005ce39c
  *   RSA-OAEP-encrypt("hello", seed=above) => the 256 bytes of pinnedCiphertext, as the JDK's OAEP produces them from the same seed
  * }}}
  */
class RsaOaepTest extends kyo.test.Test[Any]:

    import RsaOaepTest.*

    private val key: Rsa.EncryptionKey = RsaSpkiTest.keyOf(Rsa.encryptionKeyFromPem(RsaSpkiTest.testPubPem))

    "every ciphertext the JDK's OAEP produced in jdk-differential under a pinned seed is this module's, byte for byte" in {
        val jdk   = kyo.internal.crypto.TestVectorsJdk
        val cases = jdk.of("oaep")
        assert(cases.size == 6)
        val rsa = jdk.rsaKey
        val key = RsaSpkiTest.keyOf(Rsa.encryptionKeyFromPem(RsaSpkiTest.keyPemOf(rsa.big("n"), rsa.big("e"))))
        cases.foreach { c =>
            val ours = ciphertextOf(RsaOaep.encryptSha1(key, Span.from(c.bytes("msg")), Span.from(c.bytes("seed"))))
            assert(ours.toArray.map(b => f"${b & 0xff}%02x").mkString == c.value("out"), s"${c.bytes("msg").length} bytes")
        }
    }

    private val hello: Span[Byte] = Span.from("hello".getBytes(StandardCharsets.UTF_8))

    "the seed java.util.Random(42) draws is the pinned one" in {
        assert(Hex.encode(seed42) == "359d41baf78afe0de1bbe7ae28c0450ce43c084f")
    }

    "the ciphertext of a 2048-bit key is 256 bytes" in {
        assert(RsaOaep.encryptSha1(key, hello, seed42).map(_.size) == Result.succeed(256))
    }

    "the ciphertext under the pinned seed is the pinned 256 bytes" in {
        val ciphertext = ciphertextOf(RsaOaep.encryptSha1(key, hello, seed42))
        assert(ciphertext.size == 256)
        assert(Hex.encode(ciphertext) == pinnedCiphertext)
    }

    "the encoded message is 0x00, the masked seed and the masked data block, and its first bytes are the pinned ones" in {
        val encoded = RsaOaep.encode(hello.toArray, 256, seed42.toArray)
        assert(encoded.length == 256)
        assert(encoded(0) == 0)
        assert(Hex.encodeArray(encoded.take(4)) == "005ce39c")
        // Unmasking DB with MGF1(maskedSeed unmasked by MGF1(maskedDB)) recovers lHash || PS || 0x01 || M.
        val maskedSeed = encoded.slice(1, 21)
        val maskedDb   = encoded.drop(21)
        val seed       = xor(maskedSeed, Mgf1.sha1Array(maskedDb, 20).getOrElse(Array.emptyByteArray))
        assert(seed.sameElements(seed42.toArray))
        val db = xor(maskedDb, Mgf1.sha1Array(seed, 235).getOrElse(Array.emptyByteArray))
        assert(db.take(20).sameElements(Sha1.hashArray(Array.emptyByteArray)))
        assert(db.slice(20, 235 - 6).forall(_ == 0))
        assert(db(235 - 6) == 1)
        assert(db.drop(235 - 5).sameElements(hello.toArray))
    }

    "two seeds give two ciphertexts of the same plaintext" in {
        val a = ciphertextOf(RsaOaep.encryptSha1(key, hello, seed42))
        val b = ciphertextOf(RsaOaep.encryptSha1(key, hello, seedOf(7)))
        assert(!a.toArray.sameElements(b.toArray))
    }

    "the same seed gives the same ciphertext" in {
        val a = ciphertextOf(RsaOaep.encryptSha1(key, hello, seed42))
        val b = ciphertextOf(RsaOaep.encryptSha1(key, hello, seedOf(42)))
        assert(a.toArray.sameElements(b.toArray))
    }

    "the empty plaintext encrypts to 256 bytes" in {
        assert(RsaOaep.encryptSha1(key, Span.empty[Byte], seedOf(7)).map(_.size) == Result.succeed(256))
    }

    "the longest plaintext a 2048-bit key takes is 214 bytes, and 215 is PlaintextTooLong naming 214" in {
        val longest = Span.from(Array.fill[Byte](214)(0x42))
        assert(RsaOaep.encryptSha1(key, longest, seedOf(1)).map(_.size) == Result.succeed(256))
        val tooLong = Span.from(Array.fill[Byte](215)(0x42))
        assert(failureOf(RsaOaep.encryptSha1(key, tooLong, seedOf(1))) == Present(RsaOaep.Failure.PlaintextTooLong(214)))
        assert(failureOf(RsaOaep.encryptSha1(key, Span.from(Array.fill[Byte](1000)(0x42)), seedOf(1))) ==
            Present(RsaOaep.Failure.PlaintextTooLong(214)))
    }

    "a seed that is not 20 bytes is SeedLength(20)" in {
        assert(failureOf(RsaOaep.encryptSha1(key, hello, Span.empty[Byte])) == Present(RsaOaep.Failure.SeedLength(20)))
        assert(failureOf(RsaOaep.encryptSha1(key, hello, Span.from(seed42.toArray.take(19)))) == Present(RsaOaep.Failure.SeedLength(20)))
        assert(failureOf(RsaOaep.encryptSha1(key, hello, Span.from(seed42.toArray :+ 0.toByte))) == Present(RsaOaep.Failure.SeedLength(20)))
    }

    "the seed is checked before the plaintext length" in {
        val tooLong = Span.from(Array.fill[Byte](215)(0x42))
        assert(failureOf(RsaOaep.encryptSha1(key, tooLong, Span.empty[Byte])) == Present(RsaOaep.Failure.SeedLength(20)))
    }

    "a 1024-bit key with e = 3, the floor, encrypts to 128 bytes and takes at most 86" in {
        val small = RsaSpkiTest.keyOf(Rsa.encryptionKeyFromPem(RsaSpkiTest.syntheticKeyPem(1024, BigInt(3))))
        assert(RsaOaep.encryptSha1(small, hello, seedOf(5)).map(_.size) == Result.succeed(128))
        assert(failureOf(RsaOaep.encryptSha1(small, Span.from(Array.fill[Byte](87)(1)), seedOf(5))) ==
            Present(RsaOaep.Failure.PlaintextTooLong(86)))
    }

    "a key at both ceilings encrypts to 1024 bytes" in {
        val pem   = RsaSpkiTest.syntheticKeyPem(Rsa.MaxModulusBits, (BigInt(1) << (Rsa.MaxExponentBits - 1)) | BigInt(1))
        val large = RsaSpkiTest.keyOf(Rsa.encryptionKeyFromPem(pem))
        assert(RsaOaep.encryptSha1(large, hello, seedOf(3)).map(_.size) == Result.succeed(Rsa.MaxModulusBits / 8))
    }

    "leaves the plaintext and seed unchanged" in {
        val plaintext = hello.toArray
        val seed      = seed42.toArray
        discard(RsaOaep.encryptSha1(key, Span.from(plaintext), Span.from(seed)))
        assert(plaintext.sameElements(hello.toArray) && seed.sameElements(seed42.toArray))
    }

end RsaOaepTest

object RsaOaepTest:

    /** The first 20 bytes `java.util.Random(seed)` draws, the stand-in for a secure seed that lets a ciphertext be pinned. */
    def seedOf(seed: Long): Span[Byte] =
        val bytes = new Array[Byte](20)
        new java.util.Random(seed).nextBytes(bytes)
        Span.from(bytes)
    end seedOf

    val seed42: Span[Byte] = seedOf(42)

    /** The ciphertext of `hello` under the pinned 2048-bit key and [[seed42]], as the JDK's OAEP produces it from the same seed. */
    val pinnedCiphertext: String =
        "651390aa73e80e41925aac7e098055c30fcb6ded75b22f78a1f49e1c602a8540ffa2c6b5793b9f7737e6266cddbfd9d6691af1888678adc8effc84e9e8ecf157" +
            "14ca9386535ad59332b433ddabd9c53c4e600a563495d87c329b634c3dfe2617f6650c3c9bbfde9560a52b47250fae9810809452eaf41c5f8fe6d8da4c93e389" +
            "a6d43ff23d2b4ebcd346e903e467e047be1352fe79c6fb58bc0ad9c8968cf91c60046ede5f8022a68e25ceb541d785545cf485c44f5a3ae5b54834764de089cb" +
            "22d1b7e08cc4c233b12ed058ea1c556fb41f86481cf6bdf1daeae2526cca33605f46f858e0b9b170e4ed771317d2b9a9993a5e8aff5aaab0884d423d2210c0ac"

    def failureOf(result: Result[RsaOaep.Failure, Span[Byte]]): Maybe[RsaOaep.Failure] = result.failure

    def ciphertextOf(result: Result[RsaOaep.Failure, Span[Byte]]): Span[Byte] = result match
        case Result.Success(ciphertext) => ciphertext
        case other                      => throw new IllegalStateException(s"expected a ciphertext, got $other")

    def xor(a: Array[Byte], b: Array[Byte]): Array[Byte] =
        Array.tabulate(a.length)(i => (a(i) ^ b(i)).toByte)

end RsaOaepTest
