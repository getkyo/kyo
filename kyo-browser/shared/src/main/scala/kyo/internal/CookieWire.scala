package kyo.internal

import CdpTypes.*
import kyo.*

/** Wire-format representation of a CDP `Network.Cookie`.
  *
  * `CookieWire` mirrors the raw shape Chrome reports across the DevTools Protocol byte-for-byte; `expires` is the seconds-since-epoch
  * `Double` that CDP sends.
  *
  * The public [[kyo.Browser.Cookie]] type uses [[kyo.Instant]] for `expires` instead of the raw `Double`. Conversion happens at the CDP
  * boundary via [[CookieWire.fromCookie]] (outbound, when serialising a public `Cookie` to a CDP request) and [[CookieWire.toCookie]]
  * (inbound, when decoding a CDP response into the public type).
  */
final private[kyo] case class CookieWire(
    name: String,
    value: String,
    domain: Maybe[String] = Absent,
    path: Maybe[String] = Absent,
    expires: Maybe[Double] = Absent,
    size: Maybe[Int] = Absent,
    httpOnly: Maybe[Boolean] = Absent,
    secure: Maybe[Boolean] = Absent,
    sameSite: Maybe[String] = Absent
) derives Schema

private[kyo] object CookieWire:

    /** Converts a public [[kyo.Browser.Cookie]] into the wire-format [[CookieWire]].
      *
      * `Maybe[Instant]` collapses to `Maybe[Double]` (seconds-since-epoch with fractional component preserved from the `Instant`'s
      * nanosecond field). The `expires` mapping is the inverse of [[toCookie]].
      */
    def fromCookie(c: kyo.Browser.Cookie): CookieWire =
        // CDP `Network.setCookie` has no `size` field; the public `Cookie` no longer carries one either,
        // so the wire `size` is always `Absent` on the outbound path.
        CookieWire(
            name = c.name,
            value = c.value,
            domain = c.domain,
            path = c.path,
            expires = c.expires.map(instantToEpochSeconds),
            httpOnly = c.httpOnly,
            secure = c.secure,
            sameSite = c.sameSite.map(_.wire)
        )

    /** Converts a wire-format [[CookieWire]] into the public [[kyo.Browser.Cookie]].
      *
      * `Maybe[Double]` lifts to `Maybe[Instant]` (seconds-since-epoch with fractional component restored as nanoseconds on the resulting
      * `Instant`).
      *
      * CDP encodes session cookies as `expires = -1` (per the
      * [Network.Cookie](https://chromedevtools.github.io/devtools-protocol/tot/Network/#type-Cookie) schema). A negative `expires`
      * therefore collapses to `Absent` at the boundary so the public type carries the same "no expiry" semantics regardless of whether the
      * wire was `null`/missing or `-1`.
      */
    def toCookie(w: CookieWire): kyo.Browser.Cookie =
        // `size` on the wire is informational (Chrome's aggregate of name + value byte counts) and is
        // dropped from the public `Cookie` shape; it has no meaning on the outbound path.
        val expiresInstant = w.expires.filter(_ >= 0.0).map(epochSecondsToInstant)
        kyo.Browser.Cookie(
            name = w.name,
            value = w.value,
            domain = w.domain,
            path = w.path,
            expires = expiresInstant,
            httpOnly = w.httpOnly,
            secure = w.secure,
            sameSite = w.sameSite.flatMap(kyo.Browser.Cookie.SameSite.parse)
        )
    end toCookie

    /** Splits a fractional epoch-seconds `Double` into integer-seconds + nanoseconds and constructs an [[Instant]]. The fractional split
      * preserves CDP's sub-second precision (Chromium emits cookie expiry with up to microsecond resolution).
      */
    private def epochSecondsToInstant(seconds: Double): kyo.Instant =
        val whole = math.floor(seconds).toLong
        val nanos = math.round((seconds - whole.toDouble) * 1e9).toLong
        kyo.Instant.of(whole.seconds, nanos.nanos)
    end epochSecondsToInstant

    /** Re-flattens an [[Instant]] back into the seconds-since-epoch `Double` shape CDP expects. */
    private def instantToEpochSeconds(instant: kyo.Instant): Double =
        val j = instant.toJava
        j.getEpochSecond.toDouble + j.getNano.toDouble / 1e9
