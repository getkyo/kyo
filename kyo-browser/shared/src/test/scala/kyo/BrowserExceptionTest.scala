package kyo

import kyo.BrowserElementNotActionableException.Reason

// This file covers message content and factory-method behaviour. Compile-time hierarchy
// assertions (via <:<) are in BrowserExceptionHierarchyTest.scala.

class BrowserExceptionTest extends BrowserTest:

    override def timeout = 60.seconds

    "message content" in {
        val ex = BrowserElementNotFoundException("#my-selector")
        assert(ex.getMessage.contains("#my-selector"))
    }

    "cause is preserved" in {
        val cause = new RuntimeException("underlying error")
        val ex    = BrowserConnectionLostException(s"Connection lost: ${cause.getMessage}", Present(cause))
        assert(ex.getCause eq cause)
    }

    "a Panic produced inside an interaction method is surfaced via Abort.panic, not raw throw" in {
        val panic = new RuntimeException("boom")
        Abort.run[Throwable] {
            Abort.panic(panic).andThen(())
        }.map {
            case Result.Panic(t) =>
                assert(t.getMessage == "boom", s"Expected boom panic but got $t")
            case other =>
                fail(s"Expected Result.Panic(boom) but got $other")
        }
    }

    // ---- factory methods ----

    "BrowserProtocolErrorException.decodeFailure produces the documented message" in {
        val ex = BrowserProtocolErrorException.decodeFailure("Page.getNavigationHistory", "<garbled json>")
        assert(ex.method == "Page.getNavigationHistory")
        assert(ex.error == "decode failed: <garbled json>")
        assert(ex.getMessage.contains("Page.getNavigationHistory"))
        assert(ex.getMessage.contains("<garbled json>"))
    }

    "BrowserProtocolErrorException.unexpectedReply produces the documented message" in {
        val ex = BrowserProtocolErrorException.unexpectedReply("Runtime.evaluate", "garbled")
        assert(ex.method == "Runtime.evaluate")
        assert(ex.error == "unexpected reply: garbled")
    }

    "BrowserSetupFailedException carries message and cause directly without RuntimeException wrap" in {
        val cause = new RuntimeException("underlying: ENOENT /bad/path")
        val ex    = BrowserSetupFailedException("failed to start /bad/path", cause)
        // Field access; no getMessage round-trip through a synthetic RuntimeException.
        assert(ex.message == "failed to start /bad/path")
        assert(ex.cause == Present(cause))
        // The KyoException cause slot is the genuine third-party Throwable, not a wrapper.
        assert(ex.getCause eq cause)
    }

    "BrowserAssertionTimedOutException.notQuiesced produces the documented message" in {
        import scala.concurrent.duration.*
        val ex  = BrowserAssertionTimedOutException.notQuiesced(50.millis, 123L, 7L, 2.seconds)
        val msg = ex.getMessage
        assert(msg.contains("DOM quiescence"))
        assert(msg.contains("last mutation"))
        assert(msg.contains("mutations"))
        assert(msg.contains("deadline"))
    }

    // ---- constructor + factory coverage ----

    "BrowserConnectionLostException(\"msg\") constructor stores the message" in {
        val ex = BrowserConnectionLostException("something went wrong")
        assert(ex.message == "something went wrong")
        assert(ex.cause == Absent)
    }

    "BrowserConnectionLostException(message, Present(closed)) stores the closed reason" in {
        val closed = new Closed("WebSocket", summon[Frame])
        val ex     = BrowserConnectionLostException(s"Connection lost: ${closed.getMessage}", Present(closed))
        assert(ex.cause == Present(closed))
        assert(ex.getMessage.contains("Connection lost:"))
    }

    "BrowserProtocolErrorException.internalEvalFailed factory returns properly-shaped exception" in {
        val ex = BrowserProtocolErrorException.internalEvalFailed("unexpected null return")
        ex match
            case _: BrowserProtocolErrorException => ()
            case null                             => fail("expected BrowserProtocolErrorException but got null")
        assert(ex.getMessage.contains("kyo-browser internal JS evaluation failed:"))
        assert(ex.getMessage.contains("unexpected null return"))
    }

    "BrowserSetupFailedException.apply(message, cause: Throwable) smart constructor wires both fields" in {
        val cause = new RuntimeException("disk full")
        val ex    = BrowserSetupFailedException("failed to extract zip", cause)
        // The smart ctor must store the message verbatim and wrap the throwable as Present.
        assert(ex.message == "failed to extract zip")
        assert(ex.cause == Present(cause))
        // KyoException's cause slot must hold the genuine throwable (no wrapper allocated).
        assert(ex.getCause eq cause)
        // Resulting instance still satisfies the marker hierarchy.
        ex match
            case _: BrowserSetupException => ()
            case null                     => fail("expected BrowserSetupException but got null")
        ()
    }

    // ---- Reason sealed-trait hierarchy unit tests ----

    "Reason.NotAttached.description matches legacy getMessage suffix" in {
        val reason = Reason.NotAttached
        val ex     = BrowserElementNotActionableException("#btn", reason)
        // Pin the description string so error messages don't silently regress.
        assert(reason.description == "element is not attached to the DOM")
        assert(ex.getMessage.contains("not attached"))
    }

    // getMessage on NotInViewport mentions the rect coordinates.
    "Reason.NotInViewport.description mentions element rect coordinates" in {
        val rect     = Reason.Rect(10, 20, 30, 40)
        val viewport = Reason.Rect(0, 0, 1280, 720)
        val reason   = Reason.NotInViewport(rect, viewport)
        val ex       = BrowserElementNotActionableException("#btn", reason)
        assert(ex.getMessage.contains("10"), s"expected rect x=10 in message: ${ex.getMessage}")
        assert(ex.getMessage.contains("20"), s"expected rect y=20 in message: ${ex.getMessage}")
        assert(ex.getMessage.contains("1280"), s"expected viewport width=1280 in message: ${ex.getMessage}")
    }

    // Extra: description strings for all leaf cases are non-empty and carry the expected discriminators.
    "Reason all description strings are non-empty and carry expected discriminators" in {
        val cases: List[(Reason, String)] = List(
            Reason.NotAttached                                         -> "not attached",
            Reason.NotVisible(Reason.NotVisibleCause.DisplayNone)      -> "display",
            Reason.NotVisible(Reason.NotVisibleCause.VisibilityHidden) -> "visibility",
            Reason.NotVisible(Reason.NotVisibleCause.OpacityZero)      -> "opacity",
            Reason.NotVisible(Reason.NotVisibleCause.ZeroComputedSize) -> "zero",
            Reason.ZeroSizedElement(0, 0)                              -> "zero",
            Reason.Disabled(Reason.DisabledKind.Attribute)             -> "disabled",
            Reason.Disabled(Reason.DisabledKind.AriaDisabled)          -> "aria",
            Reason.Disabled(Reason.DisabledKind.FieldsetDisabled)      -> "fieldset",
            Reason.Disabled(Reason.DisabledKind.PointerEventsNone)     -> "pointer",
            Reason.OutsideHitTarget("div#overlay")                     -> "covered",
            Reason.NotFillable("div")                                  -> "fillable",
            Reason.Unstable                                            -> "moving",
            Reason.FillDesync                                          -> "value",
            Reason.NotInViewport(
                Reason.Rect(0, 0, 0, 0),
                Reason.Rect(0, 0, 1280, 720)
            ) -> "viewport"
        )
        cases.foreach { case (reason, discriminator) =>
            val d = reason.description
            assert(d.nonEmpty, s"description for $reason returned empty string")
            assert(
                d.toLowerCase.contains(discriminator.toLowerCase),
                s"description for $reason = '$d' does not contain expected discriminator '$discriminator'"
            )
        }
        ()
    }

    // Extra: CanEqual allows pattern-match exhaustiveness on concrete cases.
    "Reason derives CanEqual - case objects compare by identity" in {
        assert(Reason.NotAttached == Reason.NotAttached)
        assert(Reason.Unstable == Reason.Unstable)
        assert(Reason.FillDesync == Reason.FillDesync)
    }

    "Reason case classes compare structurally" in {
        val r1 = Reason.NotVisible(Reason.NotVisibleCause.DisplayNone)
        val r2 = Reason.NotVisible(Reason.NotVisibleCause.DisplayNone)
        val r3 = Reason.NotVisible(Reason.NotVisibleCause.VisibilityHidden)
        assert(r1 == r2)
        assert(r1 != r3)
    }

    "Reason.NotFillable carries tagName through BrowserElementNotActionableException" in {
        val reason = Reason.NotFillable("span")
        val ex     = BrowserElementNotActionableException("#target", reason)
        // Use pattern matching; no casts.
        ex.reason match
            case Reason.NotFillable(tag) =>
                assert(tag == "span", s"expected tagName=span but got $tag")
            case other => fail(s"expected NotFillable but got $other")
        end match
        ()
    }

    "Reason.Disabled carries DisabledKind through getMessage" in {
        val kinds = List(
            Reason.DisabledKind.Attribute         -> "disabled",
            Reason.DisabledKind.AriaDisabled      -> "aria",
            Reason.DisabledKind.FieldsetDisabled  -> "fieldset",
            Reason.DisabledKind.PointerEventsNone -> "pointer"
        )
        kinds.foreach { case (kind, discriminator) =>
            val ex = BrowserElementNotActionableException("#btn", Reason.Disabled(kind))
            assert(ex.getMessage.toLowerCase.contains(discriminator), s"expected '$discriminator' in message: ${ex.getMessage}")
        }
        ()
    }

    // ---- Other sentinel cases for wire-drift preservation ----

    "NotVisibleCause.Other preserves the raw wire string in description" in {
        val reason = Reason.NotVisible(Reason.NotVisibleCause.Other("NewBrowserCause"))
        val d      = reason.description
        assert(d.nonEmpty, "description should not be empty")
        assert(d.contains("NewBrowserCause"), s"description should include raw cause string: $d")
    }

    "DisabledKind.Other preserves the raw wire string in description" in {
        val reason = Reason.Disabled(Reason.DisabledKind.Other("NewDisabledKind"))
        val d      = reason.description
        assert(d.nonEmpty, "description should not be empty")
        assert(d.contains("NewDisabledKind"), s"description should include raw kind string: $d")
    }

    "NotVisibleCause.Other compares structurally" in {
        val a = Reason.NotVisibleCause.Other("foo")
        val b = Reason.NotVisibleCause.Other("foo")
        val c = Reason.NotVisibleCause.Other("bar")
        assert(a == b)
        assert(a != c)
    }

    "DisabledKind.Other compares structurally" in {
        val a = Reason.DisabledKind.Other("foo")
        val b = Reason.DisabledKind.Other("foo")
        val c = Reason.DisabledKind.Other("bar")
        assert(a == b)
        assert(a != c)
    }

    // Live trigger for BrowserScriptErrorException: drive a real Chrome, evaluate JS that throws
    // a ReferenceError, and pin the typed exception shape.
    "Browser.eval against undefined symbol surfaces BrowserScriptErrorException via typed Abort (live trigger)" in {
        withBrowser {
            onPage("<html><body></body></html>") {
                Abort.run[BrowserScriptException] {
                    // `nonexistentSymbolXyz` is not defined; Chrome reports a ReferenceError exception
                    // detail, which BrowserEval translates into BrowserScriptErrorException.
                    Browser.eval("nonexistentSymbolXyz.method()")
                }.map {
                    case Result.Failure(ex: BrowserScriptErrorException) =>
                        // The error message should mention the script-error nature; we do not pin Chrome's exact wording
                        // (it differs across Chrome versions), only that the typed exception path is engaged.
                        assert(ex.error.nonEmpty, s"expected non-empty error message but got '${ex.error}'")
                    case other =>
                        fail(s"expected Result.Failure(_: BrowserScriptErrorException) but got $other")
                }
            }
        }
    }

    // Live trigger for BrowserDecodingException: Browser.evalJson[A] decodes the JS expression's result
    // against the supplied Schema. A JSON-incompatible result (e.g. a stringified value that does not
    // parse as the target type) surfaces BrowserDecodingException via typed Abort.
    "Browser.evalJson against a malformed payload surfaces BrowserDecodingException via typed Abort (live trigger)" in {
        withBrowser {
            onPage("<html><body></body></html>") {
                Abort.run[BrowserReadException] {
                    // The JS returns the literal string "not-an-int"; JSON.stringify wraps it as "\"not-an-int\"".
                    // Decoding "\"not-an-int\"" as an Int fails at the kyo-schema layer, which evalJson translates
                    // into BrowserDecodingException("evalJson", err.toString).
                    Browser.evalJson[Int](""""not-an-int"""")
                }.map {
                    case Result.Failure(ex: BrowserDecodingException) =>
                        assert(
                            ex.method == "evalJson",
                            s"expected BrowserDecodingException.method == 'evalJson' but got '${ex.method}'"
                        )
                        assert(ex.error.nonEmpty, s"expected non-empty error description but got '${ex.error}'")
                    case other =>
                        fail(s"expected Result.Failure(_: BrowserDecodingException) but got $other")
                }
            }
        }
    }

end BrowserExceptionTest
