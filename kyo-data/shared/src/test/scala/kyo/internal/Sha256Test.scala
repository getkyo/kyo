package kyo.internal

import java.nio.charset.StandardCharsets

class Sha256Test extends kyo.test.Test[Any]:

    private def utf8(value: String): Array[Byte] =
        value.getBytes(StandardCharsets.UTF_8)

    private def hex(bytes: Array[Byte]): String =
        val result = new StringBuilder(bytes.length * 2)
        bytes.foreach { byte =>
            result.append(f"${byte & 0xff}%02x")
        }
        result.result()
    end hex

    private def generated(size: Int): Array[Byte] =
        Array.tabulate(size)(index => ((index * 31 + size * 17) & 0xff).toByte)

    "hashing" - {
        "matches the published empty-input digest" in {
            assert(hex(Sha256.hash(Array.emptyByteArray)) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        }

        "matches the published short-input digest" in {
            assert(hex(Sha256.hash(utf8("abc"))) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        }

        "matches the published padding-boundary digest" in {
            val input = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
            assert(input.length == 56)
            assert(hex(Sha256.hash(utf8(input))) == "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
        }

        "matches the published multi-block digest" in {
            val input =
                "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno" +
                    "ijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"
            assert(input.length == 112)
            assert(hex(Sha256.hash(utf8(input))) == "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1")
        }

        "matches exact digests for generated boundary inputs" in {
            val vectors = Seq(
                1   -> "4a64a107f0cb32536e5bce6c98c393db21cca7f4ea187ba8c4dca8b51d4ea80a",
                55  -> "7ce4e0e40de7324a02e7cf0cd700dd5022e0f0013aa1851001b6a4e911372e2e",
                56  -> "c4d6a4c722c6410c3f618c5d22e2624dfef61c1d5ae0f8f9685dd96183aa382e",
                63  -> "55cb1d1ce5c16add76433b2057e0af5802b2b00f6459e2919942792db7ab76eb",
                64  -> "3ea97ec766b8247739939247b4d4cb362cf13c100deb0cc2ba5391f762023852",
                65  -> "29f9271ca7d028ab5612ea14e8bcd7057a4df39f167fe9fd999360df4295afc9",
                127 -> "50e2381c1f398559773e06a3685821477dff4baee91356bdc457c664f9f98c6a",
                128 -> "3b35116c160c0ffdaf1287960af39caf2760811b02a36e3bd5294bdd61eea9a9",
                129 -> "ef7a8d95e4cc02c7b88886d1114ce8fecb1fd2a2a45c076edb8b060013723fac"
            )
            vectors.foreach { case (size, expected) =>
                assert(hex(Sha256.hash(generated(size))) == expected)
            }
        }

        "matches one-shot hashing when input is split across chunks" in {
            val input  = generated(257)
            val chunks = Seq(input.take(55), input.slice(55, 64), Array.emptyByteArray, input.slice(64, 129), input.drop(129))
            assert(Sha256.hashChunks(chunks).sameElements(Sha256.hash(input)))
        }

        "computes padding from logical lengths beyond the array limit" in {
            assert(Sha256.paddingSize(Int.MaxValue.toLong) == 65)
            assert(Sha256.paddingSize(Int.MaxValue.toLong + 16) == 49)
            assert(Sha256.bitLength(Int.MaxValue.toLong + 45) == 17179869536L)
        }

        "returns the same digest for the same generated input" in {
            val input = generated(257)
            assert(Sha256.hash(input).sameElements(Sha256.hash(input)))
        }

        "leaves generated input bytes unchanged" in {
            val input    = generated(257)
            val original = input.clone()
            Sha256.hash(input)
            assert(input.sameElements(original))
        }
    }
end Sha256Test
