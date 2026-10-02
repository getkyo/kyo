package kyo.crypto

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.crypto.TestVectors

class HmacTest extends kyo.test.Test[Any]:

    "every HMAC-SHA-256 the JDK computed in jdk-differential, keys from empty through longer than the block" in {
        val cases = kyo.internal.crypto.TestVectorsJdk.of("hmac")
        assert(cases.size == 17)
        assert(cases.exists(_.bytes("key").isEmpty) && cases.exists(_.bytes("key").length > 64))
        cases.foreach { c =>
            val out = Hmac.sha256Array(c.bytes("key"), c.bytes("msg")).map(b => f"${b & 0xff}%02x").mkString
            assert(out == c.value("out"), s"a ${c.bytes("key").length}-byte key")
        }
    }

    import HmacTest.*

    private def bytes(hex: String): Array[Byte] =
        Array.tabulate(hex.length / 2)(i => Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte)

    private def hex(bytes: Array[Byte]): String =
        bytes.map(b => f"${b & 0xff}%02x").mkString

    private def utf8(value: String): Array[Byte] =
        value.getBytes(StandardCharsets.UTF_8)

    "the vendored RFC 4231 text matches its MANIFEST digest" in {
        assert(TestVectors.names(set) == Seq("rfc4231.txt"))
        val embedded = TestVectors.text(set, "rfc4231.txt").getBytes(StandardCharsets.UTF_8)
        assert(Hex.encode(Sha256.hash(Span.from(embedded))) == TestVectors.sha256(set, "rfc4231.txt"))
    }

    "RFC 4231 section 4" - {
        "reads the seven test cases out of the RFC text" in {
            assert(cases.map(_.number) == (1 to 7))
            assert(cases.map(_.key.length) == Seq(20, 4, 20, 25, 20, 131, 131))
            assert(cases.map(_.data.length) == Seq(8, 28, 50, 50, 20, 54, 152))
            assert(cases.map(_.tag.length) == Seq(32, 32, 32, 32, 16, 32, 32))
        }

        "every test case produces the published HMAC-SHA-256, test case 5 truncated to 128 bits" in {
            cases.foreach { c =>
                assert(
                    Hmac.sha256(Span.from(c.key), Span.from(c.data)).toArray.take(c.tag.length).sameElements(c.tag),
                    s"test case ${c.number}"
                )
            }
        }
    }

    "key length boundaries" - {
        "an empty key" in {
            assert(hex(Hmac.sha256Array(Array.emptyByteArray, utf8("x"))) ==
                "4cbc96099a6467ce002461f10549b4898265ebe6188b45efacc44293516e62c4")
        }

        "a key one byte short of the block is padded" in {
            val key = Array.tabulate(63)(i => ((i * 7 + 3) & 0xff).toByte)
            assert(hex(Hmac.sha256Array(key, utf8("kyo"))) == "182928abf1b328b28433305c384b8205ffaa6b823709f1ba6786ec3f9c271772")
        }

        "a key of exactly one block is used as is" in {
            val key = Array.tabulate(64)(i => ((i * 7 + 3) & 0xff).toByte)
            assert(hex(Hmac.sha256Array(key, utf8("kyo"))) == "dfff71ad478c3e57e78a8cd68168c94fb19ea4946b448204f8e93e835c4624ee")
        }

        "a key one byte over the block is hashed first (RFC 2104 section 2; RFC 4231 test cases 6 and 7 use a 131-byte key)" in {
            val key = Array.tabulate(65)(i => ((i * 7 + 3) & 0xff).toByte)
            assert(hex(Hmac.sha256Array(key, utf8("kyo"))) == "5bd0aa739271d7151413909c27dddd692d3a969c1bb68bbb7a26df557273fd21")
            assert(Hmac.sha256Array(key, utf8("kyo")).sameElements(Hmac.sha256Array(Sha256.hashArray(key), utf8("kyo"))))
        }
    }

    "an empty message" in {
        assert(hex(Hmac.sha256Array(utf8("key"), Array.emptyByteArray)) ==
            "5d5d139563c95b5967b9bd9a8c9b233a9dedb45072794cd232dc1b74832607d0")
    }

    "a one-byte change to the message changes the tag" in {
        val key = utf8("k")
        assert(!Hmac.sha256Array(key, utf8("abc")).sameElements(Hmac.sha256Array(key, utf8("abd"))))
    }

    "a one-byte change to the key changes the tag" in {
        val data = utf8("abc")
        assert(!Hmac.sha256Array(utf8("k1"), data).sameElements(Hmac.sha256Array(utf8("k2"), data)))
    }

    "leaves the key and message unchanged" in {
        val key             = Array.tabulate(131)(i => i.toByte)
        val message         = Array.tabulate(200)(i => (i * 3).toByte)
        val originalKey     = key.clone()
        val originalMessage = message.clone()
        discard(Hmac.sha256Array(key, message))
        assert(key.sameElements(originalKey))
        assert(message.sameElements(originalMessage))
    }

    "the array tier produces the bytes of the Span surface" in {
        cases.foreach { c =>
            assert(Hmac.sha256Array(c.key, c.data).toSeq == Hmac.sha256(Span.from(c.key), Span.from(c.data)).toArray.toSeq)
        }
    }

    "verifySha256" - {
        val key     = Span.from(bytes("4a656665"))
        val message = Span.from(bytes("7768617420646f2079612077616e7420666f72206e6f7468696e673f"))
        val tag     = bytes("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")

        "accepts the tag of the message under the key" in {
            assert(Hmac.verifySha256(key, message, Span.from(tag)))
        }

        "accepts the tag it computes itself" in {
            assert(Hmac.verifySha256(key, message, Hmac.sha256(key, message)))
        }

        "rejects a tag with any single bit flipped" in {
            val accepted =
                for
                    position <- 0 until tag.length
                    bit      <- 0 until 8
                yield
                    val flipped = tag.clone()
                    flipped(position) = (flipped(position) ^ (1 << bit)).toByte
                    Hmac.verifySha256(key, message, Span.from(flipped))
            assert(accepted.size == 256)
            assert(!accepted.contains(true))
        }

        "rejects the tag under another key" in {
            assert(!Hmac.verifySha256(Span.from(bytes("4a656666")), message, Span.from(tag)))
        }

        "rejects the tag of another message" in {
            assert(!Hmac.verifySha256(key, Span.from(bytes("7768617420646f2079612077616e7420666f72206e6f7468696e6721")), Span.from(tag)))
        }

        "rejects a tag of the wrong length" in {
            assert(!Hmac.verifySha256(key, message, Span.empty[Byte]))
            assert(!Hmac.verifySha256(key, message, Span.from(tag.take(31))))
            assert(!Hmac.verifySha256(key, message, Span.from(tag :+ 0.toByte)))
            assert(!Hmac.verifySha256(key, message, Span.from(tag.take(16))))
        }
    }

