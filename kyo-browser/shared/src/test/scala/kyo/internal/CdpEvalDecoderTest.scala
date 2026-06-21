package kyo.internal

import kyo.*

/** Pure unit tests for [[CdpEvalDecoder]] post-processing helpers.
  *
  * All scenarios are pure (no browser, no I/O). The helpers under test project the typed CDP `Runtime.evaluate` [[EvalResult]] (the engine
  * decodes the reply through `Schema[EvalResult]`) and decode the `Seq[String]` reply emitted by the list probes.
  */
class CdpEvalDecoderTest extends kyo.BaseBrowserTest:

    // -------------------------------------------------------------------------
    // extractValueOrFail / extractEvalValue: per-variant value projection
    // -------------------------------------------------------------------------

    "extractValueOrFail - string value" in {
        val env = EvalResult(result = RemoteObject.`string`(value = "foo"))
        Abort.run[BrowserConnectionException](CdpEvalDecoder.extractValueOrFail(env)).map {
            case Result.Success(s) => assert(s == "foo")
            case other             => fail(s"Expected Success(\"foo\") but got $other")
        }
    }

    "extractValueOrFail - number value" in {
        val env = EvalResult(result = RemoteObject.`number`(value = 42, description = Present("42")))
        Abort.run[BrowserConnectionException](CdpEvalDecoder.extractValueOrFail(env)).map {
            case Result.Success(s) => assert(s == "42")
            case other             => fail(s"Expected Success(\"42\") but got $other")
        }
    }

    "extractValueOrFail - undefined yields empty string" in {
        val env = EvalResult(result = RemoteObject.`undefined`())
        Abort.run[BrowserConnectionException](CdpEvalDecoder.extractValueOrFail(env)).map {
            case Result.Success(s) => assert(s == "")
            case other             => fail(s"Expected Success(\"\") but got $other")
        }
    }

    "extractValueOrFail - exceptionDetails surfaces a typed protocol error, never an empty string" in {
        val env = EvalResult(
            result = RemoteObject.`undefined`(),
            exceptionDetails = Present(ExceptionDetails(
                text = Present("Uncaught"),
                // CDP returns a thrown error as a type=object RemoteObject whose `description` carries the
                // message; ExceptionDetailsFormat reads `description`, matching the real Runtime.evaluate wire.
                exception =
                    Present(RemoteObject.`object`(subtype = Present("error"), description = Present("ReferenceError: x is not defined")))
            ))
        )
        Abort.run[BrowserReadException](CdpEvalDecoder.extractValueOrFail(env)).map {
            case Result.Failure(ex: BrowserProtocolErrorException) =>
                assert(ex.getMessage.contains("ReferenceError"), s"expected the exception description preserved, got: ${ex.getMessage}")
            case other => fail(s"Expected typed protocol-error failure for exceptionDetails but got $other")
        }
    }

    "extractEvalValue - string Schema decodes \\n, \\\", and unicode escapes back to the source characters" in {
        // The RemoteObject string Schema re-parses the captured raw JSON literal, so escapes round-trip to source chars.
        Abort.run[BrowserConnectionException](CdpEvalDecoder.extractEvalValue(EvalResult(RemoteObject.`string`(value =
            "hello\nworld"
        )))).map {
            case Result.Success(s) => assert(s == "hello\nworld")
            case other             => fail(s"Case 1: expected hello<newline>world but got $other")
        }.andThen {
            Abort.run[BrowserConnectionException](CdpEvalDecoder.extractEvalValue(EvalResult(RemoteObject.`string`(value = "a\"b")))).map {
                case Result.Success(s) => assert(s == "a\"b")
                case other             => fail(s"Case 2: expected a\"b but got $other")
            }
        }.andThen {
            Abort.run[BrowserConnectionException](CdpEvalDecoder.extractEvalValue(EvalResult(RemoteObject.`string`(value = "A")))).map {
                case Result.Success(s) => assert(s == "A")
                case other             => fail(s"Case 3: expected A but got $other")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Integer-valued Double round-trip via Long.toString. Schema decodes CDP's integer
    // literal `42` as Double 42.0; the extractor must emit "42" (not "42.0") so callers
    // see the original CDP literal.
    // -------------------------------------------------------------------------

    "extractEvalValue - integer-valued double round-trips through Long.toString (\"42\", not \"42.0\")" in {
        Abort.run[BrowserReadException](CdpEvalDecoder.extractEvalValue(EvalResult(RemoteObject.`number`(value = 42)))).map {
            case Result.Success(s) =>
                assert(s == "42", s"expected '42' (Long.toString round-trip) but got '$s'")
                assert(!s.contains("."), s"expected no decimal point on integer-valued double but got '$s'")
            case other => fail(s"Expected Success(\"42\") but got $other")
        }
    }

    "extractEvalValue - non-integer double survives via Double.toString (\"3.14\")" in {
        Abort.run[BrowserReadException](CdpEvalDecoder.extractEvalValue(EvalResult(RemoteObject.`number`(value = 3.14)))).map {
            case Result.Success(s) =>
                assert(s == "3.14", s"expected '3.14' (Double.toString) but got '$s'")
            case other => fail(s"Expected Success(\"3.14\") but got $other")
        }
    }

    "extractEvalValue - integer-valued double with negative sign round-trips (\"-7\", not \"-7.0\")" in {
        Abort.run[BrowserReadException](CdpEvalDecoder.extractEvalValue(EvalResult(RemoteObject.`number`(value = -7)))).map {
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

    "decodeStringListReply - empty input is the no-match sentinel (Chunk.empty, no abort)" in {
        Abort.run[BrowserReadException](CdpEvalDecoder.decodeStringListReply("textAll", "")).map {
            case Result.Success(chunk) =>
                assert(chunk.isEmpty, s"expected empty Chunk for empty input but got $chunk")
            case other => fail(s"Expected Success(Chunk.empty) for empty input but got $other")
        }
    }

    "decodeStringListReply - well-formed JSON array of strings decodes verbatim" in {
        val json = """["alpha","beta","gamma"]"""
        Abort.run[BrowserReadException](CdpEvalDecoder.decodeStringListReply("textAll", json)).map {
            case Result.Success(chunk) =>
                assert(chunk == Chunk("alpha", "beta", "gamma"), s"expected [alpha,beta,gamma] but got $chunk")
            case other => fail(s"Expected Success(Chunk(alpha,beta,gamma)) but got $other")
        }
    }

    "decodeStringListReply - malformed JSON warns + aborts with BrowserProtocolErrorException carrying the label" in {
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

    "decodeStringListReply - JSON of the wrong shape (object, not array) aborts with BrowserProtocolErrorException" in {
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
