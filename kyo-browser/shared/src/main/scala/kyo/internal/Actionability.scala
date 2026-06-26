package kyo.internal

import CdpTypes.*
import kyo.*
import kyo.BrowserElementNotActionableException.Reason

/** Single JS round-trip actionability gate used by every Browser interaction method.
  *
  * Given a selector, resolves to the first matching element and runs the following sub-checks inside ONE `Runtime.evaluate` call, in order:
  *
  *   1. Attached: the element is still connected to `document` (`Node.isConnected`).
  *   1. Fillable: when `requireFillable = true`, rejects non-INPUT/TEXTAREA/contentEditable targets with
  *      [[Reason.NotFillable]] before any visibility work.
  *   1. Visible: `getComputedStyle` is checked for `display:none`, `visibility:hidden`, `opacity:0` on the element and its ancestor chain;
  *      `getBoundingClientRect` must have non-zero area.
  *   1. Scroll-into-view: brings the element into the viewport only when it is outside its nearest scroll container or the viewport; this
  *      is a no-op for already-visible elements, so it avoids the unnecessary motion that scrolling always-center would introduce on every
  *      check. Blink-only, fine because kyo-browser drives Chromium via CDP.
  *   1. Stable: the bounding rect is identical across two ~16ms ticks (to within 1px rounding). Sampled AFTER scroll-into-view so a
  *      still-scrolling element is flagged as [[Reason.Unstable]] until it settles.
  *   1. Hittable: `elementFromPoint` at the rect's center returns the element or one of its descendants (i.e. not covered by an overlay).
  *   1. Enabled: neither `el.disabled` nor `aria-disabled="true"` nor inside a `<fieldset disabled>` nor `pointer-events:none`.
  *
  * When `requireFillable = true` an additional short-circuit rejects non-`INPUT`/`TEXTAREA`/contentEditable targets with
  * [[Reason.NotFillable]] before running the visibility pipeline.
  *
  * The checks are ordered so the earliest failure wins (e.g. a detached node surfaces as [[Reason.NotAttached]], not
  * [[Reason.NotVisible]]).
  *
  * Returns `Result[Reason, ActionableRef]`: success carries the rect center for the caller to reuse in
  * `Input.dispatchMouseEvent`; failure carries the typed reason so callers can enrich `BrowserElementNotActionableException`.
  */
