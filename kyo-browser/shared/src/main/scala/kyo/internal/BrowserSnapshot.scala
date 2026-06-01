package kyo.internal

import CdpTypes.*
import kyo.*

/** Tab-state capture and restore primitives used by `Browser.withFork`, `Browser.isolate.fresh`, and `Browser.isolate.clone`.
  *
  * Captures a snapshot of the live tab (URL, localStorage / sessionStorage, cookies, form-field values, scroll position, focused element,
  * and caret position) then re-applies the same state to a child tab. JS templates inline the unique-to-snapshot in-page logic; the
  * snapshot path does NOT depend on [[SelectorJs]] or [[ProbesJs]].
  */
private[kyo] object BrowserSnapshot:

    /** Typed wire shape for a form field captured from the page (input / textarea / select). `checked` is `Present` only for checkbox /
      * radio inputs; other fields carry their `value`.
      */
    final private[kyo] case class FormField(
        id: String,
        `type`: String,
        value: String = "",
        checked: Maybe[Boolean] = Absent
    ) derives Schema, CanEqual

    /** Snapshot of a tab's page state for cloning. */
    final private[kyo] case class BrowserSnapshot(
        url: String,
        localStorage: Dict[String, String],
        sessionStorage: Dict[String, String],
        cookies: Chunk[Browser.Cookie],
        formFields: Chunk[FormField],
        scrollX: Int,
        scrollY: Int,
        focusedSelector: String,
        cursorPosition: String
    )

    /** Wire envelope returned by [[captureScript]]: collects every captured field into a single CDP call. The JS template emits storage and
      * formFields as typed JS object / array values (not stringified inner payloads), and they decode directly into typed Scala values via
      * Schema.
      *
      * Field defaults provide safe fallbacks: a missing `scrollX` / `scrollY` (e.g. on about:blank) decodes as 0; missing storage / form
      * fields decode as empty.
      */
    final private[internal] case class SnapshotEnvelope(
        url: String = "",
        localStorage: Dict[String, String] = Dict.empty,
        sessionStorage: Dict[String, String] = Dict.empty,
        formFields: Chunk[FormField] = Chunk.empty,
        scrollX: Int = 0,
        scrollY: Int = 0,
        focusedSelector: String = "",
        cursorPosition: String = ""
    ) derives Schema

    /** JS body for the snapshot envelope: collects URL, storage, form fields, scroll, focus, and cursor in a single round-trip. The
      * `storageToObj` helper explicitly enumerates Storage entries (since `JSON.stringify(Storage)` does NOT enumerate own keys in V8) and
      * returns `{}` on `SecurityError` / opaque origins so storage access never throws.
      */
    private val captureScript: String =
        """(() => {
            function storageToObj(name) {
                try {
                    const s = window[name];
                    const out = {};
                    for (let i = 0; i < s.length; i++) {
                        const k = s.key(i);
                        out[k] = s.getItem(k);
                    }
                    return out;
                } catch (e) { return {}; }
            }
            const fields = [];
            document.querySelectorAll('input,textarea,select').forEach(el => {
                const id = el.id || el.name;
                if (!id) return;
                if (el.type === 'checkbox' || el.type === 'radio') {
                    fields.push({id: id, type: el.type, checked: el.checked, value: el.value});
                } else {
                    fields.push({id: id, type: el.type || el.tagName.toLowerCase(), value: el.value});
                }
            });
            function focused() {
                const el = document.activeElement;
                if (!el || el === document.body) return '';
                if (el.id) return '#' + el.id;
                if (el.name) return '[name="' + el.name + '"]';
                const tag = el.tagName.toLowerCase();
                const parent = el.parentElement;
                if (!parent) return tag;
                const idx = Array.from(parent.children).indexOf(el);
                return tag + ':nth-child(' + (idx + 1) + ')';
            }
            function cursor() {
                const el = document.activeElement;
                if (!el || typeof el.selectionStart !== 'number') return '';
                return el.selectionStart + ',' + el.selectionEnd;
            }
            return JSON.stringify({
                url: window.location.href,
                localStorage: storageToObj('localStorage'),
                sessionStorage: storageToObj('sessionStorage'),
                formFields: fields,
                scrollX: window.scrollX | 0,
                scrollY: window.scrollY | 0,
                focusedSelector: focused(),
                cursorPosition: cursor()
            });
        })()"""

    /** Captures a snapshot of the tab's current state including cookies and form fields.
      *
      * Single JS round-trip via [[captureScript]] for the seven page-state fields, plus a CDP `Network.getCookies` call (which cannot be
      * folded into the JS envelope; cookies are HTTP-jar state, not page state). The Schema-derived [[SnapshotEnvelope]] decodes `scrollX`
      * / `scrollY` as typed `Int`s; defaults preserve fallback-to-zero semantics on about:blank / opaque origins.
      */
    private[kyo] def captureSnapshot(tab: BrowserTab)(using Frame): BrowserSnapshot < (Async & Abort[BrowserReadException]) =
        for
            envelopeJson <- CdpBackend.runtimeEvaluate(
                tab.session,
                EvalParams(captureScript)
            ).map(CdpEvalDecoder.parseAndExtractEvalValue)
            envelope      <- decodeSnapshotEnvelope(envelopeJson)
            cookiesResult <- CdpBackend.getCookies(tab.session)
            cookies = Chunk.from(cookiesResult.cookies.map(CookieWire.toCookie)): Chunk[Browser.Cookie]
        yield BrowserSnapshot(
            envelope.url,
            envelope.localStorage,
            envelope.sessionStorage,
            cookies,
            envelope.formFields,
            envelope.scrollX,
            envelope.scrollY,
            envelope.focusedSelector,
            envelope.cursorPosition
        )

    /** Decodes the snapshot envelope, surfacing wire-shape drift as a typed [[BrowserConnectionException]]. Mirrors the
      * `NavigationWatcher.decodeSettleState` / `CdpEvalEnvelope.decodeEvalEnvelope` pattern.
      */
    private[internal] def decodeSnapshotEnvelope(json: String)(using Frame): SnapshotEnvelope < Abort[BrowserReadException] =
        Json.decode[SnapshotEnvelope](json) match
            case Result.Success(env) => env
            case Result.Failure(err) =>
                Abort.fail(BrowserProtocolErrorException("captureSnapshot", s"snapshot wire decode failed: ${err.getMessage}"))
            case Result.Panic(t) =>
                Abort.fail(BrowserProtocolErrorException("captureSnapshot", s"snapshot wire decode panicked: ${t.getMessage}"))
    end decodeSnapshotEnvelope

    /** Restores a snapshot (URL + storage + cookies + form fields) onto a tab. */
    private[kyo] def restoreSnapshot(tab: BrowserTab, snapshot: BrowserSnapshot)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        // Bound as `def` (not `val`) so that dropping any binding (e.g. `_ <- navigate`) surfaces
        // as an unused-def compiler warning rather than a silently-unevaluated val.
        def navigate: Unit < (Async & Abort[BrowserReadException]) =
            if snapshot.url != "about:blank" then
                Browser.configLocal.use { cfg =>
                    tab.session.sendUnit("Page.navigate", NavigateParams(snapshot.url))
                        .andThen(NavigationWatcher.waitForLoad(tab, cfg.loadSchedule))
                }
            else (): Unit < (Async & Abort[BrowserReadException])
        def restoreFocus: String < (Async & Abort[BrowserReadException]) =
            if snapshot.focusedSelector.nonEmpty then
                val escaped = JsStringUtil.escapeJsString(snapshot.focusedSelector)
                CdpBackend.runtimeEvaluate(
                    tab.session,
                    EvalParams(s"""(() => {
                        const el = document.querySelector('$escaped');
                        if (el) el.focus();
                    })()""")
                ).map(CdpEvalDecoder.parseAndExtractEvalValue)
            else
                CdpBackend.runtimeEvaluate(
                    tab.session,
                    EvalParams("void 0")
                ).map(CdpEvalDecoder.parseAndExtractEvalValue)
        def restoreCursor: Unit < (Async & Abort[BrowserReadException]) =
            if snapshot.cursorPosition.nonEmpty && snapshot.focusedSelector.nonEmpty then
                val escapedSelector = JsStringUtil.escapeJsString(snapshot.focusedSelector)
                val escapedCursor   = JsStringUtil.escapeJsString(snapshot.cursorPosition)
                CdpBackend.runtimeEvaluate(
                    tab.session,
                    EvalParams(s"""(() => {
                        const el = document.querySelector('$escapedSelector');
                        if (el && typeof el.setSelectionRange === 'function') {
                            const parts = '$escapedCursor'.split(',');
                            el.setSelectionRange(parseInt(parts[0]), parseInt(parts[1]));
                        }
                    })()""")
                ).map(CdpEvalDecoder.parseAndExtractEvalValue).unit
            else
                CdpBackend.runtimeEvaluate(
                    tab.session,
                    EvalParams("void 0")
                ).map(CdpEvalDecoder.parseAndExtractEvalValue).unit
        for
            _ <- navigate
            _ <- restoreStorage(tab, snapshot.localStorage, snapshot.sessionStorage)
            _ <- restoreCookies(tab, snapshot.cookies)
            _ <- restoreFormFields(tab, snapshot.formFields)
            _ <- CdpBackend.runtimeEvaluate(
                tab.session,
                EvalParams(s"window.scrollTo(${snapshot.scrollX}, ${snapshot.scrollY})")
            ).map(CdpEvalDecoder.parseAndExtractEvalValue)
            _ <- restoreFocus
            _ <- restoreCursor
        yield ()
        end for
    end restoreSnapshot

    /** Restores localStorage and sessionStorage on a tab.
      *
      * Each Dict is JSON-encoded once on the Scala side and inlined as a JS object literal (`const d = {...}`); the in-page code iterates
      * its own-properties and `setItem`s each entry. No `JSON.parse('$escaped')` detour, no `JsStringUtil.escapeJsString` on the storage
      * path.
      */
    private[kyo] def restoreStorage(tab: BrowserTab, localStorage: Dict[String, String], sessionStorage: Dict[String, String])(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        val localJs   = Json.encode(localStorage)
        val sessionJs = Json.encode(sessionStorage)
        def restoreOne(slot: String, payloadJs: String): String =
            s"""(() => {
                const d = $payloadJs;
                if (d && typeof d === 'object' && !Array.isArray(d)) {
                    Object.entries(d).forEach(([k,v]) => $slot.setItem(k, v));
                }
            })()"""
        CdpBackend.runtimeEvaluate(
            tab.session,
            EvalParams(restoreOne("localStorage", localJs))
        ).map(CdpEvalDecoder.parseAndExtractEvalValue).andThen {
            CdpBackend.runtimeEvaluate(
                tab.session,
                EvalParams(restoreOne("sessionStorage", sessionJs))
            ).map(CdpEvalDecoder.parseAndExtractEvalValue).unit
        }
    end restoreStorage

    /** Restores cookies on a tab via CDP Network.setCookie.
      *
      * Delegates to the public `setCookie(Cookie)` overload running against the explicit `tab` via `runOn`, so the
      * `Cookie → NetworkSetCookieParams` mapping lives in exactly one place. `Kyo.foreachDiscard` avoids building the intermediate
      * `Chunk[Unit]` that `Kyo.foreach { ... }.unit` would allocate.
      */
    private[kyo] def restoreCookies(tab: BrowserTab, cookies: Chunk[Browser.Cookie])(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        Kyo.foreachDiscard(cookies)(c => Browser.runOn(tab)(Browser.setCookie(c)))

    /** Restores form field values on a tab via JavaScript. The typed `Chunk[FormField]` is JSON-encoded once on the Scala side and inlined
      * as a JS array literal; the in-page code iterates and dispatches input/change events on each matching element.
      */
    private[kyo] def restoreFormFields(tab: BrowserTab, formFields: Chunk[FormField])(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        if formFields.isEmpty then
            (): Unit < (Async & Abort[BrowserReadException])
        else
            val payloadJs = Json.encode(formFields)
            CdpBackend.runtimeEvaluate(
                tab.session,
                EvalParams(s"""(() => {
                const fields = $payloadJs;
                fields.forEach(f => {
                    const el = document.getElementById(f.id) || document.querySelector('[name="' + f.id + '"]');
                    if (!el) return;
                    if (f.type === 'checkbox' || f.type === 'radio') {
                        el.checked = f.checked;
                    } else {
                        el.value = f.value;
                    }
                    el.dispatchEvent(new Event('input', {bubbles: true}));
                    el.dispatchEvent(new Event('change', {bubbles: true}));
                });
                return 'ok';
            })()""")
            ).map(CdpEvalDecoder.parseAndExtractEvalValue).unit
        end if
    end restoreFormFields

end BrowserSnapshot
