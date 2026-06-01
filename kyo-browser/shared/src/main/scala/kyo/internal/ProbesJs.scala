package kyo.internal

import kyo.*

/** Passive in-page probes, focus helpers, keyboard shims, and JS-based fill helpers used by `Browser`.
  *
  * Every method runs JS via [[BrowserEval.evalJs]] (or composes around helpers that do). The probes return short ASCII sentinels (e.g.
  * `"visible"`, `"hidden"`, `"not_attached"`) which the caller in `Browser.scala` translates into typed assertions / Aborts. The keyboard
  * shims (`runTabFocusAdvance`, `markActiveElementForTabAdvance`, `runSpaceClickSynthesis`) bridge gaps in CDP's synthetic-key handling.
  */
private[kyo] object ProbesJs:

    // --- One-shot scripts ---

    /** Minimal readability extraction script inspired by Mozilla's Readability.js.
      *
      * Clones the document, removes non-content elements (scripts, styles, navigation, footers, ads, etc.), then returns the text content
      * of the best article/main element found, or falls back to the full body text.
      */
    private[kyo] val readabilityScript: String = """(() => {
        const clone = document.cloneNode(true);
        const remove = [
            'script','style','nav','footer','header','aside',
            '[role="banner"]','[role="navigation"]','[role="complementary"]',
            '[role="contentinfo"]','[aria-hidden="true"]',
            '.ad,.ads,.advertisement,.social-share,.sidebar,.menu,.popup,.modal,.overlay,.cookie-banner'
        ];
        remove.forEach(sel => {
            try { clone.querySelectorAll(sel).forEach(el => el.remove()); } catch(e) {}
        });
        const article = clone.querySelector('article,main,[role="main"]');
        return (article || clone.body).innerText.trim();
    })()"""

    // --- Probe builders ---

    /** Self-contained JS expression for the visibility ladder. Evaluates to `visibleSentinel` when the element passes the full ladder
      * (`isConnected` -> self `getComputedStyle` -> ancestor walk -> `getBoundingClientRect`), and one of `"not_attached"` / `"hidden"` /
      * `"ancestor_hidden"` / `"zero_size"` (or all collapsed to `"hidden"` per `collapseHidden`) otherwise. Optional `extraTail` is
      * appended just before the visible-return so callers can layer extra checks (e.g. `disabled`) on a fully-visible element.
      *
      * Returned as a JS string so the assertion stability sampler can re-evaluate it in-page; the Scala-side probe methods below run it via
      * [[BrowserEval.evalJs]] for a one-shot read.
      */
    private[kyo] def visibilityLadderExprJs(
        selector: Selector,
        visibleSentinel: String,
        collapseHidden: Boolean,
        extraTail: String
    ): String =
        val jsExpr           = SelectorJs.resolveElementJs(Selector.toNode(selector))
        val ancestorSentinel = if collapseHidden then "hidden" else "ancestor_hidden"
        val zeroSizeSentinel = if collapseHidden then "hidden" else "zero_size"
        s"""(() => {
            const el = $jsExpr;
            if (!el) return 'not_attached';
            if (!el.isConnected) return 'not_attached';
            const style = getComputedStyle(el);
            if (style.display === 'none' || style.visibility === 'hidden') return 'hidden';
            for (let p = el.parentElement; p; p = p.parentElement) {
                const ps = getComputedStyle(p);
                if (ps.display === 'none' || ps.visibility === 'hidden') return '$ancestorSentinel';
            }
            const r = el.getBoundingClientRect();
            if (r.width <= 0 || r.height <= 0) return '$zeroSizeSentinel';
            $extraTail
            return '$visibleSentinel';
        })()"""
    end visibilityLadderExprJs

    /** JS expression for the passive visibility probe. Evaluates to `"visible"`, `"hidden"`, `"zero_size"`, `"ancestor_hidden"`, or
      * `"not_attached"`.
      */
    private[kyo] def visibilityExprJs(selector: Selector): String =
        visibilityLadderExprJs(selector, visibleSentinel = "visible", collapseHidden = false, extraTail = "")

    /** JS expression for the passive enabled probe. Evaluates to `"enabled"`, `"disabled"`, `"hidden"`, or `"not_attached"`. */
    private[kyo] def enabledExprJs(selector: Selector): String =
        visibilityLadderExprJs(
            selector,
            visibleSentinel = "enabled",
            collapseHidden = true,
            extraTail = "if (el.disabled === true || el.getAttribute('aria-disabled') === 'true') return 'disabled';"
        )

    /** JS expression for the passive disabled probe. Evaluates to `"disabled"`, `"enabled"`, or `"not_attached"`. */
    private[kyo] def disabledExprJs(selector: Selector): String =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        s"""(() => {
            const el = $jsExpr;
            if (!el) return 'not_attached';
            if (!el.isConnected) return 'not_attached';
            if (el.disabled === true || el.getAttribute('aria-disabled') === 'true') return 'disabled';
            return 'enabled';
        })()"""
    end disabledExprJs

    /** JS expression for the passive checked probe. Evaluates to `"checked"`, `"not_checked"`, or `"not_attached"`. */
    private[kyo] def checkedExprJs(selector: Selector): String =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        s"""(() => {
            const el = $jsExpr;
            if (!el) return 'not_attached';
            if (!el.isConnected) return 'not_attached';
            return el.checked === true ? 'checked' : 'not_checked';
        })()"""
    end checkedExprJs

    /** JS expression for the passive no-visible-text probe. Evaluates to `"empty"`, `"non_empty"`, or `"not_attached"`. Asserts that
      * `textContent.trim()` is empty (non-input case).
      */
    private[kyo] def emptyTextExprJs(selector: Selector): String =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        s"""(() => {
            const el = $jsExpr;
            if (!el) return 'not_attached';
            if (!el.isConnected) return 'not_attached';
            const text = (el.textContent || '').trim();
            return text === '' ? 'empty' : 'non_empty';
        })()"""
    end emptyTextExprJs

    /** JS expression for the passive empty-value probe. Evaluates to `"empty"`, `"non_empty"`, or `"not_attached"`. Asserts that `el.value`
      * is empty (input / textarea / select case).
      */
    private[kyo] def emptyValueExprJs(selector: Selector): String =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        s"""(() => {
            const el = $jsExpr;
            if (!el) return 'not_attached';
            if (!el.isConnected) return 'not_attached';
            return (el.value === '' || el.value == null) ? 'empty' : 'non_empty';
        })()"""
    end emptyValueExprJs

    /** JS expression for the passive focus probe. Evaluates to `"focused"`, `"not_focused"`, or `"not_attached"`. */
    private[kyo] def focusExprJs(selector: Selector): String =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        s"""(() => {
            const el = $jsExpr;
            if (!el) return 'not_attached';
            if (!el.isConnected) return 'not_attached';
            return document.activeElement === el ? 'focused' : 'not_focused';
        })()"""
    end focusExprJs

    /** JS expression for the passive caret-position probe. Evaluates to the zero-based `selectionStart` of an `HTMLInputElement` /
      * `HTMLTextAreaElement` as a string, `"unsupported"` when the element is attached but its `selectionStart` is `null`/`undefined` or
      * throws (e.g. `type="number"`, `type="email"`, `type="date"`, where the input has no selection model), or `"not_attached"` when the
      * selector resolved to nothing (or a detached element).
      *
      * Per the HTML spec, reading `selectionStart` on `<input>` types without a selection model throws `InvalidStateError`; the read is
      * wrapped in a `try/catch` so those types map to the same `'unsupported'` sentinel as the `null` branch.
      */
    private[kyo] def selectionStartExprJs(jsExpr: String): String =
        s"""(() => {
            const el = $jsExpr;
            if (!el) return 'not_attached';
            if (!el.isConnected) return 'not_attached';
            try {
                const s = el.selectionStart;
                if (s == null) return 'unsupported';
                return String(s);
            } catch (e) {
                return 'unsupported';
            }
        })()"""
    end selectionStartExprJs

    /** JS expression for the passive `el.value` probe. Reads the property (NOT the `value` HTML attribute, which `attribute(sel, "value")`
      * surfaces): for `<input>`, `<textarea>`, and `<select>` the property is the current runtime value, including any modifications made
      * via `el.value = ...` after page load. Evaluates to `'V:' + el.value` for elements that expose a string `value` property,
      * `"unsupported"` for elements whose `value` is not a string (e.g. detached non-form-controls), or `"not_attached"` when the selector
      * resolved to nothing (or a detached element). The `'V:'` prefix is required so the raw value itself can equal a sentinel string
      * without ambiguity; the Scala-side decoder strips the prefix.
      */
    private[kyo] def valueExprJs(selector: Selector): String =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        s"""(() => {
            const el = $jsExpr;
            if (!el) return 'not_attached';
            if (!el.isConnected) return 'not_attached';
            if (typeof el.value !== 'string') return 'unsupported';
            return 'V:' + el.value;
        })()"""
    end valueExprJs

    /** JS expression for the passive named-attribute-absence probe. Evaluates to `"absent"` when the element is attached and
      * `!el.hasAttribute(name)`, `"present"` when the element is attached and `el.hasAttribute(name)`, or `"not_attached"` when the
      * selector resolved to nothing (or a detached element).
      */
    private[kyo] def noAttributeExprJs(name: String, jsExpr: String): String =
        val escAttr = JsStringUtil.escapeJsString(name)
        s"""(() => {
            const el = $jsExpr;
            if (!el) return 'not_attached';
            if (!el.isConnected) return 'not_attached';
            return el.hasAttribute('$escAttr') ? 'present' : 'absent';
        })()"""
    end noAttributeExprJs

    /** JS expression for the passive in-order substring probe. Reads `document.body.innerText` once and walks `substrings` in order,
      * advancing a cursor `pos` via `indexOf(sub, pos)`. Evaluates to `"ok"` when every substring is found in order (not necessarily
      * contiguous), or `"missing:<substring>"` when an `indexOf` returns `-1` for that substring. Each substring is escaped via
      * [[JsStringUtil.escapeJsString]] before being interpolated into the JS array literal so embedded quotes/newlines do not break the
      * expression.
      */
    private[kyo] def textOrderExprJs(substrings: Seq[String]): String =
        val arrayLiteral = substrings.iterator.map(s => s"'${JsStringUtil.escapeJsString(s)}'").mkString(", ")
        s"""(() => {
            const body = (document.body && document.body.innerText) || '';
            const subs = [$arrayLiteral];
            let pos = 0;
            for (let i = 0; i < subs.length; i++) {
                const idx = body.indexOf(subs[i], pos);
                if (idx < 0) return 'missing:' + subs[i];
                pos = idx + subs[i].length;
            }
            return 'ok';
        })()"""
    end textOrderExprJs

    /** JS expression for the passive element document-order probe. Resolves each selector via its per-selector resolver JS expression
      * (already a valid JS subexpression), then walks adjacent pairs and checks `prev.compareDocumentPosition(curr) &
      * Node.DOCUMENT_POSITION_FOLLOWING`. Evaluates to `"ok"` when every adjacent pair satisfies document-order, `"not_attached:<index>"`
      * (1-based) when a selector resolves to null/undefined, or `"out_of_order:<prev_index>:<curr_index>"` (1-based) when a pair fails the
      * ordering check.
      */
    private[kyo] def elementOrderExprJs(jsExprs: Seq[String]): String =
        val arrayLiteral = jsExprs.mkString(", ")
        s"""(() => {
            const els = [$arrayLiteral];
            for (let i = 0; i < els.length; i++) {
                if (!els[i]) return 'not_attached:' + (i + 1);
            }
            for (let i = 1; i < els.length; i++) {
                const prev = els[i - 1];
                const curr = els[i];
                if (!(prev.compareDocumentPosition(curr) & Node.DOCUMENT_POSITION_FOLLOWING)) {
                    return 'out_of_order:' + i + ':' + (i + 1);
                }
            }
            return 'ok';
        })()"""
    end elementOrderExprJs

    /** Tab focus-advance shim: emulates the platform's tabbing algorithm to make `Browser.press(_, Key.Tab)` deterministic. Chromium's CDP
      * `Input.dispatchKeyEvent({key:'Tab'})` advances focus in many cases but is unreliable at wrap boundaries (last → first, or first →
      * last under Shift+Tab) and on pages where Chromium-via-CDP doesn't run the focus-advance algorithm at all.
      *
      * The shim runs INSIDE `MutationSettlement.afterAction` (so subscribers observe the focus change as a settled mutation) AFTER the
      * keyUp dispatch. It reads the pre-Tab `document.activeElement` from a `data-kyo-tab-prev="1"` attribute that the caller stamps just
      * before keyDown (see `markActiveElementForTabAdvance`), collects focusables, filters out hidden / display:none / visibility:hidden /
      * disabled elements, sorts positive-tabindex first then natural-order, computes the next/previous element from the marker's index, and
      * calls `.focus()`. Wraps to first/last on overflow.
      *
      * No-op when no focusables are present (so a Tab on an empty page completes without abort).
      *
      * Why mark before dispatch: Chromium's native Tab handling advances `document.activeElement` synchronously during the dispatch, so we
      * cannot read the pre-dispatch `activeElement` from inside this shim. The marker survives the dispatch and lets the shim compute the
      * focus advance from the original element regardless of what Chromium did.
      */
    private[kyo] def runTabFocusAdvance(shift: Boolean)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        val dir = if shift then -1 else 1
        BrowserEval.evalJs(s"""(() => {
            const prev = document.querySelector('[data-kyo-tab-prev="1"]');
            if (prev) prev.removeAttribute('data-kyo-tab-prev');
            const all = [...document.querySelectorAll(
                'a[href],button:not([disabled]),input:not([disabled]):not([type=hidden]),' +
                'select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])'
            )].filter(el => {
                if (el.tabIndex < 0) return false;
                if (el.hidden) return false;
                if (el.offsetParent === null && el.tagName !== 'BODY') return false;
                for (let p = el; p; p = p.parentElement) {
                    const s = getComputedStyle(p);
                    if (s.display === 'none' || s.visibility === 'hidden') return false;
                }
                return true;
            });
            const positive = all.filter(el => el.tabIndex > 0).sort((a,b) => a.tabIndex - b.tabIndex);
            const natural  = all.filter(el => el.tabIndex <= 0);
            const ordered  = [...positive, ...natural];
            if (ordered.length === 0) return 'noop';
            const anchor = prev || document.activeElement;
            const i   = ordered.indexOf(anchor);
            const dir = $dir;
            const next = ordered[((i < 0 ? (dir > 0 ? -1 : 0) : i) + dir + ordered.length) % ordered.length];
            if (next) next.focus();
            return 'ok';
        })()""").unit
    end runTabFocusAdvance

    /** Tags `document.activeElement` with `data-kyo-tab-prev="1"` so `runTabFocusAdvance` can compute the next focus from the pre-dispatch
      * element regardless of what Chromium did to `document.activeElement` during the Tab dispatch.
      *
      * No-op if no element is focused. The shim removes the attribute when it runs.
      */
    private[kyo] def markActiveElementForTabAdvance(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(
            "(() => { const a = document.activeElement; if (a && a.setAttribute) a.setAttribute('data-kyo-tab-prev', '1'); return 'ok'; })()"
        ).unit
    end markActiveElementForTabAdvance

    /** Click-synthesis shim for `Key.Space` on activatable controls. Real browsers fire a synthetic `click` on the focused element on Space
      * `keyup` for `<button>`, `<input type="checkbox">`, and `<input type="radio">`; CDP's synthetic Space keystroke does not. The shim
      * runs INSIDE `MutationSettlement.afterAction` (so subscribers observe state changes as settled mutations) AFTER the keyUp dispatch
      * and only invokes `el.click()` if `document.activeElement` is one of those three activatable types.
      *
      * For all other elements (text inputs, links, body, etc.) the shim is a no-op so a literal Space character can still be inserted by
      * Chromium directly via the keyDown's `text:" "` field. `HTMLElement.click()` is itself a no-op for disabled elements per the HTML
      * spec.
      */
    private[kyo] def runSpaceClickSynthesis(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(
            """(() => {
              |  const el = document.activeElement;
              |  if (!el) return 'noop';
              |  if (el.tagName === 'BUTTON') { el.click(); return 'clicked'; }
              |  if (el.tagName === 'INPUT' && (el.type === 'checkbox' || el.type === 'radio')) { el.click(); return 'clicked'; }
              |  return 'noop';
              |})()""".stripMargin
        ).unit
    end runSpaceClickSynthesis

    private[kyo] def focusElement(selector: Selector)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        Actionability.requireResolved(selector) {
            val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
            BrowserEval.evalJs(s"""(() => {
                const el = $jsExpr;
                el.focus();
                return 'ok';
            })()""").unit
        }
    end focusElement

    /** Selector-scoped focus that preserves caret/selection state when the element is already focused.
      *
      * `Browser.press` calls this instead of [[focusElement]] so that repeated key presses against the same input do not reset the caret to
      * position 0 between presses (CDP `DOM.focus`, and `el.focus()` in JS, both reset the caret on every call). The probe asks the page
      * `el === document.activeElement`: when `'already'`, the focus call is skipped entirely; when `'needs_focus'`, [[focusElement]] runs
      * as usual.
      *
      * The unified `Browser.fill` shim leaves the input focused with caret at end, so the post-fill `press` flow naturally exercises the
      * `'already'` arm (caret stays at end, Backspace deletes the last character). For unfocused targets the existing focus behavior is
      * preserved verbatim.
      *
      * The probe runs inside `requireResolved`, so a missing-element selector aborts `BrowserElementNotFoundException` before the JS probe
      * is scheduled; `withActionable` upstream of this helper already aborts `NotAttached` ahead of either path.
      */
    private[kyo] def focusElementIfNotFocused(selector: Selector)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        Actionability.requireResolved(selector) {
            val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
            BrowserEval.evalJs(s"""(() => {
                const el = $jsExpr;
                return el && el === document.activeElement ? 'already' : 'needs_focus';
            })()""").map { result =>
                if result == "already" then ()
                else focusElement(selector)
            }
        }
    end focusElementIfNotFocused

    /** Unified JS-fallback `fill` shim used by [[Browser.fill]] for INPUT / TEXTAREA / contentEditable / SELECT targets. Resolves the
      * element, sets the value via the prototype-native `value` setter (bypassing framework-monkey-patched setters), dispatches the
      * appropriate events, and leaves the element focused with caret at the end of its value.
      *
      * SELECT branch: when `tag === 'SELECT'` the body assigns `el.value` and dispatches `change` only (no `input`, no `setSelectionRange`,
      * no prototype-setter trick), mirroring `Browser.select`'s semantics. Returns the boolean discriminator `wasSelect` so the caller can
      * skip the `verifyFilledValue` round-trip on SELECT (a missing option silently no-ops in `Browser.select`; running readback would
      * surface that as a FillDesync, which is a behaviour change relative to the `select`-delegation path).
      *
      * Fillable gate: the JS body itself short-circuits non-fillable, non-SELECT tags with `'not_fillable'` so that the actionability gate
      * at `Browser.fill` does not need to enforce `requireFillable`, preserving the SELECT pass-through while keeping
      * `<div>`/non-form-control rejection semantics intact (mapped to [[BrowserElementNotActionableException.Reason.NotFillable]] in Scala, with the actual
      * lowercase tagName extracted from `el.tagName.toLowerCase()` in the JS payload).
      *
      * The prototype-setter trick is required for React: React installs a custom `value` setter on the input prototype that synchronizes
      * its internal state machine; assigning `el.value = X` directly invokes React's setter and React then ignores the subsequent `input`
      * event. Looking up the descriptor from `HTMLInputElement.prototype` (or `HTMLTextAreaElement.prototype`) and calling its `.set`
      * directly bypasses React's override, so the `input` event fires after the DOM value has been set and React picks up the new value.
      *
      * `setSelectionRange` is wrapped in a `try/catch` because some input types (number, date, range, color) throw an `InvalidStateError`
      * when `setSelectionRange` is called; they have no selection model. Caret-at-end on those types is a no-op.
      */
    /** JSON wire shape returned by [[fillViaJs]]'s in-page IIFE. `tag` discriminates the outcome:
      *   - `"ok"`: fill succeeded on INPUT / TEXTAREA / contentEditable
      *   - `"ok-select"`: fill succeeded on a SELECT element via the value-assignment short-circuit
      *   - `"not_attached"`: selector matched nothing
      *   - `"not_fillable"`: matched a non-form-control element; `tagName` carries the offending element's tag name
      */
    private[internal] case class FillReply(tag: String, tagName: Maybe[String] = Absent) derives Schema

    private[kyo] def fillViaJs(selector: Selector, text: String)(using
        Frame
    ): Boolean < (Browser & Abort[BrowserReadException]) =
        val escaped = JsStringUtil.escapeJsString(text)
        val jsExpr  = SelectorJs.resolveElementJs(Selector.toNode(selector))
        BrowserEval.evalJs(s"""(() => {
            const el = $jsExpr;
            if (!el) return JSON.stringify({tag: 'not_attached'});
            const value = '$escaped';
            const tag = el.tagName;
            if (tag === 'SELECT') {
                el.value = value;
                el.dispatchEvent(new Event('change', { bubbles: true }));
                return JSON.stringify({tag: 'ok-select'});
            }
            if (!(tag === 'INPUT' || tag === 'TEXTAREA' || el.isContentEditable)) {
                return JSON.stringify({tag: 'not_fillable', tagName: (el.tagName || 'unknown').toLowerCase()});
            }
            const proto =
                tag === 'TEXTAREA' ? HTMLTextAreaElement.prototype :
                                     HTMLInputElement.prototype;
            const desc = Object.getOwnPropertyDescriptor(proto, 'value');
            if (desc && typeof desc.set === 'function') {
                desc.set.call(el, value);
            } else {
                el.value = value;
            }
            el.dispatchEvent(new Event('input',  { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            try { el.focus(); } catch (e) {}
            try { el.setSelectionRange(value.length, value.length); } catch (e) {}
            return JSON.stringify({tag: 'ok'});
        })()""").map { raw =>
            Json.decode[FillReply](raw) match
                case Result.Success(FillReply("not_fillable", tn)) =>
                    Abort.fail(
                        BrowserElementNotActionableException(
                            Browser.selectorNodeDescription(Selector.toNode(selector)),
                            BrowserElementNotActionableException.Reason.NotFillable(tn.getOrElse("unknown"))
                        )
                    )
                case Result.Success(FillReply("ok-select", _)) => true
                case _                                         => false
        }
    end fillViaJs

    /** Reads the element's `value` (or `textContent` for contentEditable) back from the page and compares against `expected`. If the
      * framework rejected or normalized the value away, raises [[BrowserElementNotActionableException]] with reason `FillDesync`. Inside
      * the enclosing `Retry` loop this triggers a retry; when the schedule exhausts the exception escapes as the final failure.
      *
      * Browser-normalized newlines: per the HTML spec, the `value` setter on `<textarea>` and `<input>` normalizes `\r\n` and `\r` to `\n`
      * before storing. For `<textarea>` newlines are then preserved verbatim, while `<input>` runs an extra value-sanitization step that
      * strips `\n` outright. The readback therefore needs to compare expected and actual under the same normalization, otherwise a fill
      * with newline content on an `<input>` always FillDesyncs. This is benign: the user's intent is preserved as far as the spec allows.
      */
    /** JSON wire shape returned by [[verifyFilledValue]]'s in-page IIFE. `tag` is the element's `tagName` (uppercase) or `"CE"` for
      * contentEditable hosts, `"OTHER"` for non-form-control elements, or `"__missing__"` when the selector matched nothing between the
      * fill and the readback.
      */
    private[internal] case class FillReadback(tag: String, value: String = "") derives Schema

    private[kyo] def verifyFilledValue(selector: Selector, expected: String)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        val jsExpr = SelectorJs.resolveElementJs(Selector.toNode(selector))
        BrowserEval.evalJs(s"""(() => {
            const el = $jsExpr;
            if (!el) return JSON.stringify({tag: '__missing__'});
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') return JSON.stringify({tag: el.tagName, value: String(el.value)});
            if (el.isContentEditable) return JSON.stringify({tag: 'CE', value: String(el.textContent)});
            return JSON.stringify({tag: 'OTHER', value: String(el.value != null ? el.value : (el.textContent || ''))});
        })()""").map { raw =>
            val baseDesc = Browser.selectorNodeDescription(Selector.toNode(selector))
            Json.decode[FillReadback](raw) match
                case Result.Success(FillReadback("__missing__", _)) =>
                    Abort.fail(
                        BrowserElementNotActionableException(
                            s"$baseDesc (attempted to fill '$expected' but the element disappeared between fill and readback)",
                            BrowserElementNotActionableException.Reason.FillDesync
                        )
                    )
                case Result.Success(FillReadback(tag, actual)) =>
                    // Normalize expected to match HTML's value-setter behaviour.
                    //   * `<textarea>` and contentEditable: `\r\n` and lone `\r` become `\n`; embedded `\n` is preserved.
                    //   * `<input>` (any type) and other elements: same `\r` normalization, then `\n` is stripped entirely
                    //     by the value-sanitization algorithm.
                    val crNormalized = expected.replace("\r\n", "\n").replace("\r", "\n")
                    val normalizedExpected =
                        if tag == "INPUT" then crNormalized.replace("\n", "")
                        else crNormalized
                    if actual == normalizedExpected then ()
                    else
                        Abort.fail(
                            BrowserElementNotActionableException(
                                s"$baseDesc (attempted to fill '$expected' but readback got '$actual'; the page rejected or normalized the value)",
                                BrowserElementNotActionableException.Reason.FillDesync
                            )
                        )
                    end if
                case _ =>
                    Abort.fail(
                        BrowserElementNotActionableException(
                            s"$baseDesc (verifyFilledValue: unexpected wire shape '$raw')",
                            BrowserElementNotActionableException.Reason.FillDesync
                        )
                    )
            end match
        }
    end verifyFilledValue

    /** Resolves to the active document's URL. Surface of `Browser.url`. */
    private[kyo] val urlJs: String = "window.location.href"

    /** Resolves to the active document's `<title>` text. Surface of `Browser.title`. */
    private[kyo] val titleJs: String = "document.title"

end ProbesJs
