package kyo

import kyo.EmailLiterals.*

class EmailFlagTest extends kyo.test.Test[Any]:

    "a keyword is a non-empty IMAP atom without a backslash, and init fails on any other name" in {
        assert(keywordOf("$Forwarded").wire == "$Forwarded")
        assert(keywordOf("Work") == keywordOf("Work"))
        Seq("", "two words", "\\Seen", "a(b", "a]b", "a\"b", "a%b", "a*b", "a{b", "café", "a\u0001b").foreach { name =>
            assert(Email.Flag.Keyword.init(name) == Result.fail(EmailInvalidFlagException(name)), name)
        }
    }

    "reading" - {
        "a system flag in any case of its name" in {
            assert(Email.Flag.fromWire("\\SEEN") == Present(Email.Flag.Seen))
            assert(Email.Flag.fromWire("\\draft") == Present(Email.Flag.Draft))
        }
        "\\Recent is dropped" in {
            assert(Email.Flag.fromWire("\\Recent") == Absent)
            assert(Email.Flag.fromWire("\\RECENT") == Absent)
        }
        "a keyword, and a system flag the module does not model, kept as written" in {
            assert(Email.Flag.fromWire("$Junk") == Present(keywordOf("$Junk")))
            assert(Email.Flag.fromWire("\\Forwarded").map(_.wire) == Present("\\Forwarded"))
            assert(Email.Flag.fromWire("\\Forwarded").exists(_.isInstanceOf[Email.Flag.Other]))
        }
        "a keyword that is not an IMAP atom is kept as Other, as sent" in {
            assert(Email.Flag.fromWire("$a%b").map(_.wire) == Present("$a%b"))
            assert(Email.Flag.fromWire("$a%b").exists(_.isInstanceOf[Email.Flag.Other]))
            assert(Email.Flag.fromWire("a]b").exists(_.isInstanceOf[Email.Flag.Other]))
        }
        "text no flag holds reads as nothing" in {
            Seq("", "a b", "a(b", "a)b", "a\tb", "a\u007fb").foreach { text =>
                assert(!Email.Flag.isFlagText(text))
                assert(Email.Flag.fromWire(text) == Absent)
            }
        }
    }

    "a rejection inside a derived schema names the decoding caller's Frame, for a keyword, another flag and a flag" in {
        val other = Email.Flag.fromWire("$a%b") match
            case Present(other: Email.Flag.Other) => other
            case read                             => fail(s"expected an Other, got $read")
        val stored = Json.encode(FlagsShape(keywordOf("Work"), other, Email.Flag.Seen))
        Seq("\"Work\"" -> "\"a b\"", "\"$a%b\"" -> "\"a b\"", "\"\\\\Seen\"" -> "\"\\\\Recent\"").foreach { (valid, invalid) =>
            assert(stored.contains(valid), stored)
            Json.decode[FlagsShape](stored.replace(valid, invalid)) match
                case Result.Failure(e: ConstructorRejectedException) =>
                    e.rejection match
                        case leaf: EmailInvalidFlagException =>
                            assert(leaf.frame == e.frame, s"$valid rejected at ${leaf.frame}, decoded at ${e.frame}")
                        case other => fail(s"expected the constructor's own failure for $valid, got $other")
                case other => fail(s"expected a ConstructorRejectedException for $valid, got $other")
            end match
        }
    }

    final private case class FlagsShape(keyword: Email.Flag.Keyword, other: Email.Flag.Other, flag: Email.Flag) derives Schema

end EmailFlagTest
