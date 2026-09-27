package kyo.internal.crypto

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.Sha256

class HmacTest extends kyo.test.Test[Any]:

    private val rfc4231LongKey =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaa"

    private def bytes(hex: String): Array[Byte] =
        Array.tabulate(hex.length / 2)(i => Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte)

    private def hex(bytes: Array[Byte]): String =
        bytes.map(b => f"${b & 0xff}%02x").mkString

    private def utf8(value: String): Array[Byte] =
        value.getBytes(StandardCharsets.UTF_8)

    "RFC 4231" - {
        "test case 1" in {
            val key = bytes(
                "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b" +
                    "0b0b0b0b"
            )
            val data = bytes("4869205468657265")
            assert(key.length == 20)
            assert(hex(Hmac.sha256(key, data)) == "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7")
        }

        "test case 2: a key shorter than the output" in {
            val key  = bytes("4a656665")
            val data = bytes(
                "7768617420646f2079612077616e7420" +
                    "666f72206e6f7468696e673f"
            )
            assert(hex(Hmac.sha256(key, data)) == "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")
        }

        "test case 3: key and data longer than one block combined" in {
            val key = bytes(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                    "aaaaaaaa"
            )
            val data = bytes(
                "dddddddddddddddddddddddddddddddd" +
                    "dddddddddddddddddddddddddddddddd" +
                    "dddddddddddddddddddddddddddddddd" +
                    "dddd"
            )
            assert(key.length == 20)
            assert(data.length == 50)
            assert(hex(Hmac.sha256(key, data)) == "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe")
        }

        "test case 4: key and data longer than one block combined" in {
            val key = bytes(
                "0102030405060708090a0b0c0d0e0f10" +
                    "111213141516171819"
            )
            val data = bytes(
                "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd" +
                    "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd" +
                    "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd" +
                    "cdcd"
            )
            assert(key.length == 25)
            assert(data.length == 50)
            assert(hex(Hmac.sha256(key, data)) == "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b")
        }

        "test case 5: output truncated to 128 bits" in {
            val key = bytes(
                "0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c" +
                    "0c0c0c0c"
            )
            val data = bytes(
                "546573742057697468205472756e6361" +
                    "74696f6e"
            )
            assert(key.length == 20)
            assert(hex(Hmac.sha256(key, data).take(16)) == "a3b6167473100ee06e0c796c2955552b")
        }

        "test case 6: a key longer than the block" in {
            val key  = bytes(rfc4231LongKey)
            val data = bytes(
                "54657374205573696e67204c61726765" +
                    "72205468616e20426c6f636b2d53697a" +
                    "65204b6579202d2048617368204b6579" +
                    "204669727374"
            )
            assert(key.length == 131)
            assert(hex(Hmac.sha256(key, data)) == "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54")
        }

        "test case 7: a key and data longer than the block" in {
            val key  = bytes(rfc4231LongKey)
            val data = bytes(
                "54686973206973206120746573742075" +
                    "73696e672061206c6172676572207468" +
                    "616e20626c6f636b2d73697a65206b65" +
                    "7920616e642061206c61726765722074" +
                    "68616e20626c6f636b2d73697a652064" +
                    "6174612e20546865206b6579206e6565" +
                    "647320746f2062652068617368656420" +
                    "6265666f7265206265696e6720757365" +
                    "642062792074686520484d414320616c" +
                    "676f726974686d2e"
            )
            assert(key.length == 131)
            assert(hex(Hmac.sha256(key, data)) == "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2")
        }
    }

    "key length boundaries" - {
        "an empty key" in {
            assert(hex(Hmac.sha256(Array.emptyByteArray, utf8("x"))) == "4cbc96099a6467ce002461f10549b4898265ebe6188b45efacc44293516e62c4")
        }

        "a key one byte short of the block is padded" in {
            val key = Array.tabulate(63)(i => ((i * 7 + 3) & 0xff).toByte)
            assert(hex(Hmac.sha256(key, utf8("kyo"))) == "182928abf1b328b28433305c384b8205ffaa6b823709f1ba6786ec3f9c271772")
        }

        "a key of exactly one block is used as is" in {
            val key = Array.tabulate(64)(i => ((i * 7 + 3) & 0xff).toByte)
            assert(hex(Hmac.sha256(key, utf8("kyo"))) == "dfff71ad478c3e57e78a8cd68168c94fb19ea4946b448204f8e93e835c4624ee")
        }

        "a key one byte over the block is hashed first" in {
            val key = Array.tabulate(65)(i => ((i * 7 + 3) & 0xff).toByte)
            assert(hex(Hmac.sha256(key, utf8("kyo"))) == "5bd0aa739271d7151413909c27dddd692d3a969c1bb68bbb7a26df557273fd21")
            assert(Hmac.sha256(key, utf8("kyo")).sameElements(Hmac.sha256(Sha256.hash(key), utf8("kyo"))))
        }
    }

    "an empty message" in {
        assert(hex(Hmac.sha256(utf8("key"), Array.emptyByteArray)) == "5d5d139563c95b5967b9bd9a8c9b233a9dedb45072794cd232dc1b74832607d0")
    }

    "a one-byte change to the message changes the tag" in {
        val key = utf8("k")
        assert(!Hmac.sha256(key, utf8("abc")).sameElements(Hmac.sha256(key, utf8("abd"))))
    }

    "a one-byte change to the key changes the tag" in {
        val data = utf8("abc")
        assert(!Hmac.sha256(utf8("k1"), data).sameElements(Hmac.sha256(utf8("k2"), data)))
    }

    "leaves the key and message unchanged" in {
        val key             = Array.tabulate(131)(i => i.toByte)
        val message         = Array.tabulate(200)(i => (i * 3).toByte)
        val originalKey     = key.clone()
        val originalMessage = message.clone()
        discard(Hmac.sha256(key, message))
        assert(key.sameElements(originalKey))
        assert(message.sameElements(originalMessage))
    }

end HmacTest