end CookieWire

/** JSON wire shape returned by `Browser.tryAcceptCookies`'s in-page IIFE. The `tag` discriminates the outcome (`"none"`, `"accepted"`,
  * `"timeout"`); `selector` is the CSS path of the dismissed banner element when `tag == "accepted"`.
  */
final private[kyo] case class CookieBannerReply(tag: String, selector: Maybe[String] = Absent) derives Schema

/** Best-effort cookie-banner dismissal. The body is purely JS heuristics (try a few common selectors, click, poll until the banner is gone)
  * shipped to the page in one `Runtime.evaluate`; the Scala-side `tryAcceptCookies` wrapper is the entry point on the public surface.
  */
private[kyo] object CookieBanner:

    /** Fallback for the cookie-banner removal wait when the active `loadSchedule` does not carry a finite max-duration
      * (the same degenerate `Schedule.never` edge case as [[NavigationWatcher.defaultLoadScheduleTimeout]]). Named so the
      * 5-second window is auditable in one place.
      */
    private[kyo] val defaultCookieBannerDeadline: Duration = 5.seconds

    /** Implementation of `Browser.tryAcceptCookies(schedule)`. `Maybe[Selector]` carries the CSS selector matched on success wrapped via
      * [[Selector.css]]; `Absent` means no banner was visible to begin with.
      */
    def tryAcceptCookiesWithSchedule(schedule: Maybe[Schedule])(using
        Frame
    ): Maybe[Selector] < (Browser & Abort[BrowserReadException]) =
        Browser.configLocal.use { cfg =>
            val effectiveSchedule = schedule.getOrElse(cfg.loadSchedule)
            val d                 = NavigationWatcher.loadScheduleTimeout(effectiveSchedule)
            val deadlineMs        = if d.isFinite then d.toMillis else CookieBanner.defaultCookieBannerDeadline.toMillis
            val js = s"""(async () => {
                const selectors = [
                    "[id*='accept'][id*='cookie' i]",
                    "[class*='accept'][class*='cookie' i]",
                    "button[id*='accept' i]",
                    "button[class*='accept' i]",
                    "[aria-label*='accept' i]",
                    "[data-testid*='accept' i]"
                ];
                let matched = null;
                let matchedSel = null;
                for (const sel of selectors) {
                    const el = document.querySelector(sel);
                    if (el && el.offsetParent !== null) {
                        matched = el;
                        matchedSel = sel;
                        break;
                    }
                }
                if (!matched) return JSON.stringify({tag: 'none'});
                matched.click();
                const deadlineAt = Date.now() + ${deadlineMs};
                const sleep = (ms) => new Promise(r => setTimeout(r, ms));
                while (true) {
                    const el = document.querySelector(matchedSel);
                    if (!el || el.offsetParent === null || getComputedStyle(el).visibility === 'hidden') {
                        return JSON.stringify({tag: 'accepted', selector: matchedSel});
                    }
                    if (Date.now() >= deadlineAt) return JSON.stringify({tag: 'timeout'});
                    await sleep(50);
                }
            })()"""
            Browser.use[Maybe[Selector], Async & Abort[BrowserReadException]] { tab =>
                CdpBackend.runtimeEvaluate(tab.session, EvalParams(js, returnByValue = true, awaitPromise = true))
                    .map { env =>
                        CdpEvalDecoder.extractEvalValue(env).map { raw =>
                            Json.decode[CookieBannerReply](raw) match
                                case Result.Success(CookieBannerReply("none", _)) => Maybe.empty[Selector]
                                case Result.Success(CookieBannerReply("accepted", Present(sel))) =>
                                    Present(Selector.css(sel))
                                case Result.Success(CookieBannerReply("timeout", _)) =>
                                    Abort.fail(BrowserAssertionTimedOutException("banner removed", "banner still present"))
                                case _ => Abort.fail(BrowserProtocolErrorException.unexpectedReply("tryAcceptCookies", raw))
                        }
                    }
            }
        }
end CookieBanner
