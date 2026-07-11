package kyo.internal

import kyo.*

/** Hold-still capture: a TARGET convention (the legacy `Browser.screenshot` is a plain direct
  * capture that predates hold-still; each public capture method that adopts this convention notes
  * it in scaladoc). This helper wraps any base capture thunk and is best-effort: it NEVER aborts
  * on timeout (returns the last frame). Order of operations:
  *
  *   1. Best-effort `settleForCapture` (pre-capture DOM settle, proceeds on timeout).
  *   2. Await `document.fonts.ready` via `evalJsAwaiting` (the expression returns a Promise).
  *   3. Inject a `data-kyo-internal` freeze `<style>` (animations/transitions paused, caret
  *      hidden; removed via `Scope.acquireRelease` on success/failure/interruption).
  *   4. Loop the base capture until two consecutive frames share a `frameHash` or
  *      `captureHoldStillTimeout` elapses; returns the last frame.
  *
  * The cross-CDP per-frame loop (paced by `Clock.sleep(captureHoldStillInterval)`) is exempt from
  * the in-page `awaitPromise` single-eval constraint: that constraint binds only
  * in-page transient loops. The JS-side font/freeze evals are each a single non-splitting eval.
  * The hold-still loop is NOT an in-page transient loop; each iteration issues a full CDP
  * round-trip.
  *
  * `Async` used internally (`Clock.sleep`, capture suspension) is absorbed by the `Browser` effect
  * at the `Browser.run` boundary and does not appear in callers' public rows (`Browser <:
  * Async` at the run boundary; the locked public signatures stay `Browser &
  * Abort[BrowserReadException]` without `Async`).
  *
  * The freeze `<style data-kyo-internal>` injection produces ONE unfiltered `childList` mutation
  * because the insertion targets the untagged `document.head` parent. A live `settleForCapture`
  * gate tolerates this single mutation as one quiet-window; `settleForCapture` runs BEFORE the
  * injection (step 1 above), so the mutation lands within its quiet-window. HoldStill convergence
  * is FRAME-HASH based (two byte-identical consecutive frames), not settlement-gate based, so the
  * injection mutation does not break convergence.
  *
  * For multi-band captures (e.g. full-page strips) use `withFrozenPage` to settle and freeze ONCE
  * around the whole loop, then call `holdStillFrame` per band. This guarantees all bands are
  * captured from the same frozen animation state, preventing visual inconsistencies between strips.
  * `withHoldStill` remains the single-call form: settle + freeze + hold-still loop + teardown.
  */
