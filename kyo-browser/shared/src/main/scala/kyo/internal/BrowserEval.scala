package kyo.internal

import CdpTypes.*
import kyo.*

/** CDP `Runtime.evaluate` wrappers and single-eval read cores.
  *
  * `evalJs` / `evalJsAwaiting` / `evalJsChecked` are the three eval flavours used by every kyo-browser script round-trip. They share the
  * same iframe-context routing (read `Browser.activeIFrameLocal` on entry, pass the snapshotted `executionContextId` to CDP).
  * `translateContextDestroyed` is the cross-flavour error translator.
  *
  * `locateCount` / `readTextCore` / `readAttributeCore` are the read-side single-evaluate fast paths built on top of those wrappers, used
  * by `Browser.count` / `Browser.text` / `Browser.attribute` and by the retry-and-assert helpers in `BrowserAssertion`.
  *
  * `clickAt` / `dispatchMouse` are the mouse-event-dispatch primitives used by the click family, included here (alongside the eval
  * primitives rather than in `Actionability` or `BrowserAssertion`) because they are the Scala-side counterpart to `evalJs`: both are
  * low-level CDP issuers that the rest of the module composes.
  */
private[kyo] object BrowserEval:

    // ---- Eval wrappers ----

    private[kyo] def evalJs(expr: String)(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Browser.activeIFrameLocal.use(active => active.map(_.executionContextId)).map { ctx =>
            Browser.use { tab =>
                CdpBackend.runtimeEvaluate(tab.session, EvalParams(expr, contextId = ctx.map(c => c.value)))
                    .map(translateContextDestroyed)
                    .map(CdpEvalDecoder.parseAndExtractEvalValue)
            }
        }

    /** Like [[evalJs]] but evaluates an `async`/Promise-returning expression and waits for the promise to settle in-page before returning
      * its resolved value. Used by [[kyo.internal.StabilitySampler]], which drives an entire in-page sampling loop inside one eval.
      */
    private[kyo] def evalJsAwaiting(expr: String)(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Browser.activeIFrameLocal.use(active => active.map(_.executionContextId)).map { ctx =>
            Browser.use { tab =>
                CdpBackend.runtimeEvaluate(
                    tab.session,
                    EvalParams(expr, returnByValue = true, awaitPromise = true, contextId = ctx.map(c => c.value))
                )
                    .map(translateContextDestroyed)
                    .map(CdpEvalDecoder.parseAndExtractEvalValue)
            }
        }

    private[kyo] def evalJsChecked(expr: String)(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        Browser.activeIFrameLocal.use(active => active.map(_.executionContextId)).map { ctx =>
            Browser.use { tab =>
                CdpBackend.runtimeEvaluate(tab.session, EvalParams(expr, contextId = ctx.map(c => c.value)))
                    .map(translateContextDestroyed)
                    .map { result =>
                        if CdpEvalDecoder.isUnreturnableValueError(result) then ""
                        else
                            CdpEvalEnvelope.decodeEvalEnvelope(result, "evalJsChecked") { env =>
                                env.exceptionDetails match
                                    case Present(ex) =>
                                        Abort.fail(
                                            BrowserScriptErrorException(ExceptionDetailsFormat.format(ex))
                                        )
                                    case Absent => CdpEvalDecoder.extractEvalValue(env)
                            }
                        end if
                    }
            }
        }

    /** Inspects a `Runtime.evaluate` raw response JSON: when CDP returned the error `"Cannot find context with specified id"` (a
      * snapshotted `executionContextId` was destroyed mid-scope), translates it to [[BrowserIFrameInvalidException]]; otherwise passes the
      * response through unchanged. The wire shape of an error response is the encoded [[kyo.internal.CdpError]] (`{"code":-32000,
      * "message":...}`); see `decodeCdpMessage` at `CdpClient.decodeCdpMessage` which re-encodes the `"error"` payload back to JSON before
      * resolving the promise.
      */
    private[kyo] def translateContextDestroyed(rawJson: String)(using Frame): String < Abort[BrowserReadException] =
        // The dispatcher passes the whole CDP wire; the CDP error (if any) is at `wire.error`,
        // not at the top level. Decode the [[CdpReply]] envelope and look at the `error` field.
        Json.decode[CdpReply[EvalResult]](rawJson) match
            case Result.Success(reply) =>
                reply.error match
                    case Present(err) if err.message.contains(CdpErrorStrings.ContextDestroyedErrorMessage) =>
                        Abort.fail(BrowserIFrameInvalidException(BrowserIFrameInvalidException.Reason.ContextDestroyed))
                    case _ => rawJson
            case _ => rawJson

    // ---- Read cores ----

    /** Counts elements matching the selector. Non-numeric JS results are treated as 0 (element not found).
      *
      * Constraint: this method must be safe to invoke inside a `Retry` loop; a transiently unavailable execution context (navigation in
      * flight, document being replaced) must surface as `0`, not as a connection failure that would terminate the retry. The JS expressions
      * are constructed by the library (not user input), so a non-numeric result is semantically equivalent to "not found" and `0` is the
      * correct retry-safe answer.
      */
    private[kyo] def locateCount(selector: Selector)(using Frame): Int < (Browser & Abort[BrowserReadException]) =
        val node = Selector.toNode(selector)
        node match
            case SelectorNode.FirstOf(selectors) =>
                // FirstOf short-circuits on the first non-empty alternative. `SelectorJs.resolveAllElementsJs(FirstOf(...))` concatenates every
                // alternative's matches, which would over-count for this method's semantics (callers treat 0 as "retry, not yet").
                //
                // Recursive walk with explicit early-return: `Kyo.foldLeft` would visit every alternative even after we have a hit, which
                // means an N-way `FirstOf` issues N JS evaluations on a hit at index 0. The recursion below stops at the first non-zero
                // count, matching the public-facing short-circuit semantics.
                val alternatives = selectors.toSeq
                def walk(i: Int): Int < (Browser & Abort[BrowserReadException]) =
                    if i >= alternatives.length then 0
                    else
                        locateCount(Selector.fromNode(alternatives(i))).map { n =>
                            if n > 0 then n else walk(i + 1)
                        }
                walk(0)
            case _ =>
                val jsExpr = s"(${SelectorJs.resolveAllElementsJs(node)}).length"
                evalJs(jsExpr).map(s => s.toIntOption.getOrElse(0))
        end match
    end locateCount

    private[kyo] def readTextCore(selector: Selector, jsExpr: String)(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        // Single-evaluate fast path: resolve + read in one JS round-trip instead of the
        // requireResolved → evalJs two-pass. Returns the raw text, or "not_attached" when the
        // selector matches nothing; the "not_attached" sentinel is then translated to
        // BrowserElementNotFoundException, consistent with the ProbesJs convention.
        evalJs(SelectorJs.readTextExprJs(jsExpr)).map { text =>
            if text == "not_attached" then
                Abort.fail(BrowserElementNotFoundException(Browser.selectorNodeDescription(Selector.toNode(selector))))
            else text
        }
    end readTextCore

    private[kyo] def readAttributeCore(selector: Selector, attrName: String, jsExpr: String)(using
        Frame
    ): String < (Browser & Abort[BrowserReadException]) =
        // Single-evaluate fast path: resolve + read in one round-trip. Returns "not_attached" when
        // the selector matches nothing, translated to BrowserElementNotFoundException.
        evalJs(SelectorJs.readAttributeExprJs(attrName, jsExpr)).map { value =>
            if value == "not_attached" then
                Abort.fail(BrowserElementNotFoundException(Browser.selectorNodeDescription(Selector.toNode(selector))))
            else value
        }
    end readAttributeCore

    /** Point-in-time decoder for the `"not_attached" | <positiveSentinel> | <anything else>` probe shape shared by the boolean
      * read family (`isVisible`, `isEnabled`, `isChecked`, `isFocused`, `hasNoVisibleText`, `hasEmptyValue`, `hasAttribute`).
      *
      * Runs the probe via [[evalJs]] (single round-trip, no retry / no stability window) and returns:
      *   - `Abort.fail(BrowserElementNotFoundException(...))` when the probe resolves to `"not_attached"` (the element is missing or
      *     detached). The fail-fast contract mirrors [[Browser.text]] / [[Browser.attribute]]: a missing element is an exceptional
      *     condition, not a `false` answer.
      *   - `true` when the probe equals `positive`.
      *   - `false` for every other sentinel (the negative side of the boolean, e.g. `"hidden"`, `"disabled"`, `"non_empty"`).
      *
      * This is the seam the `isX` reads in `Browser.scala` share. Callers wanting custom sentinel translation (e.g. `value`'s
      * `"unsupported"` arm) bypass this helper and call [[evalJs]] directly.
      */
    private[kyo] def probeBoolean(selector: Selector, probeExpr: String, positive: String)(using
        Frame
    ): Boolean < (Browser & Abort[BrowserReadException]) =
        evalJs(probeExpr).map {
            case "not_attached" =>
                Abort.fail(BrowserElementNotFoundException(Browser.selectorNodeDescription(Selector.toNode(selector))))
            case v => v == positive
        }
    end probeBoolean

    // ---- Mouse-event dispatch ----

    /** Dispatches a click at pre-resolved coordinates. Called from the Actionability-gated interaction paths; the gate has already produced
      * the center, so there is no need to re-evaluate the element's rect.
      */
    private[kyo] def clickAt(x: Int, y: Int, clickCount: Int)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.use { tab =>
            // Correct CDP double-click semantics: first press/release uses clickCount=1, the second uses clickCount=2.
            // Sending two clickCount=2 pairs makes Chrome interpret each pair as a "second click" and fire two dblclick events.
            dispatchMouse(tab, CdpTypes.MouseEventType.Moved, x, y, 0)
                .andThen(dispatchMouse(tab, CdpTypes.MouseEventType.Pressed, x, y, 1))
                .andThen(dispatchMouse(tab, CdpTypes.MouseEventType.Released, x, y, 1))
                .andThen {
                    if clickCount > 1 then
                        dispatchMouse(tab, CdpTypes.MouseEventType.Pressed, x, y, 2)
                            .andThen(dispatchMouse(tab, CdpTypes.MouseEventType.Released, x, y, 2))
                    else Kyo.unit
                }
        }

    /** Resolves the center of an Actionability-gated [[Actionability.ActionableRef]] in top-level viewport coordinates suitable for
      * [[Input.dispatchMouseEvent]]. When an iframe context is active, the actionability gate's `(ref.x, ref.y)` are iframe-local; CDP
      * `Input.dispatchMouseEvent` always interprets coords against the top-level viewport, so an iframe-local center clicks the wrong
      * pixel (or nothing). We translate via `DOM.getBoxModel`, which returns the element's content quad in top-level coords regardless
      * of frame depth.
      *
      * Outside an iframe (`Browser.activeIFrameLocal == Absent`) the gate's coords are already top-level; no translation needed.
      *
      * A `DOM.getBoxModel` miss (display:none/detached element) falls back to the actionability-gate coords. The actionability gate has
      * already screened those failure modes; this is purely a safety net so a transient stale-node race doesn't mask the gate's contract.
      */
    private[kyo] def clickAtActionable(ref: Actionability.ActionableRef, clickCount: Int)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.activeIFrameLocal.use { active =>
            active match
                case Absent => clickAt(ref.x, ref.y, clickCount)
                case Present(_) =>
                    Browser.use { tab =>
                        Abort.recover[BrowserProtocolErrorException](_ => clickAt(ref.x, ref.y, clickCount)) {
                            CdpBackend.getBoxModel(tab.session, GetBoxModelParams(backendNodeId = ref.nodeRef.backendNodeId)).map { bm =>
                                val c = bm.model.content
                                if c.size < 8 then clickAt(ref.x, ref.y, clickCount)
                                else
                                    // content is [x1,y1, x2,y2, x3,y3, x4,y4] for the content quad. Center is midpoint of opposing corners.
                                    val cx = ((c(0) + c(4)) / 2.0).round.toInt
                                    val cy = ((c(1) + c(5)) / 2.0).round.toInt
                                    clickAt(cx, cy, clickCount)
                                end if
                            }
                        }
                    }
        }
    end clickAtActionable

    private[kyo] def dispatchMouse(tab: BrowserTab, eventType: CdpTypes.MouseEventType, x: Int, y: Int, clickCount: Int)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        import CdpTypes.MouseEventType.*
        CdpBackend.dispatchMouseEvent(
            tab.session,
            MouseEventParams(
                eventType,
                x,
                y,
                eventType match
                    case Pressed | Released => Present("left")
                    case Moved              => Absent
                ,
                if clickCount > 0 then Present(clickCount) else Absent
            )
        )
    end dispatchMouse

end BrowserEval
