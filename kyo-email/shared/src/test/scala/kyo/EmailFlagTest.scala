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

end EmailFlagTest
