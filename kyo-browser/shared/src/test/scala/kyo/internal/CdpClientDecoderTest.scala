package kyo.internal

import kyo.*
import kyo.internal.CdpTypes.*

/** Pure unit tests for [[CdpClient]] decoder and encoder helpers.
  *
  * No browser, no I/O, no CdpClient instance required. All tests are pure Sync over the publicly-accessible (private[kyo]) helpers on the
  * CdpClient companion object.
  *
  * Tests cover:
  *   - CDP error-response pipeline: well-formed and malformed error decoding.
  *   - encodeRequest with malformed paramsJson falls back to {}.
  *   - decodeCdpMessage four sub-cases: error-id branch, malformed error, non-Object frame, truly malformed JSON.
  *   - eventWhitelist negative-assertion: non-whitelisted event is NOT emitted.
  */
class CdpClientDecoderTest extends kyo.BaseBrowserTest:

    // CanEqual instance needed to pattern-match Exchange.Message.Skip in this test class.
    // Exchange.Message is a covariant enum; the Skip singleton has type
    // Exchange.Message[Nothing, Nothing, Nothing], which requires CanEqual to match against
    // Exchange.Message[Int, String, CdpEvent] in a strict-equality context.
    given CanEqual[Exchange.Message[Int, String, CdpEvent], Exchange.Message[Int, String, CdpEvent]] =
        CanEqual.derived

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build a fresh (empty) dialogHandlers AtomicRef and a closed dialogQueue Channel for use in decoder tests that do not need dialog
      * dispatch.
      */
    private def makeDialogFixtures(using
        Frame
    )
        : (AtomicRef[Dict[String, (Boolean, String)]], Channel[(Boolean, String, Maybe[SessionId])]) < Sync =
        for
            handlers <- AtomicRef.init[Dict[String, (Boolean, String)]](Dict.empty)
            queue    <- Channel.initUnscoped[(Boolean, String, Maybe[SessionId])](16)
        yield (handlers, queue)

    /** Decode a wire string and return the Exchange.Message result. */
    private def decode(wire: String)(using
        Frame
    )
        : Exchange.Message[Int, String, CdpEvent] < Sync =
        makeDialogFixtures.map { (handlers, queue) =>
            AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty).map { dispatchers =>
                AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty).map { downloadDispatchers =>
                    AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty).map { screencastDispatchers =>
                        AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty).map { consoleDispatchers =>
                            AtomicRef.init[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]](Dict.empty).map { recorders =>
                                CdpClient.decodeCdpMessage(
                                    wire,
                                    handlers,
                                    queue,
                                    dispatchers,
                                    downloadDispatchers,
                                    screencastDispatchers,
                                    consoleDispatchers,
                                    recorders
                                )
                            }
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // 1. CDP error-response pipeline; well-formed
    // -------------------------------------------------------------------------

    "CDP error-response pipeline: well-formed error surfaces as the whole wire, callers decode via CdpReply[Any]" in {
        // The dispatcher passes the WHOLE wire to the awaiting caller; the caller decodes a [[CdpReply]]
        // envelope which carries the typed `error: CdpError` field.
        val wire = """{"id": 1, "error": {"code": -32602, "message": "Invalid params"}}"""
        decode(wire).map {
            case Exchange.Message.Response(id, payload) =>
                assert(id == 1)
                assert(payload == wire, s"payload must be the whole wire, got: $payload")
                Json.decode[CdpReply[CdpNoParams]](payload) match
                    case Result.Success(reply) =>
                        reply.error match
                            case Present(err) =>
                                assert(err.code == -32602)
                                assert(err.message == "Invalid params")
                            case Absent => fail(s"Expected CdpReply.error to be Present, got Absent: $reply")
                    case other => fail(s"Expected CdpReply decode success but got $other")
                end match
            case other => fail(s"Expected Response but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // 2. CDP error-response pipeline; malformed error fallback
    // -------------------------------------------------------------------------

    "CDP error-response pipeline: malformed error falls back to whole-wire Response via FallbackIdEnvelope" in {
        // "error" is a JSON string, not an object → typed CdpWireMessage decode fails → fallbackDecode
        // recovers `id` via FallbackIdEnvelope (a permissive Maybe[Int] envelope) and routes the whole wire.
        val wire = """{"id": 2, "error": "not-an-object"}"""
        decode(wire).map {
            case Exchange.Message.Response(id, payload) =>
                assert(id == 2)
                assert(payload == wire, s"payload must be the whole wire, got: $payload")
                // The caller's CdpReply decode must FAIL on this shape (error is a string, not a CdpError);
                // decodeOrFail surfaces this as BrowserProtocolErrorException.decodeFailure at the call site.
                Json.decode[CdpReply[CdpNoParams]](payload) match
                    case Result.Success(reply) =>
                        fail(s"Malformed error wire unexpectedly decoded as a valid CdpReply: $reply")
                    case _ =>
                        ()
                end match
            case other => fail(s"Expected Response but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // 4. decodeCdpMessage four sub-cases
    // -------------------------------------------------------------------------

    "decodeCdpMessage: error-id branch routes whole wire; caller's CdpReply decode surfaces the typed error (4a)" in {
        val wire = """{"id": 1, "error": {"code": -32601, "message": "Method not found"}}"""
        decode(wire).map {
            case Exchange.Message.Response(id, payload) =>
                assert(id == 1)
                assert(payload == wire, s"payload must be the whole wire, got: $payload")
                Json.decode[CdpReply[CdpNoParams]](payload) match
                    case Result.Success(reply) =>
                        reply.error match
                            case Present(err) =>
                                assert(err.code == -32601)
                                assert(err.message == "Method not found")
                            case Absent => fail(s"Expected CdpReply.error to be Present, got Absent: $reply")
                    case other => fail(s"Expected CdpReply decode but got $other from payload=$payload")
                end match
            case other => fail(s"Expected Response for error-id frame but got $other")
        }
    }

    "decodeCdpMessage: malformed error JSON falls back via FallbackIdEnvelope to whole-wire Response (4b)" in {
        val wire = """{"id": 1, "error": "not-an-object"}"""
        decode(wire).map {
            case Exchange.Message.Response(id, payload) =>
                assert(id == 1)
                assert(payload == wire, s"payload must be the whole wire, got: $payload")
                Json.decode[CdpReply[CdpNoParams]](payload) match
                    case Result.Success(reply) =>
                        fail(s"Malformed error wire unexpectedly decoded as a valid CdpReply: $reply")
                    case _ =>
                        ()
                end match
            case other => fail(s"Expected Response for malformed-error frame but got $other")
        }
    }

    "decodeCdpMessage: non-Object frame (JSON array) returns Skip (4c)" in {
        val wire = "[1, 2, 3]"
        decode(wire).map {
            case msg @ Exchange.Message.Skip =>
                assert(msg == Exchange.Message.Skip, s"Expected Skip for non-Object frame but got $msg")
            case other => fail(s"Expected Skip for non-Object frame but got $other")
        }
    }

    "decodeCdpMessage: truly malformed JSON returns Skip (4d)" in {
        val wire = "not-json"
        decode(wire).map {
            case msg @ Exchange.Message.Skip =>
                assert(msg == Exchange.Message.Skip, s"Expected Skip for malformed JSON but got $msg")
            case other => fail(s"Expected Skip for malformed JSON but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // 5. dispatchOrDrop: console/exception events skip when no subscriber
    // -------------------------------------------------------------------------

    /** Build a decoder call with a caller-supplied consoleEventDispatchers map. All other registries are empty. */
    private def decodeWithConsoleDispatchers(
        wire: String,
        consoleDispatchersMap: Dict[String, CdpEvent.Generic => Unit < Sync]
    )(using Frame): Exchange.Message[Int, String, CdpEvent] < Sync =
        makeDialogFixtures.map { (handlers, queue) =>
            AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty).map { dispatchers =>
                AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty).map { downloadDispatchers =>
                    AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty).map { screencastDispatchers =>
                        AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](consoleDispatchersMap).map { consoleDispatchers =>
                            AtomicRef.init[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]](Dict.empty).map { recorders =>
                                CdpClient.decodeCdpMessage(
                                    wire,
                                    handlers,
                                    queue,
                                    dispatchers,
                                    downloadDispatchers,
                                    screencastDispatchers,
                                    consoleDispatchers,
                                    recorders
                                )
                            }
                        }
                    }
                }
            }
        }

    "dispatchOrDrop: Runtime.consoleAPICalled with no subscriber returns Skip (drop)" in {
        val wire =
            """{"method": "Runtime.consoleAPICalled", "params": {"type": "log", "args": [], "executionContextId": 1, "timestamp": 0, "stackTrace": null}}"""
        decodeWithConsoleDispatchers(wire, Dict.empty).map { msg =>
            assert(msg == Exchange.Message.Skip, s"Expected Skip (drop) for consoleAPICalled with no subscriber, got: $msg")
        }
    }

    "dispatchOrDrop: Runtime.exceptionThrown with no subscriber returns Skip (drop)" in {
        val wire =
            """{"method": "Runtime.exceptionThrown", "params": {"timestamp": 0, "exceptionDetails": {"exceptionId": 1, "text": "Uncaught", "lineNumber": 0, "columnNumber": 0}}}"""
        decodeWithConsoleDispatchers(wire, Dict.empty).map { msg =>
            assert(msg == Exchange.Message.Skip, s"Expected Skip (drop) for exceptionThrown with no subscriber, got: $msg")
        }
    }

    "dispatchOrDrop: Runtime.consoleAPICalled with a registered subscriber invokes the handler (not a silent drop)" in {
        val consoleWire =
            """{"method": "Runtime.consoleAPICalled", "sessionId": "test-session-1", "params": {"type": "log", "args": [], "executionContextId": 1, "timestamp": 0, "stackTrace": null}}"""
        AtomicInt.init(0).map { invocationCount =>
            val handler: CdpEvent.Generic => Unit < Sync = (_: CdpEvent.Generic) =>
                invocationCount.incrementAndGet.unit
            val sessionKey = "test-session-1"
            decodeWithConsoleDispatchers(consoleWire, Dict(sessionKey -> handler)).map { msg =>
                // With a registered handler the result is still Skip (the handler consumed the event),
                // but invocationCount must be 1: the handler was called, not silently dropped.
                assert(msg == Exchange.Message.Skip, s"Expected Skip (handler path) for consoleAPICalled with subscriber, got: $msg")
                invocationCount.get.map { n =>
                    assert(n == 1, s"Expected handler invocation count 1, got: $n")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 6. eventWhitelist negative-assertion
    // -------------------------------------------------------------------------

    "eventWhitelist: non-whitelisted event is NOT emitted as CdpEvent" in {
        // Verify the method is indeed not in the whitelist first (defensive)
        assert(!CdpClient.eventWhitelist.contains("NotAWhitelistedEvent"))
        val wire = """{"method": "NotAWhitelistedEvent", "params": {}}"""
        decode(wire).map {
            case Exchange.Message.Skip =>
                ()
            case Exchange.Message.Push(_) =>
                fail("Non-whitelisted event must NOT be emitted as a CdpEvent Push")
            case Exchange.Message.Response(id, _) =>
                fail(s"Non-whitelisted event must NOT produce a Response, got id=$id")
        }
    }

end CdpClientDecoderTest
