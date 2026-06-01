package kyo.internal

import kyo.*

/** Shared installers for in-page fetch / XHR JS trackers.
  *
  * Hosts two installers:
  *   - Network-activity tracker (`ensureInstalled`): installs the `__kyoNetTrackingInstalled` / `__kyoNetPending` / `__kyoNetLastActivity`
  *     counters used by [[Browser.waitForNetworkIdle]] and the `NavigationWatcher` `NetworkIdle` settle path.
  *   - Response-URL observer (`ensureResponseTrackingInstalled`): installs the `__kyoResponseTrackingInstalled` / `__kyoResponseObserved`
  *     interceptors used by [[Browser.waitForRequestUrl]].
  *
  * Both installers are centralised here to keep payloads byte-identical across call sites and to avoid silent drift. Idempotency is
  * guaranteed at the JS layer via the respective `__kyoXxxInstalled` flags: the IIFEs short-circuit on repeat invocations, so concurrent
  * callers on the same tab are safe with no Scala-side mutex required.
  */
private[kyo] object BrowserNetworkTracker:

    /** JS payload that installs fetch/XHR network-activity counters. Idempotent via the in-page `__kyoNetTrackingInstalled` flag; repeat
      * invocations short-circuit at the JS layer.
      */
    private val installerScript: String =
        """(() => {
            if (window.__kyoNetTrackingInstalled) return 'ok';
            window.__kyoNetTrackingInstalled = true;
            window.__kyoNetPending = 0;
            window.__kyoNetLastActivity = Date.now();
            const origFetch = window.fetch;
            window.fetch = function() {
                window.__kyoNetPending++;
                window.__kyoNetLastActivity = Date.now();
                return origFetch.apply(this, arguments).then(
                    r => { window.__kyoNetPending--; window.__kyoNetLastActivity = Date.now(); return r; },
                    e => { window.__kyoNetPending--; window.__kyoNetLastActivity = Date.now(); throw e; }
                );
            };
            const origOpen = XMLHttpRequest.prototype.open;
            const origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function() { this.__kyoTracked = true; return origOpen.apply(this, arguments); };
            XMLHttpRequest.prototype.send = function() {
                if (this.__kyoTracked) {
                    window.__kyoNetPending++;
                    window.__kyoNetLastActivity = Date.now();
                    this.addEventListener('loadend', () => {
                        window.__kyoNetPending--;
                        window.__kyoNetLastActivity = Date.now();
                    });
                }
                return origSend.apply(this, arguments);
            };
            return 'ok';
        })()"""

    /** Installs the JS-level fetch/XHR tracker if not already present. Idempotent; safe to call from any number of producers. */
    def ensureInstalled(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(installerScript).unit

    /** JS payload that installs the fetch/XHR response-URL observer used by `Browser.waitForRequestUrl`. Idempotent via the in-page
      * `__kyoResponseTrackingInstalled` flag; repeat invocations short-circuit at the JS layer.
      *
      * Registered via `Page.addScriptToEvaluateOnNewDocument` (alongside `installConsoleCapture`) so the observer is in place BEFORE any
      * page JS runs, guaranteeing every fetch/XHR fired by the page is recorded, including XHRs that fire during the action immediately
      * preceding the user's `waitForRequestUrl` call. Without this, the observer installs lazily on first call and misses XHRs that fired
      * before it.
      */
    private[kyo] val responseTrackerScript: String =
        """(() => {
            if (window.__kyoResponseTrackingInstalled) return 'ok';
            window.__kyoResponseTrackingInstalled = true;
            window.__kyoResponseObserved = window.__kyoResponseObserved || [];
            const origFetch = window.fetch;
            window.fetch = function(input, init) {
                const url = typeof input === 'string' ? input : (input && input.url) || '';
                return origFetch.apply(this, arguments).then(r => {
                    window.__kyoResponseObserved.push(url);
                    return r;
                });
            };
            const origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.__kyoUrl = url;
                this.addEventListener('loadend', () => {
                    window.__kyoResponseObserved.push(String(url));
                });
                return origOpen.apply(this, arguments);
            };
            return 'ok';
        })()"""

    /** Installs the JS-level fetch/XHR response-URL observer if not already present. Idempotent; safe to call from any number of producers.
      * Used by `Browser.waitForRequestUrl`.
      */
    def ensureResponseTrackingInstalled(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        BrowserEval.evalJs(responseTrackerScript).unit

    /** Wait-loop body for `Browser.waitForNetworkIdle`. Retries until the in-page tracker reports no pending requests AND
      * `(now - lastActivity) >= idle`, or the schedule exhausts (raising `BrowserAssertionTimedOutException`).
      */
    def waitForNetworkIdleFor(idle: Duration, schedule: Maybe[Schedule])(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        ensureInstalled.andThen {
            val idleMs = idle.toMillis
            Browser.configLocal.use { cfg =>
                val effectiveSchedule = schedule.getOrElse(cfg.retrySchedule)
                Retry[BrowserAssertionException](effectiveSchedule) {
                    BrowserEval.evalJs(s"""(() => {
                        const pending = window.__kyoNetPending || 0;
                        const lastActivity = window.__kyoNetLastActivity || 0;
                        const now = Date.now();
                        if (pending === 0 && lastActivity > 0 && (now - lastActivity) >= $idleMs) return 'idle';
                        if (pending === 0 && lastActivity === 0) {
                            window.__kyoNetLastActivity = now;
                            return 'waiting';
                        }
                        return 'pending:' + pending;
                    })()""").map { result =>
                        if result == "idle" then ()
                        else
                            Abort.fail(
                                BrowserAssertionTimedOutException("idle", result)
                            )
                    }
                }
            }
        }
end BrowserNetworkTracker
