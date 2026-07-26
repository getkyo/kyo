package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.http1.*

/** Unit coverage for the field-value matching the parsers route every framing and connection decision through.
  *
  * These matter out of proportion to their size: a comparison that is one byte too generous turns "chunkedfoo" into chunked framing, and
  * one that is one byte too strict refuses conformant traffic. Both directions are pinned here, on the helper itself, so a defect is
  * located at the comparison rather than inferred from a parser leaf three layers up.
  *
  * Origin: GHSA-jrpm-956j-96jg.
  */
class HeaderTokensTest extends kyo.BaseHttpTest:

    private def b(s: String): Array[Byte] = s.getBytes(StandardCharsets.US_ASCII)

    /** Runs `f` against `s` embedded in a larger buffer, so any off-by-one reads a neighbouring byte rather than running off the end. */
    private def embedded(s: String)(f: (Array[Byte], Int, Int) => Boolean): Boolean =
        val padded = b("####" + s + "####")
        f(padded, 4, s.length)

    "nameEquals" - {
        "matches a whole name" in {
            assert(embedded("Content-Length")(HeaderTokens.nameEquals(_, _, _, "Content-Length")))
        }
        "is case-insensitive" in {
            assert(embedded("cOnTeNt-LeNgTh")(HeaderTokens.nameEquals(_, _, _, "Content-Length")))
        }
        // The defect this whole helper exists for: a comparison that stops at the target's length.
        "does not match a longer name by prefix" in {
            assert(!embedded("Content-Lengthx")(HeaderTokens.nameEquals(_, _, _, "Content-Length")))
        }
        "does not match a shorter name" in {
            assert(!embedded("Content-Lengt")(HeaderTokens.nameEquals(_, _, _, "Content-Length")))
        }
        // A name is a token and cannot carry OWS, so trimming here would accept what the grammar rejects.
        "does not trim whitespace" in {
            assert(!embedded("Content-Length ")(HeaderTokens.nameEquals(_, _, _, "Content-Length")))
        }
        "does not match an empty name" in {
            assert(!embedded("")(HeaderTokens.nameEquals(_, _, _, "Content-Length")))
        }
    }

    "listContainsToken" - {
        "finds a sole element" in {
            assert(embedded("upgrade")(HeaderTokens.listContainsToken(_, _, _, "upgrade")))
        }
        "finds an element in any position" in {
            assert(embedded("keep-alive, Upgrade")(HeaderTokens.listContainsToken(_, _, _, "upgrade")))
            assert(embedded("Upgrade, keep-alive")(HeaderTokens.listContainsToken(_, _, _, "upgrade")))
            assert(embedded("a, upgrade, b")(HeaderTokens.listContainsToken(_, _, _, "upgrade")))
        }
        // The substring defect: "no-upgrade" is a token whose meaning is the refusal, and it CONTAINS "upgrade".
        "does not match a token that merely contains the target" in {
            assert(!embedded("no-upgrade")(HeaderTokens.listContainsToken(_, _, _, "upgrade")))
            assert(!embedded("upgraded")(HeaderTokens.listContainsToken(_, _, _, "upgrade")))
        }
        "tolerates empty elements" in {
            assert(embedded("upgrade,")(HeaderTokens.listContainsToken(_, _, _, "upgrade")))
            assert(embedded(",upgrade")(HeaderTokens.listContainsToken(_, _, _, "upgrade")))
            assert(embedded("a,,upgrade")(HeaderTokens.listContainsToken(_, _, _, "upgrade")))
        }
        "does not match an empty list" in {
            assert(!embedded("")(HeaderTokens.listContainsToken(_, _, _, "upgrade")))
            assert(!embedded(",,")(HeaderTokens.listContainsToken(_, _, _, "upgrade")))
        }
    }

    "isSoleChunkedCoding" - {
        "accepts the sole token" in {
            assert(embedded("chunked")(HeaderTokens.isSoleChunkedCoding(_, _, _)))
        }
        // RFC 9110 section 5.6.1 permits empty list elements, so these still name exactly one coding.
        "accepts empty list elements around it" in {
            assert(embedded("chunked,")(HeaderTokens.isSoleChunkedCoding(_, _, _)))
            assert(embedded(", chunked")(HeaderTokens.isSoleChunkedCoding(_, _, _)))
        }
        // A second real coding is what makes the body length undeterminable, whichever side of chunked it sits on.
        "rejects any second coding" in {
            assert(!embedded("chunked, gzip")(HeaderTokens.isSoleChunkedCoding(_, _, _)))
            assert(!embedded("gzip, chunked")(HeaderTokens.isSoleChunkedCoding(_, _, _)))
        }
        "rejects a prefix of the token" in {
            assert(!embedded("chunkedfoo")(HeaderTokens.isSoleChunkedCoding(_, _, _)))
        }
        "rejects an empty value" in {
            assert(!embedded("")(HeaderTokens.isSoleChunkedCoding(_, _, _)))
        }
    }

    "finalCodingIsChunked" - {
        // RFC 9112 section 6.1 makes the FINAL coding the one that decides chunk framing on a response.
        "accepts chunked as the final coding" in {
            assert(embedded("chunked")(HeaderTokens.finalCodingIsChunked(_, _, _)))
            assert(embedded("gzip, chunked")(HeaderTokens.finalCodingIsChunked(_, _, _)))
        }
        "rejects chunked in a non-final position" in {
            assert(!embedded("chunked, gzip")(HeaderTokens.finalCodingIsChunked(_, _, _)))
        }
        "rejects a prefix of the token" in {
            assert(!embedded("chunkedfoo")(HeaderTokens.finalCodingIsChunked(_, _, _)))
        }
        "rejects an empty value" in {
            assert(!embedded("")(HeaderTokens.finalCodingIsChunked(_, _, _)))
        }
    }

end HeaderTokensTest
