package kyo.internal

/** Pure unit tests for [[PercentEncode]].
  *
  * Verifies the ASCII unreserved pass-through plus the non-ASCII (`>= 0x80`) UTF-8 multi-byte percent-encoding path. The encoder feeds
  * `data:` URLs so a regression that misencodes high-byte sequences would corrupt every non-ASCII page literal.
  */
class PercentEncodeTest extends kyo.Test:

    "PercentEncode" - {

        "PercentEncode(\"\") returns an empty string" in {
            assert(PercentEncode("") == "")
        }

        "PercentEncode passes ASCII unreserved chars through unchanged (RFC 3986 unreserved set)" in {
            val unreserved = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~"
            assert(PercentEncode(unreserved) == unreserved, s"expected pass-through but got '${PercentEncode(unreserved)}'")
        }

        "PercentEncode escapes ASCII space to %20 (not '+' like URLEncoder)" in {
            // The key difference from java.net.URLEncoder: space must be %20, never +. data:
            // URLs decode + as literal '+'.
            assert(PercentEncode(" ") == "%20", s"expected '%20' but got '${PercentEncode(" ")}'")
            assert(PercentEncode("a b") == "a%20b", s"expected 'a%20b' but got '${PercentEncode("a b")}'")
        }

        "PercentEncode escapes ASCII punctuation outside the unreserved set" in {
            // Common HTML-template punctuation that data: URLs need percent-escaped.
            assert(PercentEncode("<") == "%3C", s"expected '%3C' but got '${PercentEncode("<")}'")
            assert(PercentEncode(">") == "%3E", s"expected '%3E' but got '${PercentEncode(">")}'")
            assert(PercentEncode("&") == "%26", s"expected '%26' but got '${PercentEncode("&")}'")
            assert(PercentEncode("=") == "%3D", s"expected '%3D' but got '${PercentEncode("=")}'")
            assert(PercentEncode("/") == "%2F", s"expected '%2F' but got '${PercentEncode("/")}'")
        }

        "PercentEncode encodes a 2-byte UTF-8 sequence (e.g. 'e' acute, U+00E9) as %C3%A9 (non-ASCII byte path >= 0x80)" in {
            // 'e' acute is U+00E9; UTF-8 = C3 A9. Both bytes are >= 0x80 and exercise the non-ASCII path.
            assert(PercentEncode("é") == "%C3%A9", s"expected '%C3%A9' but got '${PercentEncode("é")}'")
        }

        "PercentEncode encodes a 3-byte UTF-8 sequence (e.g. 'middle' U+4E2D) as %E4%B8%AD" in {
            // 'middle' U+4E2D; UTF-8 = E4 B8 AD. Three-byte UTF-8 sequence, all >= 0x80.
            assert(PercentEncode("中") == "%E4%B8%AD", s"expected '%E4%B8%AD' but got '${PercentEncode("中")}'")
        }

        "PercentEncode encodes a 4-byte UTF-8 sequence (surrogate pair for U+1F389, party popper) as %F0%9F%8E%89" in {
            // U+1F389 'party popper' encoded as a Java surrogate pair (D83C DF89); UTF-8 = F0 9F 8E 89.
            val partyPopper = "🎉"
            assert(
                PercentEncode(partyPopper) == "%F0%9F%8E%89",
                s"expected '%F0%9F%8E%89' but got '${PercentEncode(partyPopper)}'"
            )
        }

        "PercentEncode interleaves ASCII and non-ASCII correctly without dropping or shifting bytes" in {
            // Sanity: bytes around a non-ASCII boundary must stay aligned.
            assert(
                PercentEncode("aéb") == "a%C3%A9b",
                s"expected 'a%C3%A9b' but got '${PercentEncode("aéb")}'"
            )
        }

        "PercentEncode uppercases hex digits in the percent-escape" in {
            // RFC 3986 recommends uppercase hex; the encoder uses the constant "0123456789ABCDEF".
            val encoded = PercentEncode("é")
            assert(!encoded.contains("c3"), s"expected uppercase hex but found lowercase in '$encoded'")
            assert(!encoded.contains("a9"), s"expected uppercase hex but found lowercase in '$encoded'")
            assert(encoded.contains("C3"), s"expected uppercase 'C3' in '$encoded'")
            assert(encoded.contains("A9"), s"expected uppercase 'A9' in '$encoded'")
        }
    }

end PercentEncodeTest
