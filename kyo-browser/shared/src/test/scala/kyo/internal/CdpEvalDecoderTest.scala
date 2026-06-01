package kyo.internal

import kyo.*

/** Pure unit tests for [[CdpEvalDecoder]] post-processing helpers.
  *
  * All scenarios are pure (no browser, no I/O). The helpers under test are pure JSON-shape transforms over CDP `Runtime.evaluate`
  * responses.
  */
class CdpEvalDecoderTest extends kyo.Test:

    // CDP wire = full envelope: `{"id":N,"result":<EvalResult>}` or `{"id":N,"error":<CdpError>}`.
    // The CdpClient dispatcher passes the whole wire to awaiting callers; this helper wraps the inner EvalResult JSON.
    private def replyOk(evalResult: String): String = s"""{"id":1,"result":$evalResult}"""
    private def replyErr(cdpError: String): String  = s"""{"id":1,"error":$cdpError}"""

    // -------------------------------------------------------------------------
    // parseAndExtractEvalValue
    // -------------------------------------------------------------------------

    "parseAndExtractEvalValue - string value" in run {
        val json = replyOk("""{"result":{"type":"string","value":"foo"}}""")
        Abort.run[BrowserConnectionException](CdpEvalDecoder.parseAndExtractEvalValue(json)).map {
            case Result.Success(s) => assert(s == "foo")
            case other             => fail(s"Expected Success(\"foo\") but got $other")
        }
    }

    "parseAndExtractEvalValue - number value" in run {
        val json = replyOk("""{"result":{"type":"number","value":42,"description":"42"}}""")
        Abort.run[BrowserConnectionException](CdpEvalDecoder.parseAndExtractEvalValue(json)).map {
            case Result.Success(s) => assert(s == "42")
            case other             => fail(s"Expected Success(\"42\") but got $other")
        }
    }

    "parseAndExtractEvalValue - undefined yields empty string" in run {
        val json = replyOk("""{"result":{"type":"undefined"}}""")
        Abort.run[BrowserConnectionException](CdpEvalDecoder.parseAndExtractEvalValue(json)).map {
            case Result.Success(s) => assert(s == "")
            case other             => fail(s"Expected Success(\"\") but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // String escape round-tripping (handled by RemoteObject's custom Schema
    // re-parsing the captured raw JSON literal via Json.decode[String]).
    // -------------------------------------------------------------------------

    "RemoteObject string Schema decodes \\n, \\\", and unicode escapes back to the source characters" in run {
        // Case 1: "hello\nworld" encoded as JSON string "hello\nworld"
        val jsonNewline = replyOk("""{"result":{"type":"string","value":"hello\nworld"}}""")
        Abort.run[BrowserConnectionException](CdpEvalDecoder.parseAndExtractEvalValue(jsonNewline)).map {
            case Result.Success(s) => assert(s == "hello\nworld")
            case other             => fail(s"Case 1: expected hello<newline>world but got $other")
        }.andThen {
            // Case 2: a"b encoded as "a\"b"
            val jsonQuote = replyOk("""{"result":{"type":"string","value":"a\"b"}}""")
            Abort.run[BrowserConnectionException](CdpEvalDecoder.parseAndExtractEvalValue(jsonQuote)).map {
                case Result.Success(s) => assert(s == "a\"b")
                case other             => fail(s"Case 2: expected a\"b but got $other")
            }
        }.andThen {
            // Case 3: a \\u0041 unicode escape decodes to "A"
            val jsonUnicode = replyOk("{\"result\":{\"type\":\"string\",\"value\":\"\\u0041\"}}")
            Abort.run[BrowserConnectionException](CdpEvalDecoder.parseAndExtractEvalValue(jsonUnicode)).map {
                case Result.Success(s) => assert(s == "A")
                case other             => fail(s"Case 3: expected A but got $other")
            }
        }
    }

    // -------------------------------------------------------------------------
    // CdpEvalEnvelope.decodeEvalEnvelope; CDP error payload detection
    // -------------------------------------------------------------------------
    //
    // `CdpClient.decodeCdpMessage` forwards the WHOLE wire frame (CdpReply envelope). The envelope decoder
    // surfaces a `wire.error` payload as a typed `BrowserProtocolErrorException` carrying the CDP code+message.

    "decodeEvalEnvelope - CDP error payload surfaces as typed exception with code+message" in run {
        val json = replyErr("""{"code":-32000,"message":"Cannot find context with specified id"}""")
        Abort.run[BrowserReadException](CdpEvalEnvelope.decodeEvalEnvelope(json, "eval")(_ => "should-not-reach")).map {
            case Result.Failure(ex: BrowserProtocolErrorException) =>
                assert(ex.getMessage.contains("code=-32000"), s"expected CDP code in message, got: ${ex.getMessage}")
                assert(
                    ex.getMessage.contains("Cannot find context with specified id"),
                    s"expected CDP message preserved, got: ${ex.getMessage}"
                )
            case other => fail(s"Expected typed CDP-error failure but got $other")
        }
    }

    "decodeEvalEnvelope - wire with neither result nor error surfaces a typed protocol error" in run {
        // CdpReply has both `result` and `error` as Maybe[…] defaulted to Absent, so a wire missing
        // both decodes successfully into an empty envelope. The decoder must still flag it.
        val json = """{"id":1}"""
        Abort.run[BrowserReadException](CdpEvalEnvelope.decodeEvalEnvelope(json, "eval")(_ => "should-not-reach")).map {
            case Result.Failure(ex: BrowserProtocolErrorException) =>
                assert(
                    ex.getMessage.contains("reply has neither result nor error"),
                    s"expected neither-result-nor-error message, got: ${ex.getMessage}"
                )
            case other => fail(s"Expected protocol error for empty envelope but got $other")
        }
    }

    "decodeEvalEnvelope - genuinely malformed JSON surfaces wire-decode failure" in run {
        val json = """not json at all"""
        Abort.run[BrowserReadException](CdpEvalEnvelope.decodeEvalEnvelope(json, "eval")(_ => "should-not-reach")).map {
            case Result.Failure(ex: BrowserProtocolErrorException) =>
                assert(
                    ex.getMessage.contains("wire decode failed"),
                    s"expected wire-decode-failed message for malformed JSON, got: ${ex.getMessage}"
                )
            case other => fail(s"Expected wire-decode failure but got $other")
        }
    }

    "decodeEvalEnvelope - valid EvalResult still routes to onValue" in run {
        val json = replyOk("""{"result":{"type":"string","value":"ok"}}""")
        Abort.run[BrowserReadException] {
            CdpEvalEnvelope.decodeEvalEnvelope(json, "eval") { env =>
                env.result match
                    case s: RemoteObject.`string` => s.value
                    case other                    => fail(s"expected RemoteObject.string but got $other")
            }
        }.map {
            case Result.Success(s) => assert(s == "ok")
            case other             => fail(s"Expected Success(\"ok\") but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // parseAndExtractEvalValue: integer-valued Double round-trip via Long.toString
    // (CdpWire.scala:163-169). Schema decodes CDP's integer literal `42` as Double 42.0;
    // the extractor must emit "42" (not "42.0") so callers see the original CDP literal.
    // -------------------------------------------------------------------------

    "parseAndExtractEvalValue - integer-valued double round-trips through Long.toString (\"42\", not \"42.0\")" in run {
        val json = replyOk("""{"result":{"type":"number","value":42}}""")
        Abort.run[BrowserReadException](CdpEvalDecoder.parseAndExtractEvalValue(json)).map {
            case Result.Success(s) =>
                assert(s == "42", s"expected '42' (Long.toString round-trip) but got '$s'")
                assert(!s.contains("."), s"expected no decimal point on integer-valued double but got '$s'")
            case other => fail(s"Expected Success(\"42\") but got $other")
        }
    }

    "parseAndExtractEvalValue - non-integer double survives via Double.toString (\"3.14\")" in run {
        val json = replyOk("""{"result":{"type":"number","value":3.14}}""")
        Abort.run[BrowserReadException](CdpEvalDecoder.parseAndExtractEvalValue(json)).map {
            case Result.Success(s) =>
                assert(s == "3.14", s"expected '3.14' (Double.toString) but got '$s'")
            case other => fail(s"Expected Success(\"3.14\") but got $other")
        }
    }

    "parseAndExtractEvalValue - integer-valued double with negative sign round-trips (\"-7\", not \"-7.0\")" in run {
        val json = replyOk("""{"result":{"type":"number","value":-7}}""")
        Abort.run[BrowserReadException](CdpEvalDecoder.parseAndExtractEvalValue(json)).map {
            case Result.Success(s) =>
                assert(s == "-7", s"expected '-7' but got '$s'")
            case other => fail(s"Expected Success(\"-7\") but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // decodeStringListReply: bad-JSON path. Empty input is "no matches"; non-empty input
    // that fails Schema-decode as Seq[String] must warn and Abort.fail with a typed
    // BrowserProtocolErrorException.decodeFailure (CdpWire.scala:186-194).
    // -------------------------------------------------------------------------

    "decodeStringListReply - empty input is the no-match sentinel (Chunk.empty, no abort)" in run {
        Abort.run[BrowserReadException](CdpEvalDecoder.decodeStringListReply("textAll", "")).map {
            case Result.Success(chunk) =>
                assert(chunk.isEmpty, s"expected empty Chunk for empty input but got $chunk")
            case other => fail(s"Expected Success(Chunk.empty) for empty input but got $other")
        }
    }

    "decodeStringListReply - well-formed JSON array of strings decodes verbatim" in run {
        val json = """["alpha","beta","gamma"]"""
        Abort.run[BrowserReadException](CdpEvalDecoder.decodeStringListReply("textAll", json)).map {
            case Result.Success(chunk) =>
                assert(chunk == Chunk("alpha", "beta", "gamma"), s"expected [alpha,beta,gamma] but got $chunk")
            case other => fail(s"Expected Success(Chunk(alpha,beta,gamma)) but got $other")
        }
    }

    "decodeStringListReply - malformed JSON warns + aborts with BrowserProtocolErrorException carrying the label" in run {
        val json = "this is definitely not a JSON array"
        Abort.run[BrowserReadException](CdpEvalDecoder.decodeStringListReply("textAll", json)).map {
            case Result.Failure(ex: BrowserProtocolErrorException) =>
                assert(ex.method == "textAll", s"expected method='textAll' but got '${ex.method}'")
                assert(
                    ex.error.startsWith("decode failed:"),
                    s"expected error startsWith 'decode failed:' but got '${ex.error}'"
                )
                assert(
                    ex.error.contains(json),
                    s"expected raw payload preserved in error message but got '${ex.error}'"
                )
            case other => fail(s"Expected Failure(BrowserProtocolErrorException) for bad JSON but got $other")
        }
    }

    "decodeStringListReply - JSON of the wrong shape (object, not array) aborts with BrowserProtocolErrorException" in run {
        val json = """{"not":"an array"}"""
        Abort.run[BrowserReadException](CdpEvalDecoder.decodeStringListReply("attributeAll", json)).map {
            case Result.Failure(ex: BrowserProtocolErrorException) =>
                assert(ex.method == "attributeAll", s"expected method='attributeAll' but got '${ex.method}'")
                assert(
                    ex.error.startsWith("decode failed:"),
                    s"expected error startsWith 'decode failed:' but got '${ex.error}'"
                )
            case other => fail(s"Expected Failure(BrowserProtocolErrorException) for wrong-shape JSON but got $other")
        }
    }

end CdpEvalDecoderTest
