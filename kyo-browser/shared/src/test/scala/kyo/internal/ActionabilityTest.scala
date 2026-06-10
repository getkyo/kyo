package kyo.internal

import CdpTypes.*
import kyo.*
import kyo.BrowserElementNotActionableException.Reason

class ActionabilityTest extends kyo.BaseBrowserTest:

    // A stable NodeRef used across all pure tests.
    private val dummyRef = NodeRef(42)

    // Helper to build a minimal ActionabilityValue with only the reason field set.
    private def reasonOnly(reason: String): ActionabilityValue =
        ActionabilityValue(actionable = false, reason = Present(reason))

    // `Actionability.parseResult` consumes the WHOLE CDP wire frame, not the inner `EvalResult` substring.
    // Tests express the typed `EvalResult` payload they care about; this helper wraps it in the surrounding
    // CdpReply envelope so the typed decoder sees the live dispatcher's shape.
    private def replyOk(evalResult: String): String = s"""{"id":1,"result":$evalResult}"""

    "decodeReason" - {

        "FillDesync returns FillDesync reason" in {
            Actionability.decodeReason("FillDesync", reasonOnly("FillDesync")).map { result =>
                assert(result == Reason.FillDesync)
            }
        }

        // Unknown top-level reason string is now a typed protocol error, not a silent NotAttached fallback.
        "unknown reason raises BrowserProtocolErrorException via Abort" in {
            Abort.run[BrowserReadException] {
                Actionability.decodeReason("UnknownNonsense", reasonOnly("UnknownNonsense"))
            }.map {
                case Result.Failure(ex: BrowserProtocolErrorException) => assert(ex.error.contains("unknown reason"))
                case other => fail(s"Expected Abort[BrowserProtocolErrorException] but got $other")
            }
        }

        "NotVisible with DisplayNone cause" in {
            val v = ActionabilityValue(actionable = false, reason = Present("NotVisible"), notVisibleCause = Present("DisplayNone"))
            Actionability.decodeReason("NotVisible", v).map { result =>
                assert(result == Reason.NotVisible(Reason.NotVisibleCause.DisplayNone))
            }
        }

        "NotVisible with VisibilityHidden cause" in {
            val v = ActionabilityValue(actionable = false, reason = Present("NotVisible"), notVisibleCause = Present("VisibilityHidden"))
            Actionability.decodeReason("NotVisible", v).map { result =>
                assert(result == Reason.NotVisible(Reason.NotVisibleCause.VisibilityHidden))
            }
        }

        // Unknown notVisibleCause is preserved as Other(raw) sentinel, not silently mapped to DisplayNone.
        "NotVisible with unknown cause returns Other sentinel" in {
            val v = ActionabilityValue(actionable = false, reason = Present("NotVisible"), notVisibleCause = Present("WeirdNewCause"))
            Actionability.decodeReason("NotVisible", v).map {
                case Reason.NotVisible(Reason.NotVisibleCause.Other(raw)) =>
                    assert(raw == "WeirdNewCause")
                case other => fail(s"expected NotVisible(Other(WeirdNewCause)) but got $other")
            }
        }

        "Disabled with AriaDisabled kind" in {
            val v = ActionabilityValue(actionable = false, reason = Present("Disabled"), disabledKind = Present("AriaDisabled"))
            Actionability.decodeReason("Disabled", v).map { result =>
                assert(result == Reason.Disabled(Reason.DisabledKind.AriaDisabled))
            }
        }

        // Unknown disabledKind is preserved as Other(raw) sentinel, not silently mapped to Attribute.
        "Disabled with unknown kind returns Other sentinel" in {
            val v = ActionabilityValue(actionable = false, reason = Present("Disabled"), disabledKind = Present("WeirdNewKind"))
            Actionability.decodeReason("Disabled", v).map {
                case Reason.Disabled(Reason.DisabledKind.Other(raw)) =>
                    assert(raw == "WeirdNewKind")
                case other => fail(s"expected Disabled(Other(WeirdNewKind)) but got $other")
            }
        }

        "NotFillable carries tagName" in {
            val v = ActionabilityValue(actionable = false, reason = Present("NotFillable"), tagName = Present("div"))
            Actionability.decodeReason("NotFillable", v).map {
                case Reason.NotFillable(tag) => assert(tag == "div")
                case other                   => fail(s"expected NotFillable but got $other")
            }
        }

        "OutsideHitTarget carries actualHit" in {
            val v = ActionabilityValue(actionable = false, reason = Present("OutsideHitTarget"), actualHit = Present("div#overlay"))
            Actionability.decodeReason("OutsideHitTarget", v).map {
                case Reason.OutsideHitTarget(hit) => assert(hit == "div#overlay")
                case other                        => fail(s"expected OutsideHitTarget but got $other")
            }
        }

        // NotInViewport with missing rect/viewportRect is a typed protocol error, not a silent NotAttached fallback.
        "NotInViewport with missing rect raises BrowserProtocolErrorException" in {
            val v = ActionabilityValue(actionable = false, reason = Present("NotInViewport"))
            Abort.run[BrowserReadException] {
                Actionability.decodeReason("NotInViewport", v)
            }.map {
                case Result.Failure(ex: BrowserProtocolErrorException) => assert(ex.method == "Actionability.NotInViewport")
                case other => fail(s"Expected Abort[BrowserProtocolErrorException] for partial NotInViewport but got $other")
            }
        }

    }

    "parseResult" - {

        "exceptionDetails Present returns failure" in {
            val raw = replyOk(
                """{"exceptionDetails":{"text":"error"},"result":{"type":"object","value":{"actionable":true,"navigatesOnClick":false,"rect":{"x":10,"y":20,"width":100,"height":50}}}}"""
            )
            Actionability.parseResult(raw, dummyRef).map { result =>
                assert(result == Result.Failure(Reason.NotAttached))
            }
        }

        // Malformed wire-shape surfaces typed `Abort[BrowserProtocolErrorException]`, NOT silent
        // retry-until-exhaustion. A structurally broken Actionability JSON envelope short-circuits the retry
        // loop instead of looping until `BrowserAssertionTimedOutException`.
        "malformed wire surfaces BrowserProtocolErrorException via Abort" in {
            val raw = "this is not json {{{"
            Abort.run[BrowserProtocolErrorException] {
                Actionability.parseResult(raw, dummyRef)
            }.map {
                case Result.Failure(ex: BrowserProtocolErrorException) => assert(ex.method == "Actionability")
                case other => fail(s"Expected typed Abort[BrowserProtocolErrorException] but got $other")
            }
        }

        "NotVisible wire shape decoded from JSON" in {
            val raw = replyOk(
                """{"result":{"value":{"actionable":false,"reason":"NotVisible","notVisibleCause":"DisplayNone"}}}"""
            )
            Actionability.parseResult(raw, dummyRef).map {
                case Result.Failure(Reason.NotVisible(Reason.NotVisibleCause.DisplayNone)) =>
                    ()
                case other => fail(s"expected NotVisible(DisplayNone) but got $other")
            }
        }

        "Disabled wire shape with AriaDisabled decoded from JSON" in {
            val raw = replyOk(
                """{"result":{"value":{"actionable":false,"reason":"Disabled","disabledKind":"AriaDisabled"}}}"""
            )
            Actionability.parseResult(raw, dummyRef).map {
                case Result.Failure(Reason.Disabled(Reason.DisabledKind.AriaDisabled)) =>
                    ()
                case other => fail(s"expected Disabled(AriaDisabled) but got $other")
            }
        }
    }

    "decodeValue" - {

        // actionable=true but rect absent → conservative NotAttached failure
        "actionable=true with missing rect returns NotAttached failure" in {
            // No `rect` field; drives the Result.Failure(NotAttached) branch in decodeValue.
            val value = ActionabilityValue(actionable = true, navigatesOnClick = false, rect = Absent, reason = Absent)
            Actionability.decodeValue(value, dummyRef).map { result =>
                assert(result == Result.Failure(Reason.NotAttached))
            }
        }
    }

    "navigatesOnClick" - {

        "true value is propagated into ActionableRef" in {
            val raw = replyOk(
                """{"result":{"value":{"actionable":true,"navigatesOnClick":true,"rect":{"x":10,"y":20,"width":100,"height":50}}}}"""
            )
            Actionability.parseResult(raw, dummyRef).map {
                case Result.Success(ref) =>
                    assert(ref.navigatesOnClick, "expected navigatesOnClick=true")
                    assert(ref.x == 10)
                    assert(ref.y == 20)
                case other => fail(s"expected Success, got $other")
            }
        }

        "false value is propagated into ActionableRef" in {
            val raw = replyOk(
                """{"result":{"value":{"actionable":true,"navigatesOnClick":false,"rect":{"x":5,"y":6,"width":80,"height":30}}}}"""
            )
            Actionability.parseResult(raw, dummyRef).map {
                case Result.Success(ref) =>
                    assert(!ref.navigatesOnClick, "expected navigatesOnClick=false")
                case other => fail(s"expected Success, got $other")
            }
        }
    }

end ActionabilityTest
