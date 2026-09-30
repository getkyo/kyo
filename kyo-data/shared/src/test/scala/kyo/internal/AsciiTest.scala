package kyo.internal

import kyo.*

class AsciiTest extends kyo.test.Test[Any]:

    private val dottedCapitalI  = 'İ'
    private val dotlessSmallI   = 'ı'
    private val kelvinSign      = '\u212a'
    private val arabicIndicOne  = '١'
    private val fullwidthDigit1 = '\uff11'

    "folding" - {
        "lowers and uppers exactly A to Z" in {
            assert(Ascii.toLower("TEXT/PLAIN; CHARSET=UTF-8") == "text/plain; charset=utf-8")
            assert(Ascii.toUpper("nonexistent") == "NONEXISTENT")
            assert(Ascii.toLower("I") == "i")
            assert(Ascii.toUpper("i") == "I")
        }
        "leaves the Turkish i letters unchanged" in {
            assert(Ascii.toLower(dottedCapitalI.toString) == dottedCapitalI.toString)
            assert(Ascii.toUpper(dotlessSmallI.toString) == dotlessSmallI.toString)
            assert(Ascii.toLower(dotlessSmallI.toString) == dotlessSmallI.toString)
            assert(Ascii.toUpper(dottedCapitalI.toString) == dottedCapitalI.toString)
        }
        "does not turn the Kelvin sign into k" in {
            assert(Ascii.toLower(kelvinSign.toString) == kelvinSign.toString)
            assert(Ascii.toUpper(kelvinSign.toString) == kelvinSign.toString)
        }
        "leaves other non-ASCII letters unchanged" in {
            assert(Ascii.toLower("ÉTÉ") == "ÉtÉ")
            assert(Ascii.toUpper("été") == "éTé")
        }
        "leaves the characters next to the letter ranges unchanged" in {
            assert(Ascii.toLower("@[`{") == "@[`{")
            assert(Ascii.toUpper("@[`{") == "@[`{")
        }
    }

    "equalsIgnoreCase" - {
        "matches any ASCII casing" in {
            assert(Ascii.equalsIgnoreCase("InBoX", "INBOX"))
            assert(Ascii.equalsIgnoreCase("ipv6:", "IPv6:"))
        }
        "does not match through a Turkish i or the Kelvin sign" in {
            assert(!Ascii.equalsIgnoreCase(s"${dotlessSmallI}nbox", "INBOX"))
            assert(!Ascii.equalsIgnoreCase(s"${dottedCapitalI}NBOX", "inbox"))
            assert(!Ascii.equalsIgnoreCase(s"${kelvinSign}oi8-r", "koi8-r"))
        }
        "does not match strings of different lengths" in {
            assert(!Ascii.equalsIgnoreCase("INBOX", "INBOX2"))
        }
    }

    "classes" - {
        "digits are 0 to 9 only" in {
            assert("0123456789".forall(Ascii.isDigit))
            assert(!Ascii.isDigit(arabicIndicOne))
            assert(!Ascii.isDigit(fullwidthDigit1))
            assert(!Ascii.isDigit('/'))
            assert(!Ascii.isDigit(':'))
        }
        "hex digits are 0 to 9, a to f and A to F only" in {
            assert("0123456789abcdefABCDEF".forall(Ascii.isHexDigit))
            assert(!Ascii.isHexDigit('g'))
            assert(!Ascii.isHexDigit('G'))
            assert(!Ascii.isHexDigit(arabicIndicOne))
        }
        "letters are A to Z and a to z only" in {
            assert(Ascii.isAlpha('A') && Ascii.isAlpha('z'))
            assert(!Ascii.isAlpha('é'))
            assert(!Ascii.isAlpha(dotlessSmallI))
            assert(!Ascii.isAlpha(kelvinSign))
            assert(!Ascii.isAlphaNumeric('-'))
            assert(Ascii.isAlphaNumeric('7'))
        }
    }

    "parseDigits" - {
        "reads ASCII digits" in {
            assert(Ascii.parseDigits("0") == Present(0))
            assert(Ascii.parseDigits("255") == Present(255))
            assert(Ascii.parseDigits("007") == Present(7))
            assert(Ascii.parseDigits("999999999") == Present(999999999))
        }
        "rejects empty text, signs, other characters, non-ASCII digits and values past nine digits" in {
            assert(Ascii.parseDigits("") == Absent)
            assert(Ascii.parseDigits("-1") == Absent)
            assert(Ascii.parseDigits("+1") == Absent)
            assert(Ascii.parseDigits("1a") == Absent)
            assert(Ascii.parseDigits(s"$arabicIndicOne") == Absent)
            assert(Ascii.parseDigits(s"$fullwidthDigit1") == Absent)
            assert(Ascii.parseDigits("1234567890") == Absent)
        }
    }

end AsciiTest
