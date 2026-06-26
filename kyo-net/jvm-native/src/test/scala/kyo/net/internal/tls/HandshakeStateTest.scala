package kyo.net.internal.tls

import kyo.*
import kyo.ffi.Buffer
import kyo.net.Test

/** Behavioral tests for [[HandshakeState]] and [[HandshakeState.fromCode]].
  *
  * Each test uses a stub [[TlsEngine]] that returns a preset `handshakeStep` code, then asserts that `HandshakeState.fromCode` maps it
  * to the correct typed state. The tests are behavioral rather than structural: they would fail if `fromCode` were removed or if its
  * code-to-state mapping were wrong, because `fromCode` is the function that converts the engine's raw integer result into the typed
  * state the driver's match on.
  *
  * The four codes from the `TlsEngine.handshakeStep` return convention (`1` done, `0` want-read, `-1` want-write, `-2` fatal error) are
  * all tested, along with an unknown code (treated as WantRead).
  *
  * A secondary set of tests drives the ADT's structural properties (equality, pattern matching on Failed) to pin the CanEqual contract
  * for the downstream call sites that match on `HandshakeState.Failed`.
  */
class HandshakeStateTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Minimal stub TlsEngine that returns a preset handshakeStep code.
      * All other methods are stubs: they are never called in these tests.
      */
    private def stubEngine(stepCode: Int): TlsEngine = new TlsEngine:
        def handshakeStep()(using AllowUnsafe): Int                              = stepCode
        def feedCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int  = 0
        def drainCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int = 0
        def readPlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int       = 0
        def writePlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int      = 0
        def hasBufferedPlaintext(using AllowUnsafe): Boolean                     = false
        def readBuffered()(using AllowUnsafe): Span[Byte]                        = Span.empty
        def certSha256()(using AllowUnsafe): Maybe[Span[Byte]]                   = Absent
        def shutdownStep()(using AllowUnsafe): Int                               = 0
        def free()(using AllowUnsafe): Unit                                      = ()
    end stubEngine

    "HandshakeState.fromCode" - {

        // Code 1: handshake complete. The engine signals Done and the driver attaches the session.
        "code 1 maps to Done" in {
            val engine = stubEngine(1)
            val state  = HandshakeState.fromCode(engine.handshakeStep())
            assert(state == HandshakeState.Done, s"code 1 must map to Done, got $state")
        }

        // Code 0: peer ciphertext needed before the next step.
        "code 0 maps to WantRead" in {
            val engine = stubEngine(0)
            val state  = HandshakeState.fromCode(engine.handshakeStep())
            assert(state == HandshakeState.WantRead, s"code 0 must map to WantRead, got $state")
        }

        // Code -1: outbound ciphertext queued; the driver drains and sends it, then re-steps.
        "code -1 maps to WantWrite" in {
            val engine = stubEngine(-1)
            val state  = HandshakeState.fromCode(engine.handshakeStep())
            assert(state == HandshakeState.WantWrite, s"code -1 must map to WantWrite, got $state")
        }

        // Code -2: fatal alert; the driver drains the alert record and reports EngineError.
        "code -2 maps to Failed(EngineError)" in {
            val engine = stubEngine(-2)
            val state  = HandshakeState.fromCode(engine.handshakeStep())
            assert(
                state == HandshakeState.Failed(HandshakeFailure.EngineError),
                s"code -2 must map to Failed(EngineError), got $state"
            )
        }

        // Unknown code: any value outside {1, 0, -1, -2} is treated as WantRead.
        "unknown code maps to WantRead" in {
            val engine = stubEngine(42)
            val state  = HandshakeState.fromCode(engine.handshakeStep())
            assert(state == HandshakeState.WantRead, s"unknown code 42 must map to WantRead, got $state")
        }

        // All four codes produce distinct states.
        "all four codes produce distinct states" in {
            val done      = HandshakeState.fromCode(1)
            val wantRead  = HandshakeState.fromCode(0)
            val wantWrite = HandshakeState.fromCode(-1)
            val failed    = HandshakeState.fromCode(-2)
            assert(done != wantRead, "Done must differ from WantRead")
            assert(done != wantWrite, "Done must differ from WantWrite")
            assert(done != failed, "Done must differ from Failed")
            assert(wantRead != wantWrite, "WantRead must differ from WantWrite")
            assert(wantRead != failed, "WantRead must differ from Failed")
            assert(wantWrite != failed, "WantWrite must differ from Failed")
        }

        // fromCode is stable: calling it twice with the same code returns equal states.
        "fromCode is stable: same code returns equal states across calls" in {
            @scala.annotation.tailrec
            def loop(i: Int, codes: List[Int]): Unit = codes match
                case Nil => ()
                case code :: rest =>
                    val s1 = HandshakeState.fromCode(code)
                    val s2 = HandshakeState.fromCode(code)
                    assert(s1 == s2, s"fromCode($code) must return equal states across calls, got $s1 and $s2")
                    loop(i + 1, rest)
            end loop
            loop(0, List(1, 0, -1, -2))
        }
    }

    "HandshakeState structural properties" - {

        // The WantRead/Done/Failed cycle through pattern matching as driveHandshake would.
        "pattern match on Done triggers correct arm" in {
            var arm = ""
            HandshakeState.fromCode(1) match
                case HandshakeState.Done      => arm = "done"
                case HandshakeState.Failed(_) => arm = "failed"
                case HandshakeState.WantWrite => arm = "want-write"
                case HandshakeState.WantRead  => arm = "want-read"
            end match
            assert(arm == "done", s"fromCode(1) must match Done, got arm=$arm")
        }

        "pattern match on Failed(EngineError) extracts typed reason" in {
            HandshakeState.fromCode(-2) match
                case HandshakeState.Failed(HandshakeFailure.EngineError) =>
                    succeed
                case other =>
                    fail(s"fromCode(-2) must match Failed(EngineError), got $other")
            end match
        }

        "Failed(EngineError) equality is reflexive" in {
            val a = HandshakeState.Failed(HandshakeFailure.EngineError)
            val b = HandshakeState.Failed(HandshakeFailure.EngineError)
            assert(a == b, "Failed(EngineError) must equal itself across two calls")
        }
    }

    "HandshakeState driveHandshake scenario properties" - {

        // DeadlineReaped is constructed at the deadline-reap site in armHandshakeDeadline and
        // wrapped in HandshakeState.Failed so the caller (logged as the typed failure). This leaf
        // pins that the value can be constructed and pattern-matched correctly.
        "deadline-reap scenario: Failed(DeadlineReaped) constructs and matches" in {
            val state = HandshakeState.Failed(HandshakeFailure.DeadlineReaped)
            state match
                case HandshakeState.Failed(HandshakeFailure.DeadlineReaped) =>
                    succeed
                case other =>
                    fail(s"Failed(DeadlineReaped) must match its own constructor, got $other")
            end match
        }

        // EngineThrew is constructed in driveHandshake's inner catch when engine.handshakeStep()
        // throws unexpectedly (e.g. SSLHandshakeException on a received fatal alert). The throwable
        // must survive the wrap so the onFailed callback can inspect or log the original cause.
        "engine-throw scenario: Failed(EngineThrew(t)) carries the throwable" in {
            val cause = new RuntimeException("ssl-fatal-alert: certificate_unknown")
            val state = HandshakeState.Failed(HandshakeFailure.EngineThrew(cause))
            state match
                case HandshakeState.Failed(HandshakeFailure.EngineThrew(t)) =>
                    assert(t eq cause, "EngineThrew must carry the original throwable by reference")
                    assert(t.getMessage == "ssl-fatal-alert: certificate_unknown")
                case other =>
                    fail(s"Failed(EngineThrew(cause)) must match, got $other")
            end match
        }

        // The three HandshakeFailure cases must all be distinct so a caller can distinguish which
        // failure path fired. DeadlineReaped and EngineError have no fields (singleton-like);
        // EngineThrew carries a throwable and is therefore structurally distinct.
        "all three HandshakeFailure cases are distinct" in {
            val engineError    = HandshakeState.Failed(HandshakeFailure.EngineError)
            val deadlineReaped = HandshakeState.Failed(HandshakeFailure.DeadlineReaped)
            val engineThrew    = HandshakeState.Failed(HandshakeFailure.EngineThrew(new RuntimeException("t")))
            assert(engineError != deadlineReaped, "EngineError must differ from DeadlineReaped")
            assert(engineError != engineThrew, "EngineError must differ from EngineThrew")
            assert(deadlineReaped != engineThrew, "DeadlineReaped must differ from EngineThrew")
        }

        // EngineThrew equality is by throwable reference, not message: two distinct throwables with
        // the same message must not be equal, so that callers can distinguish independent throws.
        "EngineThrew equality is by throwable reference" in {
            val t1 = new RuntimeException("same-message")
            val t2 = new RuntimeException("same-message")
            val a  = HandshakeState.Failed(HandshakeFailure.EngineThrew(t1))
            val b  = HandshakeState.Failed(HandshakeFailure.EngineThrew(t2))
            // EngineThrew derives CanEqual so the == comparison is checked; two different Throwable
            // instances are not equal even when their messages match (Throwable.equals is identity).
            assert(a != b, "Failed(EngineThrew(t1)) must not equal Failed(EngineThrew(t2)) when t1 ne t2")
            val c = HandshakeState.Failed(HandshakeFailure.EngineThrew(t1))
            assert(a == c, "Failed(EngineThrew(t1)) must equal itself (same throwable reference)")
        }
    }

end HandshakeStateTest
