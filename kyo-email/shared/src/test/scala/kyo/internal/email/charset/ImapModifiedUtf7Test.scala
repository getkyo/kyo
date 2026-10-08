package kyo.internal.email.charset

import kyo.*

class ImapModifiedUtf7Test extends kyo.test.Test[Any]:

    import ImapModifiedUtf7.Rejection

    private def text(codePoints: Int*): String = codePoints.map(cp => new String(Character.toChars(cp))).mkString

    "RFC 3501 section 5.1.3 examples" - {
        "the mixed English, Chinese and Japanese name decodes and encodes back to itself" in {
            val name    = "~peter/mail/&U,BTFw-/&ZeVnLIqe-"
            val decoded = ImapModifiedUtf7.decode(name).getOrElse(fail("rejected"))
            assert(ImapModifiedUtf7.encode(decoded) == Result.succeed(name))
            assert(decoded.startsWith("~peter/mail/"))
        }
        "the invalid name, the name with a superfluous shift and their correct forms" in {
            val correct = Seq("&Jjo-!", "&U,BTF2XlZyyKng-")
            assert(ImapModifiedUtf7.decode("&Jjo!") == Result.fail(Rejection.UnterminatedRun(0)))
            assert(ImapModifiedUtf7.decode("&U,BTFw-&ZeVnLIqe-") == Result.fail(Rejection.NullShift(8)))
            correct.foreach { name =>
                val decoded = ImapModifiedUtf7.decode(name).getOrElse(fail(s"rejected $name"))
                assert(ImapModifiedUtf7.encode(decoded) == Result.succeed(name))
            }
            val joined = Seq("&U,BTFw-", "&ZeVnLIqe-").map(n => ImapModifiedUtf7.decode(n).getOrElse(fail(s"rejected $n"))).mkString
            assert(ImapModifiedUtf7.decode(correct(1)) == Result.succeed(joined))
            assert(ImapModifiedUtf7.decode(correct(0)) == Result.succeed(text(0x263a, '!')))
        }
        "the run shared with RFC 2152's nihongo example decodes to the code units RFC 2152 lists" in {
            assert(ImapModifiedUtf7.decode("&ZeVnLIqe-") == Result.succeed(text(0x65e5, 0x672c, 0x8a9e)))
        }
    }

    "round trip" - {
        "every BMP code point that is not a surrogate, alone and inside printable text" in {
            val failures = (0 to 0xffff).filterNot(c => c >= 0xd800 && c <= 0xdfff).filter { c =>
                val s = s"a${c.toChar}b"
                ImapModifiedUtf7.encode(s).flatMap(ImapModifiedUtf7.decode) != Result.succeed(s) ||
                ImapModifiedUtf7.encode(c.toChar.toString).flatMap(ImapModifiedUtf7.decode) != Result.succeed(c.toChar.toString)
            }
            assert(failures.isEmpty)
        }
        "every BMP code point that is not a surrogate, in one run" in {
            val all     = (0 to 0xffff).filterNot(c => c >= 0xd800 && c <= 0xdfff || (c >= 0x20 && c <= 0x7e)).map(_.toChar).mkString
            val encoded = ImapModifiedUtf7.encode(all).getOrElse(fail("rejected"))
            assert(encoded.count(_ == '&') == 1)
            assert(ImapModifiedUtf7.decode(encoded) == Result.succeed(all))
        }
        "supplementary code points" in {
            Seq(0x10000, 0x1f600, 0x20000, 0x2a6d6, 0xe0001, 0x10ffff).foreach { cp =>
                val s = "x" + text(cp) + "y" + text(cp, cp)
                assert(ImapModifiedUtf7.encode(s).flatMap(ImapModifiedUtf7.decode) == Result.succeed(s))
            }
            succeed
        }
        "every accepted name encodes back to itself" in {
            val names = Seq("INBOX", "&-", "a&-b", "&AOk-t&AOk-", "Entw&APw-rfe", "~peter/mail/&U,BTFw-/&ZeVnLIqe-", "&2D3eAA-")
            names.foreach { name =>
                assert(ImapModifiedUtf7.decode(name).flatMap(ImapModifiedUtf7.encode) == Result.succeed(name))
            }
            succeed
        }
    }

    "canonical form" in {
        assert(ImapModifiedUtf7.encode("INBOX") == Result.succeed("INBOX"))
        assert(ImapModifiedUtf7.encode("&") == Result.succeed("&-"))
        assert(ImapModifiedUtf7.encode("a&b") == Result.succeed("a&-b"))
        assert(ImapModifiedUtf7.encode("Entw" + text(0xfc) + "rfe") == Result.succeed("Entw&APw-rfe"))
        assert(ImapModifiedUtf7.encode(text(0xe9) + "t" + text(0xe9)) == Result.succeed("&AOk-t&AOk-"))
        assert(ImapModifiedUtf7.encode(text(0x1f600)) == Result.succeed("&2D3eAA-"))
        assert(ImapModifiedUtf7.encode(text(0x263a) + "&") == Result.succeed("&Jjo-&-"))
        assert(ImapModifiedUtf7.encode("tab\there") == Result.succeed("tab&AAk-here"))
        assert(ImapModifiedUtf7.encode("") == Result.succeed(""))
    }

    "rejections" - {
        "a character outside 0x20 to 0x7E written directly" in {
            assert(ImapModifiedUtf7.decode("ab" + text(0xe9)) == Result.fail(Rejection.NotPrintableAscii(2)))
            assert(ImapModifiedUtf7.decode("a\tb") == Result.fail(Rejection.NotPrintableAscii(1)))
        }
        "& followed by neither base64 nor -, or at the end" in {
            assert(ImapModifiedUtf7.decode("a&!") == Result.fail(Rejection.ShiftWithoutRun(1)))
            assert(ImapModifiedUtf7.decode("a&") == Result.fail(Rejection.ShiftWithoutRun(1)))
        }
        "a run not closed by -" in {
            assert(ImapModifiedUtf7.decode("x&Jjo!") == Result.fail(Rejection.UnterminatedRun(1)))
            assert(ImapModifiedUtf7.decode("x&Jjo") == Result.fail(Rejection.UnterminatedRun(1)))
        }
        "a run starting where the previous one ended" in {
            assert(ImapModifiedUtf7.decode("&AOk-&AOk-") == Result.fail(Rejection.NullShift(5)))
        }
        "non-zero leftover bits, or six or more" in {
            assert(ImapModifiedUtf7.decode("&AOl-") == Result.fail(Rejection.LeftoverBits(0)))
            assert(ImapModifiedUtf7.decode("&AOkA-") == Result.fail(Rejection.LeftoverBits(0)))
            assert(ImapModifiedUtf7.decode("&A-") == Result.fail(Rejection.LeftoverBits(0)))
        }
        "base64 for a printable character, & included" in {
            assert(ImapModifiedUtf7.decode("&AGE-") == Result.fail(Rejection.EncodedPrintableAscii(0)))
            assert(ImapModifiedUtf7.decode("&ACY-") == Result.fail(Rejection.EncodedPrintableAscii(0)))
            assert(ImapModifiedUtf7.decode("&AOkAYQ-") == Result.fail(Rejection.EncodedPrintableAscii(0)))
        }
        "an unpaired surrogate in a run" in {
            assert(ImapModifiedUtf7.decode("&2D0-") == Result.fail(Rejection.UnpairedSurrogate(0)))
            assert(ImapModifiedUtf7.decode("&3gA-") == Result.fail(Rejection.UnpairedSurrogate(0)))
        }
        "an unpaired surrogate in the text to encode" in {
            assert(ImapModifiedUtf7.encode("ab" + 0xd83d.toChar + "c") == Result.fail(Rejection.UnpairedSurrogate(2)))
            assert(ImapModifiedUtf7.encode("a" + 0xde00.toChar) == Result.fail(Rejection.UnpairedSurrogate(1)))
        }
    }

end ImapModifiedUtf7Test
