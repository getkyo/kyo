package kyo.internal

import CdpTypes.*
import kyo.*
import kyo.BrowserElementNotActionableException.Reason
import kyo.JsonRpcIdStrategy

class ActionabilityTest extends kyo.BaseBrowserTest:

    // A stable NodeRef used across all pure tests.
    private val dummyRef = NodeRef(42)

    // Helper to build a minimal ActionabilityValue with only the reason field set.
    private def reasonOnly(reason: String): ActionabilityValue =
        ActionabilityValue(actionable = false, reason = Present(reason))

    // `Actionability.parseResult` consumes the typed `ActionabilityResponse` the engine decodes from the CDP
    // reply. This helper wraps an inline `ActionabilityValue` in the response shape (result.value) the JS probe
    // produces with `returnByValue = true`.
    private def respOk(value: ActionabilityValue): ActionabilityResponse =
        ActionabilityResponse(result = Present(ActionabilityRemoteObject(value = Present(value))))

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
            val resp = ActionabilityResponse(
                result = Present(ActionabilityRemoteObject(value =
                    Present(
                        ActionabilityValue(
                            actionable = true,
                            navigatesOnClick = false,
                            rect = Present(ActionabilityRect(x = 10, y = 20, width = 100, height = 50))
                        )
                    )
                )),
                exceptionDetails = Present(ExceptionDetails(text = Present("error")))
            )
            Actionability.parseResult(resp, dummyRef).map { result =>
                assert(result == Result.Failure(Reason.NotAttached))
            }
        }

        "NotVisible response returns NotVisible(DisplayNone)" in {
            val resp = respOk(
                ActionabilityValue(actionable = false, reason = Present("NotVisible"), notVisibleCause = Present("DisplayNone"))
            )
            Actionability.parseResult(resp, dummyRef).map {
                case Result.Failure(Reason.NotVisible(Reason.NotVisibleCause.DisplayNone)) =>
                    ()
                case other => fail(s"expected NotVisible(DisplayNone) but got $other")
            }
        }

        "Disabled response with AriaDisabled returns Disabled(AriaDisabled)" in {
            val resp = respOk(
                ActionabilityValue(actionable = false, reason = Present("Disabled"), disabledKind = Present("AriaDisabled"))
            )
            Actionability.parseResult(resp, dummyRef).map {
                case Result.Failure(Reason.Disabled(Reason.DisabledKind.AriaDisabled)) =>
                    ()
                case other => fail(s"expected Disabled(AriaDisabled) but got $other")
            }
        }
    }

    "malformed wire" - {

        // C1: the pre-redesign Actionability.parseResult(rawJson) decoded the JSON itself and on failure raised
        // BrowserProtocolErrorException tagged method == "Actionability". The decode now happens at the engine typed-decode
        // edge via tab.session.send[EvalParams, ActionabilityResponse] (Actionability.scala), so a malformed actionability
        // reply surfaces a decode failure tagged with the CDP method the path uses (Runtime.evaluate), not "Actionability".
        // This test feeds an undecodable ActionabilityResponse through that exact path and pins the typed failure.

        // Wrong-type shape for ActionabilityResponse: `result` is an Int instead of the expected record, so kyo-schema
        // rejects it rather than defaulting it away.
        case class BadActionabilityResponse(result: Int = 42) derives Schema

        val testLaunchCfg = Browser.LaunchConfig.default.copy(
            requestTimeout = 5.seconds,
            closeGrace = 500.millis
        )

        val testVersionResult = BrowserVersionResult(
            protocolVersion = "0",
            product = "Headless/0",
            revision = "0",
            userAgent = "Mozilla/5.0 (Headless)",
            jsVersion = "0.0"
        )

        def mkBackendWithServer(
            extraServerMethods: Seq[JsonRpcRoute[?, ?, ?]]
        )(using Frame): CdpBackend < (Async & Scope & Abort[BrowserReadException | BrowserSetupException]) =
            JsonRpcTransport.inMemory.map { (client, server) =>
                val versionMethod = JsonRpcRoute.request[BrowserGetVersionParams, BrowserVersionResult](
                    "Browser.getVersion"
                ) { (_, _) => testVersionResult }
                val config = JsonRpcHandler.Config(
                    codec = JsonRpcEnvelope.lenientSchema,
                    maxInFlight = Present(8),
                    idStrategy = JsonRpcIdStrategy.SequentialInt
                )
                JsonRpcHandler.init(server, versionMethod +: extraServerMethods, config).andThen {
                    CdpBackend.initUnscoped(client, testLaunchCfg)
                }
            }

        "an undecodable actionability reply surfaces BrowserProtocolErrorException at Runtime.evaluate" in {
            Scope.run {
                val evalMethod = JsonRpcRoute.request[EvalParams, BadActionabilityResponse](
                    CdpBackend.RuntimeEvaluateMethod
                ) { (_, _) => BadActionabilityResponse() }
                mkBackendWithServer(Seq(evalMethod)).map { backend =>
                    Abort.run[BrowserReadException](
                        backend.send[EvalParams, ActionabilityResponse](
                            CdpBackend.RuntimeEvaluateMethod,
                            EvalParams("(async () => ({}))()", returnByValue = true, awaitPromise = true)
                        )
                    ).map {
                        case Result.Failure(e: BrowserProtocolErrorException) =>
                            // The owning method is the CDP method the actionability path sends on, not "Actionability".
                            assert(
                                e.method == CdpBackend.RuntimeEvaluateMethod,
                                s"expected method 'Runtime.evaluate' but got '${e.method}'"
                            )
                            // Decode-failure discriminator: distinguishes a malformed-reply decode failure from a
                            // server-signalled coded error (which would carry a different message and a numeric code).
                            assert(e.error.contains("result decode"), s"expected decode-failure marker but got: ${e.error}")
                        case other => fail(s"Expected BrowserProtocolErrorException for an undecodable actionability reply but got $other")
                    }
                }
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
            val resp = respOk(
                ActionabilityValue(
                    actionable = true,
                    navigatesOnClick = true,
                    rect = Present(ActionabilityRect(x = 10, y = 20, width = 100, height = 50))
                )
            )
            Actionability.parseResult(resp, dummyRef).map {
                case Result.Success(ref) =>
                    assert(ref.navigatesOnClick, "expected navigatesOnClick=true")
                    assert(ref.x == 10)
                    assert(ref.y == 20)
                case other => fail(s"expected Success, got $other")
            }
        }

        "false value is propagated into ActionableRef" in {
            val resp = respOk(
                ActionabilityValue(
                    actionable = true,
                    navigatesOnClick = false,
                    rect = Present(ActionabilityRect(x = 5, y = 6, width = 80, height = 30))
                )
            )
            Actionability.parseResult(resp, dummyRef).map {
                case Result.Success(ref) =>
                    assert(!ref.navigatesOnClick, "expected navigatesOnClick=false")
                case other => fail(s"expected Success, got $other")
            }
        }
    }

end ActionabilityTest