private[kyo] object HoldStill:

    /** Hash of the DECODED image bytes.
      *
      * `Image.hashCode` is overridden to use XXH32 over the underlying byte array, giving
      * content-equality semantics. Using `img.hashCode` (not `img.binary.hashCode`) is correct
      * because `Span[Byte]` is opaque over `Array[Byte]` and `Array.hashCode` is reference identity,
      * meaning two byte-identical frames would never match with `img.binary.hashCode`, making the
      * loop never converge on identical content.
      * `Image.hashCode` already implements the content hash the loop needs.
      */
    private[kyo] def frameHash(img: Image): Int = img.hashCode

    /** Injects the freeze `<style>`, tagging it with a unique `data-kyo-token` minted from an in-page monotonic sequence, and returns
      * that token. The matching [[removeFreezeStyleJs]] removes only the node carrying the returned token rather than a shared global
      * slot, so nested or interleaved freezes each lift exactly their own style node: an inner freeze's teardown cannot leave the outer
      * freeze stuck (a leaked page-wide `animation-play-state: paused`).
      */
    private[kyo] val freezeStyleJs: String =
        """(() => {
            const s = document.createElement('style');
            const token = String((window.__kyoOverlayToken = (window.__kyoOverlayToken || 0) + 1));
            s.setAttribute('data-kyo-internal', 'freeze');
            s.setAttribute('data-kyo-token', token);
            s.textContent = [
                '*, *::before, *::after { animation-play-state: paused !important; transition: none !important; }',
                'input, textarea, [contenteditable] { caret-color: transparent !important; }'
            ].join(' ');
            document.head.appendChild(s);
            return token;
        })()"""

    private[kyo] def removeFreezeStyleJs(token: String): String =
        val escaped = JsStringUtil.escapeJsString(token)
        s"""(() => {
            document.querySelectorAll('[data-kyo-token="$escaped"]').forEach(n => n.remove());
            return 'unfrozen';
        })()"""
    end removeFreezeStyleJs

    /** Awaits `document.fonts.ready` via an async promise eval (`evalJsAwaiting`, not `evalJs`,
      * since the expression returns a Promise). Wrapped in try/catch so a page without a fonts API
      * does not abort the capture.
      */
    private[kyo] val fontsReadyJs: String =
        "(async () => { try { await document.fonts.ready; } catch (e) {} return 'ready'; })()"

    /** Settles the page for capture, awaits fonts, and injects the freeze stylesheet for the
      * duration of `body`. The freeze style is removed via `Scope.acquireRelease` on
      * success/failure/interruption. This is the setup half of the hold-still protocol; use it
      * directly when multiple captures need to share a single frozen animation state (e.g. full-page
      * bands). Pair with `holdStillFrame` per individual capture.
      */
    private[kyo] def withFrozenPage[A, S](
        body: => A < (Browser & Abort[BrowserReadException] & S)
    )(using Frame): A < (Browser & Abort[BrowserReadException] & S) =
        MutationSettlement.settleForCapture.andThen {
            BrowserEval.evalJsAwaiting(fontsReadyJs).andThen {
                Browser.use { tab =>
                    Scope.run {
                        Scope.acquireRelease(BrowserEval.evalJs(freezeStyleJs)) { token =>
                            Browser.releaseHook(tab)(BrowserEval.evalJs(removeFreezeStyleJs(token)).unit)
                        }.andThen(body)
                    }
                }
            }
        }
    end withFrozenPage

    /** Runs `capture` in the two-identical-frames hold-still loop, assuming the page is already
      * settled and frozen (i.e. called inside `withFrozenPage`). Reads
      * `captureHoldStillTimeout`/`captureHoldStillInterval` from `Browser.configLocal`. Never
      * aborts on timeout; returns the last captured frame.
      */
    private[kyo] def holdStillFrame[S](
        capture: => Image < (Browser & Abort[BrowserReadException] & S)
    )(using Frame): Image < (Browser & Abort[BrowserReadException] & S) =
        Browser.configLocal.use { cfg =>
            val timeout  = cfg.captureHoldStillTimeout
            val interval = cfg.captureHoldStillInterval
            Clock.nowMonotonic.map { start =>
                capture.map { first =>
                    Loop(first, frameHash(first)) { (prev, prevHash) =>
                        Clock.nowMonotonic.map { now =>
                            if now - start >= timeout then Loop.done(prev)
                            else
                                Clock.sleep(interval).andThen {
                                    capture.map { next =>
                                        val h = frameHash(next)
                                        if h == prevHash then Loop.done(next)
                                        else Loop.continue(next, h)
                                    }
                                }
                        }
                    }
                }
            }
        }
    end holdStillFrame

    /** Awaits fonts.ready, injects the freeze style (removed on scope exit), then loops `capture`
      * until `frameHash` repeats or `captureHoldStillTimeout` elapses; returns the last captured
      * frame. The loop reads `captureHoldStillTimeout`/`captureHoldStillInterval` from
      * `Browser.configLocal`. Never aborts on timeout.
      *
      * Equivalent to `withFrozenPage { holdStillFrame(capture) }`. Use this for single captures;
      * use `withFrozenPage` + `holdStillFrame` directly when capturing multiple bands that must all
      * reflect the same frozen animation state.
      */
    private[kyo] def withHoldStill[S](
        capture: => Image < (Browser & Abort[BrowserReadException] & S)
    )(using Frame): Image < (Browser & Abort[BrowserReadException] & S) =
        withFrozenPage(holdStillFrame(capture))
    end withHoldStill

end HoldStill