end HmacTest

object HmacTest:

    val set = "ietf-rfc4231"

    /** One test case of RFC 4231 section 4: the key, the data, and the HMAC-SHA-256 as printed, 16 bytes where the case truncates. */
    final case class Case(number: Int, key: Array[Byte], data: Array[Byte], tag: Array[Byte])

    /** The seven cases, read from the section headings `4.n.  Test Case n` and the `Key`, `Data` and `HMAC-SHA-256` fields, each a
      * first hex word after its label and hex words on the following lines up to a blank line or the next label.
      */
    lazy val cases: Seq[Case] =
        val lines = TestVectors.text(set, "rfc4231.txt").linesIterator.toVector
        val start = lines.indexWhere(_.trim == "4.2.  Test Case 1")
        val end   = lines.indexWhere(_.trim == "5.  Security Considerations")
        require(start >= 0 && end > start, "RFC 4231 section 4 not found")
        val headings                     = (start until end).filter(i => lines(i).startsWith("4.") && lines(i).contains("Test Case"))
        def isHex(word: String): Boolean = word.length >= 2 && word.length % 2 == 0 && word.forall(c => "0123456789abcdef".contains(c))
        def field(section: Vector[String], label: String): Array[Byte] =
            val at = section.indexWhere(line => line.trim.startsWith(label + " "))
            require(at >= 0, s"$label not found")
            val first = section(at).trim.split("\\s+").drop(1).filterNot(_ == "=").take(1).toSeq
            val rest  =
                section.drop(at + 1).takeWhile(line => line.trim.nonEmpty && isHex(line.trim.split("\\s+")(0))).map(_.trim.split("\\s+")(0))
            val words = first ++ rest
            require(words.forall(isHex), s"$label has a non-hex word in $words")
            words.mkString.grouped(2).map(pair => Integer.parseInt(pair, 16).toByte).toArray
        end field
        headings.zipWithIndex.map { (heading, i) =>
            val sectionEnd = if i + 1 < headings.size then headings(i + 1) else end
            val section    = lines.slice(heading, sectionEnd)
            val number     = lines(heading).trim.substring("4.n.  Test Case ".length).trim.toInt
            Case(number, field(section, "Key"), field(section, "Data"), field(section, "HMAC-SHA-256"))
        }
    end cases

end HmacTest