private[kyo] object Actionability:

    /** Result of a passing actionability check: the backend node id plus the rounded integer center (`x`, `y`), the element's bounding rect
      * (`width`, `height`), and a `navigatesOnClick` hint that is `true` when the element is an `<a href>`, a form submit button, any
      * element whose `onclick` contains a `location.*` / `window.open` call, or an element with `role="link"` that carries an `href`.
      * Callers reuse the center for `Input.dispatchMouseEvent` without a second round-trip; `navigatesOnClick` arms the navigation watcher
      * around the click.
      */
    final case class ActionableRef(nodeRef: NodeRef, x: Int, y: Int, width: Int, height: Int, navigatesOnClick: Boolean = false)
        derives CanEqual

    /** Single JS round-trip: resolves the selector, runs the sub-checks, and returns either [[ActionableRef]] on success or the earliest
      * failing [[Reason]]. When `requireFillable = true` a non-fillable target short-circuits with
      * [[Reason.NotFillable]]. When `requireEnabled = false` the disabled probe is skipped (used by `press` and
      * `hover`, which are valid against disabled targets: modifier-press recording / mouseover handlers still need to fire). Visibility,
      * attached, stability, and hittable checks all still run regardless.
      *
      * The return type is `Result[Reason, ActionableRef]` so callers can pattern-match both branches purely. A selector
      * that resolves to no element yields `Result.Failure(Reason.NotAttached)`; it is a degenerate case of "not
      * attached to the DOM".
      */
    def check(
        selector: Selector,
        requireFillable: Boolean,
        requireEnabled: Boolean
    )(using
        Frame
    ): Result[Reason, ActionableRef] <
        (Browser & Abort[BrowserReadException]) =
        Resolver.resolveOne(selector).map {
            case Absent =>
                Result.Failure(Reason.NotAttached): Result[Reason, ActionableRef]
            case Present(ref) =>
                val selectorJs = SelectorJs.resolveElementJs(Selector.toNode(selector))
                val js         = buildJs(selectorJs, requireFillable, requireEnabled)
                Browser.activeIFrameLocal.use(active => active.map(_.executionContextId)).map { ctx =>
                    val ctxOpt = ctx.map(c => CdpTypes.ExecutionContextId.value(c))
                    Browser.use { tab =>
                        CdpBackend.recoverContextDestroyed {
                            tab.session.send[EvalParams, ActionabilityResponse](
                                CdpBackend.RuntimeEvaluateMethod,
                                EvalParams(js, returnByValue = true, awaitPromise = true, contextId = ctxOpt)
                            )
                        }.map(resp => parseResult(resp, ref))
                    }
                }
        }
    end check

    // --- Internal ---

    /** Marker comment embedded in every actionability JS bundle.
      *
      * Invariant: `Actionability.check` must complete in a single CDP round trip. The marker exists so the round-trip count can be
      * observed: every `Runtime.evaluate` whose expression contains this string is part of an actionability check, and there must be
      * exactly one such evaluate per `check` call.
      */
    private[kyo] val jsMarker: String = "/* kyo-actionability */"

    private def buildJs(selectorJs: String, requireFillable: Boolean, requireEnabled: Boolean): String =
        val fillCheck =
            if requireFillable then
                """
                if (!(el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) {
                    return { actionable: false, reason: 'NotFillable', tagName: el.tagName.toLowerCase() };
                }
                """
            else ""
        val enabledCheck =
            if requireEnabled then
                """
                // Enabled: native disabled attribute.
                if (el.disabled === true) {
                    return { actionable: false, reason: 'Disabled', disabledKind: 'Attribute' };
                }
                // aria-disabled.
                if (el.getAttribute('aria-disabled') === 'true') {
                    return { actionable: false, reason: 'Disabled', disabledKind: 'AriaDisabled' };
                }
                // Ancestor <fieldset disabled>.
                for (let p = el.parentElement; p; p = p.parentElement) {
                    if (p.tagName === 'FIELDSET' && p.disabled) {
                        return { actionable: false, reason: 'Disabled', disabledKind: 'FieldsetDisabled' };
                    }
                }
                // pointer-events:none.
                if (getComputedStyle(el).pointerEvents === 'none') {
                    return { actionable: false, reason: 'Disabled', disabledKind: 'PointerEventsNone' };
                }
                """
            else ""
        s"""$jsMarker (async () => {
            window.__kyoActionabilityCount = (window.__kyoActionabilityCount || 0) + 1;
            const el = $selectorJs;
            if (!el || !el.isConnected) {
                return { actionable: false, reason: 'NotAttached' };
            }
            $fillCheck
            // Visibility: self + ancestor chain.
            const style = getComputedStyle(el);
            if (style.display === 'none') {
                return { actionable: false, reason: 'NotVisible', notVisibleCause: 'DisplayNone' };
            }
            if (style.visibility === 'hidden') {
                return { actionable: false, reason: 'NotVisible', notVisibleCause: 'VisibilityHidden' };
            }
            if (style.opacity === '0') {
                return { actionable: false, reason: 'NotVisible', notVisibleCause: 'OpacityZero' };
            }

            for (let p = el.parentElement; p; p = p.parentElement) {
                const ps = getComputedStyle(p);
                if (ps.display === 'none') {
                    return { actionable: false, reason: 'NotVisible', notVisibleCause: 'DisplayNone' };
                }
                if (ps.visibility === 'hidden') {
                    return { actionable: false, reason: 'NotVisible', notVisibleCause: 'VisibilityHidden' };
                }
            }
            const r0 = el.getBoundingClientRect();
            if (r0.width <= 0 || r0.height <= 0) {
                return { actionable: false, reason: 'ZeroSizedElement', rect: { x: Math.round(r0.x), y: Math.round(r0.y), width: Math.round(r0.width), height: Math.round(r0.height) } };
            }
            // Scroll-into-view: only if not already visible in its nearest scroll container + viewport. This is a
            // no-op when the element is already on-screen, avoiding unnecessary motion on every check. Blink-only,
            // which is fine because kyo-browser drives Chromium via CDP.
            el.scrollIntoViewIfNeeded(true);
            // Stability: sample rect across two ~16ms ticks; rects must agree to within 1px rounding.
            // setTimeout (not requestAnimationFrame): RAF callbacks stall on Chromium immediately after
            // dismissing JS dialogs, hanging Runtime.evaluate(awaitPromise=true) indefinitely. setTimeout does
            // not have this issue. 16ms is one paint frame; getBoundingClientRect reads the current layout
            // regardless, so paint alignment is unnecessary for stability comparison.
            await new Promise(resolve => setTimeout(resolve, 16));
            const r1 = el.getBoundingClientRect();
            await new Promise(resolve => setTimeout(resolve, 16));
            const r2 = el.getBoundingClientRect();
            const approx = (a, b) => Math.abs(a - b) <= 1;
            if (!approx(r1.x, r2.x) || !approx(r1.y, r2.y) || !approx(r1.width, r2.width) || !approx(r1.height, r2.height)) {
                return { actionable: false, reason: 'Unstable' };
            }
            if (r2.width <= 0 || r2.height <= 0) {
                return { actionable: false, reason: 'ZeroSizedElement', rect: { x: Math.round(r2.x), y: Math.round(r2.y), width: Math.round(r2.width), height: Math.round(r2.height) } };
            }
            // NotInViewport: after scroll-into-view + stabilization, check whether the element's
            // bounding rect lies entirely outside the viewport bounds. Fixed-position elements or
            // elements with explicit out-of-viewport positioning (e.g. top:-1000px) are not
            // scrollable into view and will always fail this check.
            const vw = window.innerWidth || document.documentElement.clientWidth;
            const vh = window.innerHeight || document.documentElement.clientHeight;
            if (r2.right < 0 || r2.bottom < 0 || r2.left > vw || r2.top > vh) {
                return {
                    actionable: false,
                    reason: 'NotInViewport',
                    rect: { x: Math.round(r2.x), y: Math.round(r2.y), width: Math.round(r2.width), height: Math.round(r2.height) },
                    viewportRect: { x: 0, y: 0, width: Math.round(vw), height: Math.round(vh) }
                };
            }
            // Re-check detachment after RAF wait; DOM could have moved.
            if (!el.isConnected) {
                return { actionable: false, reason: 'NotAttached' };
            }
            // Hittable: elementFromPoint at the center returns the element or a descendant.
            const cx = r2.x + r2.width / 2;
            const cy = r2.y + r2.height / 2;
            const hit = document.elementFromPoint(cx, cy);
            const isSelfOrDescendant = hit === el || (hit && el.contains(hit));
            if (!isSelfOrDescendant) {
                const hitTag = hit ? hit.tagName.toLowerCase() : 'unknown';
                const hitId = hit && hit.id ? '#' + hit.id : '';
                const hitClass = hit && hit.className && typeof hit.className === 'string' && hit.className.trim() ? '.' + hit.className.trim().split(/\\s+/).join('.') : '';
                return { actionable: false, reason: 'OutsideHitTarget', actualHit: hitTag + hitId + hitClass };
            }
            // Enabled: native disabled + aria-disabled + fieldset + pointer-events. Skipped entirely when requireEnabled = false.
            $enabledCheck
            // Nav-intent: does clicking this element plausibly cause navigation?
            // Checks (all cheap, no extra round-trip):
            //   - <a href="..."> where href isn't empty / a fragment-only hash / javascript:void(0)
            //   - <button type="submit"> or any descendant of a <form> that would submit it
            //   - <input type="submit"|"image"> inside a form
            //   - element with role="link" and [href]
            //   - onclick attribute whose source text contains location.assign / location.href = / window.open / form.submit
            const sameOriginFragment = (href) => {
                try {
                    const u = new URL(href, location.href);
                    return u.origin === location.origin && u.pathname === location.pathname && u.search === location.search && u.hash !== location.hash;
                } catch (e) { return false; }
            };
            const isNavHref = (href) => {
                if (!href) return false;
                const trimmed = String(href).trim();
                if (!trimmed) return false;
                if (trimmed.startsWith('javascript:')) return false;
                if (trimmed.startsWith('#') && !sameOriginFragment(trimmed)) return false;
                return true;
            };
            let navigatesOnClick = false;
            // Anchor or role=link with href
            const tag = el.tagName;
            const href = el.getAttribute && el.getAttribute('href');
            if ((tag === 'A' || el.getAttribute('role') === 'link') && isNavHref(href)) {
                navigatesOnClick = true;
            }
            // Submit / image button inside a form
            if (!navigatesOnClick) {
                const isSubmitButton = tag === 'BUTTON' && (el.type === 'submit' || !el.type);
                const isSubmitInput = tag === 'INPUT' && (el.type === 'submit' || el.type === 'image');
                if (isSubmitButton || isSubmitInput) {
                    // Walk ancestors to see if we're in a form
                    for (let p = el; p; p = p.parentElement) {
                        if (p.tagName === 'FORM') { navigatesOnClick = true; break; }
                    }
                }
            }
            // onclick source heuristic
            if (!navigatesOnClick) {
                const onclickSrc = el.getAttribute && el.getAttribute('onclick');
                if (onclickSrc) {
                    const s = String(onclickSrc);
                    if (s.includes('location.assign') || s.includes('location.href') ||
                        s.includes('location.replace') || s.includes('window.open') ||
                        s.includes('.submit(')) {
                        navigatesOnClick = true;
                    }
                }
            }
            return {
                actionable: true,
                navigatesOnClick: navigatesOnClick,
                rect: {
                    x: Math.round(cx),
                    y: Math.round(cy),
                    width: Math.round(r2.width),
                    height: Math.round(r2.height)
                }
            };
        })()"""
    end buildJs

    /** Parses the typed CDP `Runtime.evaluate` [[ActionabilityResponse]].
      *
      * A response with `exceptionDetails` Present, or a missing `result.value`, surfaces as `Result.Failure(NotAttached)`, the conservative
      * actionability outcome that downstream callers already expect.
      *
      * The engine decodes the reply through `Schema[ActionabilityResponse]` and Aborts on a CDP error or wire-shape drift before this method
      * runs, so there is no `CdpReply` unwrap, no `reply.error` branch, and no decode-failure branch here.
      */
    private[kyo] def parseResult(
        resp: ActionabilityResponse,
        ref: NodeRef
    )(using Frame): Result[Reason, ActionableRef] < (Sync & Abort[BrowserReadException]) =
        resp.exceptionDetails match
            case Present(_) => Result.Failure(Reason.NotAttached)
            case Absent =>
                (resp.result.flatMap(_.value): @unchecked) match
                    case Present(v) => decodeValue(v, ref)
                    case Absent     => Result.Failure(Reason.NotAttached)

    private[kyo] def decodeValue(v: ActionabilityValue, ref: NodeRef)(using
        Frame
    )
        : Result[Reason, ActionableRef] < (Sync & Abort[BrowserReadException]) =
        if v.actionable then
            v.rect match
                case Present(r) => Result.Success(ActionableRef(ref, r.x, r.y, r.width, r.height, v.navigatesOnClick))
                case Absent     => Result.Failure(Reason.NotAttached)
        else
            v.reason match
                case Present(s) => decodeReason(s, v).map(Result.Failure(_))
                case Absent     => Result.Failure(Reason.NotAttached)
        end if
    end decodeValue

    /** Decodes the `reason` string from the actionability probe wire shape into the typed [[Reason]] hierarchy.
      *
      * Each case maps the wire reason string to its corresponding typed case, extracting per-reason diagnostic fields from `v` when
      * available.
      *
      * Unknown sub-cause or sub-kind strings are logged and preserved as `NotVisibleCause.Other` / `DisabledKind.Other` sentinels so the
      * outer reason (NotVisible / Disabled) is still surfaced correctly. Genuinely unknown top-level reason strings or malformed
      * `NotInViewport` payloads (missing rect / viewportRect) are protocol errors and are raised via `Abort[BrowserReadException]` so the
      * caller can distinguish wire drift from a legitimate actionability failure.
      */
    private[kyo] def decodeReason(s: String, v: ActionabilityValue)(using
        Frame
    ): Reason < (Sync & Abort[BrowserReadException]) =
        s match
            case "NotAttached" => Reason.NotAttached
            case "NotVisible" =>
                val causeEffect: Reason.NotVisibleCause < Sync =
                    v.notVisibleCause match
                        case Present("DisplayNone")      => Reason.NotVisibleCause.DisplayNone
                        case Present("VisibilityHidden") => Reason.NotVisibleCause.VisibilityHidden
                        case Present("OpacityZero")      => Reason.NotVisibleCause.OpacityZero
                        case Present("ZeroComputedSize") => Reason.NotVisibleCause.ZeroComputedSize
                        case Present(other) =>
                            Log.warn(s"Actionability: unknown notVisibleCause '$other'; preserving as Other")
                                .andThen(Reason.NotVisibleCause.Other(other))
                        case Absent =>
                            Log.warn("Actionability: notVisibleCause absent for NotVisible; preserving as Other(absent)")
                                .andThen(Reason.NotVisibleCause.Other("absent"))
                causeEffect.map(Reason.NotVisible(_))
            case "ZeroSizedElement" =>
                val w = v.rect.map(_.width).getOrElse(0)
                val h = v.rect.map(_.height).getOrElse(0)
                Reason.ZeroSizedElement(w, h)
            case "Unstable" => Reason.Unstable
            case "OutsideHitTarget" =>
                Reason.OutsideHitTarget(v.actualHit.getOrElse("unknown"))
            case "Disabled" =>
                val kindEffect: Reason.DisabledKind < Sync =
                    v.disabledKind match
                        case Present("Attribute")         => Reason.DisabledKind.Attribute
                        case Present("AriaDisabled")      => Reason.DisabledKind.AriaDisabled
                        case Present("FieldsetDisabled")  => Reason.DisabledKind.FieldsetDisabled
                        case Present("PointerEventsNone") => Reason.DisabledKind.PointerEventsNone
                        case Present(other) =>
                            Log.warn(s"Actionability: unknown disabledKind '$other'; preserving as Other")
                                .andThen(Reason.DisabledKind.Other(other))
                        case Absent =>
                            Log.warn("Actionability: disabledKind absent for Disabled; preserving as Other(absent)")
                                .andThen(Reason.DisabledKind.Other("absent"))
                kindEffect.map(Reason.Disabled(_))
            case "NotFillable" =>
                Reason.NotFillable(v.tagName.getOrElse("unknown"))
            case "FillDesync" => Reason.FillDesync
            case "NotInViewport" =>
                (v.rect, v.viewportRect) match
                    case (Present(rect), Present(vp)) =>
                        Reason.NotInViewport(
                            Reason.Rect(rect.x, rect.y, rect.width, rect.height),
                            Reason.Rect(vp.x, vp.y, vp.width, vp.height)
                        )
                    case _ =>
                        Abort.fail(BrowserProtocolErrorException.decodeFailure(
                            "Actionability.NotInViewport",
                            s"missing rect or viewportRect: $v"
                        ))
            case other =>
                Abort.fail(BrowserProtocolErrorException.decodeFailure(
                    "Actionability.decodeReason",
                    s"unknown reason: $other; full=$v"
                ))

    // --- Element resolution and actionability ---

    /** Gate an action computation on a typed `Resolver.resolveOne`. On `Absent` abort with `BrowserElementNotFoundException` carrying the
      * selector's human-readable description; on `Present` run the action. The action JS may still fail loudly if the DOM changes between
      * resolve and run (TOCTOU); that is an internal JS failure, not a presence problem, so it surfaces as a Panic.
      */
    private[kyo] def requireResolved[A, S](selector: Selector)(
        action: => A < (Browser & Abort[BrowserReadException] & S)
    )(
        using Frame
    ): A < (Browser & Abort[BrowserReadException] & S) =
        Resolver.resolveOne(selector).map {
            case Absent     => Abort.fail(BrowserElementNotFoundException(Browser.selectorNodeDescription(Selector.toNode(selector))))
            case Present(_) => action
        }

    /** Runs `Actionability.check` for `selector`, then calls `action` on the `ActionableRef` on success, or aborts with
      * [[BrowserElementNotActionableException]] on failure. Panics are re-raised via `Abort.panic`. When `requireFillable = true`, the
      * check short-circuits non-fillable elements with [[Reason.NotFillable]]. When `requireEnabled = false`,
      * the disabled / `aria-disabled` probe is skipped (used by `press` and `hover`, which are valid against disabled targets).
      */
    private[kyo] def withActionable[A, S](
        selector: Selector,
        requireFillable: Boolean,
        requireEnabled: Boolean
    )(action: Actionability.ActionableRef => A <
        (Browser & Async & Abort[BrowserReadException] & S))(
        using Frame
    ): A < (Browser & Async & Abort[BrowserReadException] & S) =
        Actionability.check(selector, requireFillable, requireEnabled).map {
            case Result.Success(ref) => action(ref)
            case Result.Failure(reason) =>
                enrichDescriptionForReason(selector, reason).map { desc =>
                    Abort.fail(BrowserElementNotActionableException(desc, reason))
                }
            case Result.Panic(t) => Abort.panic(t)
        }

    /** Builds the selector-description string for the actionability exception. For ARIA selectors that failed with
      * [[Reason.NotAttached]] (zero candidates after the implicit ARIA visibility filter), runs an extra
      * unfiltered-count probe to distinguish "no element with this role+name exists" (selector-side problem) from "candidates exist but are
      * hidden by display:none / aria-hidden / etc." (page-state problem). The two cases need different remediations and the diagnostic
      * suffix makes that immediate.
      *
      * Non-ARIA selectors and non-NotAttached reasons fall through to the plain description.
      */
    private def enrichDescriptionForReason(selector: Selector, reason: Reason)(using
        Frame
    ): String < (Browser & Async & Abort[BrowserReadException]) =
        val baseDesc = Browser.selectorNodeDescription(Selector.toNode(selector))
        val node     = Selector.toNode(selector)
        val bodyEffect: String < (Browser & Async & Abort[BrowserReadException]) =
            (node, reason) match
                case (ariaNode @ SelectorNode.Aria(_, _), Reason.NotAttached) =>
                    BrowserEval.evalJs(SelectorJs.unfilteredAriaCountExprJs(ariaNode)).map { raw =>
                        val count = raw.toIntOption.getOrElse(0)
                        if count == 0 then s"$baseDesc (no element with this role+name on the page)"
                        else
                            s"$baseDesc ($count candidate(s) match the role+name but are filtered by visibility: display:none, aria-hidden, or zero-size)"
                    }
                // NotVisible: re-probe the matched element's bounding rect so the exception message carries geometry. "rect=0x0" pinpoints
                // a display:none-collapsed element; "rect=404x32" tells the operator the element exists with real geometry but an ancestor
                // is hiding it. Distinguishes selector matches whose visibility differs even when the selector itself is correct.
                case (_, _: Reason.NotVisible) =>
                    BrowserEval.evalJs(SelectorJs.boundingRectSummaryExprJs(node)).map { raw =>
                        if raw == "detached" then baseDesc
                        else s"$baseDesc (rect=$raw)"
                    }
                case _ => baseDesc
        // Always append the current page URL: stale-selector failures often surface as NotAttached after the page
        // navigated away from where the user thought they were. The URL transition is the single most useful piece of
        // diagnostic context for "why didn't my selector match anymore?".
        bodyEffect.map { body =>
            Browser.url.map(url => s"$body at URL $url")
        }
    end enrichDescriptionForReason

    /** Reads the current retry schedule from [[Browser.configLocal]] and wraps `action` in a `Retry[BrowserElementException]` loop.
      *
      * Narrowed from `BrowserMutationException` to `BrowserElementException` because the operation-row hierarchy makes
      * `BrowserAssertionTimedOutException` a `BrowserMutationException` (via `BrowserAssertionException`); a `BrowserMutationException`
      * Retry would catch the settlement-timeout from `MutationSettlement.afterAction` and loop the whole click-retry, blowing past the
      * test's `mutationSettlementTimeout` budget. Element-not-found / element-not-actionable are the only legitimate retry triggers in this
      * scope; settlement timeouts must propagate to the caller.
      */
    private[kyo] def withRetry[
        A,
        S
    ](action: A < (Browser & Async & Abort[BrowserReadException] & S))(
        using Frame
    ): A < (Browser & Async & Abort[BrowserReadException] & S) =
        Browser.configLocal.use { cfg =>
            Retry[BrowserElementException](cfg.retrySchedule) { action }
        }

end Actionability
