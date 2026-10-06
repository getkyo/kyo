package kyo

import kyo.EmailReceive.ResponseCode.*

class EmailReceiveResponseCodeTest extends kyo.test.Test[Any]:

    "an atom is read in any ASCII casing" in {
        assert(EmailReceive.ResponseCode.fromWire("nonexistent", Absent) == NonExistent)
        assert(EmailReceive.ResponseCode.fromWire("TryCreate", Absent) == TryCreate)
    }

    "BADCHARSET carries the charsets the server lists, atoms or quoted, and none when it lists none (RFC 9051 section 7.1)" in {
        assert(EmailReceive.ResponseCode.fromWire("BADCHARSET", Present("(US-ASCII \"UTF-8\")")) == BadCharset(Chunk("US-ASCII", "UTF-8")))
        assert(EmailReceive.ResponseCode.fromWire("badcharset", Absent) == BadCharset(Chunk.empty))
        assert(BadCharset(Chunk("US-ASCII", "UTF-8")).show == "BADCHARSET (US-ASCII UTF-8)")
        assert(BadCharset(Chunk.empty).show == "BADCHARSET")
    }

    "a modelled atom that arrives with arguments its grammar does not have stays unmodelled, so nothing is dropped" in {
        assert(EmailReceive.ResponseCode.fromWire("NONEXISTENT", Present("x")) == Other("NONEXISTENT", Present("x")))
        assert(EmailReceive.ResponseCode.fromWire("BADCHARSET", Present("UTF-8")) == Other("BADCHARSET", Present("UTF-8")))
        assert(EmailReceive.ResponseCode.fromWire("BADCHARSET", Present("()")) == Other("BADCHARSET", Present("()")))
    }

    "an unmodelled code keeps its atom and arguments" in {
        assert(Other("WEBALERT", Present("https://example.com/unlock")).show == "WEBALERT https://example.com/unlock")
        assert(Other("X-VENDOR", Absent).show == "X-VENDOR")
        assert(
            EmailReceive.ResponseCode.fromWire("WEBALERT", Present("https://example.com/unlock")) ==
                Other("WEBALERT", Present("https://example.com/unlock"))
        )
        assert(EmailReceive.ResponseCode.fromWire("X-Vendor", Absent) == Other("X-Vendor", Absent))
    }

    "a non-ASCII look-alike of an atom is not the atom" in {
        assert(EmailReceive.ResponseCode.fromWire("NONEXİSTENT", Absent) == Other("NONEXİSTENT", Absent))
        assert(EmailReceive.ResponseCode.fromWire("nonexıstent", Absent) == Other("nonexıstent", Absent))
    }

    "round-trips through Json, a modelled code, one with arguments and an unmodelled one" in {
        Seq[EmailReceive.ResponseCode](
            NonExistent,
            BadCharset(Chunk("US-ASCII", "UTF-8")),
            Other("WEBALERT", Present("https://x")),
            Other("X", Absent)
        )
            .foreach { code =>
                assert(Json.decode[EmailReceive.ResponseCode](Json.encode(code)) == Result.succeed(code))
            }
    }

end EmailReceiveResponseCodeTest
