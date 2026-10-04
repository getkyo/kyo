package kyo.internal

import kyo.internal.PercentEncoding.Mode

class PercentEncodingTest extends kyo.BaseHttpTest:

    "Component" - {

        "an empty string stays empty" in {
            assert(PercentEncoding.encode("", Mode.Component) == "")
            assert(PercentEncoding.decode("", Mode.Component) == "")
        }

        "the RFC 3986 unreserved set passes through" in {
            val unreserved = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~"
            assert(PercentEncoding.encode(unreserved, Mode.Component) == unreserved)
        }

        "a space is %20 and a '+' is %2B" in {
            assert(PercentEncoding.encode("a b+c", Mode.Component) == "a%20b%2Bc")
        }

        "reserved and other ASCII characters are escaped" in {
            assert(PercentEncoding.encode("<>&=/?#%*", Mode.Component) == "%3C%3E%26%3D%2F%3F%23%25%2A")
        }

        "UTF-8 sequences of two, three and four bytes are escaped with uppercase hex" in {
            assert(PercentEncoding.encode("é", Mode.Component) == "%C3%A9")
            assert(PercentEncoding.encode("中", Mode.Component) == "%E4%B8%AD")
            assert(PercentEncoding.encode("🎉", Mode.Component) == "%F0%9F%8E%89")
            assert(PercentEncoding.encode("aéb", Mode.Component) == "a%C3%A9b")
        }

        "decoding reads '+' as itself" in {
            assert(PercentEncoding.decode("a%20b+c", Mode.Component) == "a b+c")
        }

        "decoding accepts lowercase hex" in {
            assert(PercentEncoding.decode("%c3%a9", Mode.Component) == "é")
        }

        "decoding keeps an escape that is not two hex digits" in {
            assert(PercentEncoding.decode("100%", Mode.Component) == "100%")
            assert(PercentEncoding.decode("%zz%4", Mode.Component) == "%zz%4")
        }

        "decoding keeps a leading byte order mark" in {
            assert(PercentEncoding.decode("%EF%BB%BFa", Mode.Component) == "﻿a")
        }

        "text round-trips" in {
            val text = "/tmp/a b+c %~*é中🎉"
            assert(PercentEncoding.decode(PercentEncoding.encode(text, Mode.Component), Mode.Component) == text)
        }
    }

    "Form" - {

        "a space is '+' and a '+' is %2B" in {
            assert(PercentEncoding.encode("a b+c", Mode.Form) == "a+b%2Bc")
        }

        "'*' stays and '~' is escaped" in {
            assert(PercentEncoding.encode("*~", Mode.Form) == "*%7E")
        }

        "decoding reads '+' as a space" in {
            assert(PercentEncoding.decode("a+b%2Bc", Mode.Form) == "a b+c")
        }

        "text round-trips" in {
            val text = "name=a b+c&d %~*é"
            assert(PercentEncoding.decode(PercentEncoding.encode(text, Mode.Form), Mode.Form) == text)
        }
    }

end PercentEncodingTest
