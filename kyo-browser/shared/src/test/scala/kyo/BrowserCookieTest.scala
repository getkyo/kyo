package kyo

import kyo.internal.CookieWire

class BrowserCookieTest extends BrowserTest:

    override def timeout = 90.seconds

    private val base = """{"name":"n","value":"v"}"""

    // Matches the fixture's setTimeout(...remove..., 250) inside `tryAcceptCookies waits for banner removal`.
    private val cookieRemovalDelayMs: Long = 250L
    // Lower-bound floor for the timing assertion: removalDelay minus 50ms slack for scheduling jitter.
    private val cookieRemovalFloorMs: Long = cookieRemovalDelayMs - 50 // = 200

    /** Asserts the shared-Chrome cookie jar is empty (call AFTER `cleanupCookieJar`). Proves the prelude wipe
      * landed; also surfaces shared-Chrome contamination if a future API change breaks `cleanupCookieJar`.
      */
    private def assertEmptyCookieJar(using Frame): Unit < (Browser & Async & Abort[BrowserReadException]) =
        Browser.cookies.map { cs =>
            assert(cs.isEmpty, s"global cookie jar not empty at test start (after wipe): ${cs.map(_.name)}")
            ()
        }

    /** Wipes every cookie currently in the jar. Iterates `Browser.cookies` and calls `deleteCookie(name, domain)`
      * per entry; for cookies with `Absent` domain the no-domain overload is used. Used as a per-test prelude
      * inside `withBrowserOnLocalhost` to defend against shared-Chrome cookie-jar contamination from prior tests
      * (since shared-Chrome persists state across `Browser.run` cycles).
      *
      * Trade-off: this is a PRELUDE wipe, not a teardown. It catches contamination left by the previous test on
      * the NEXT test's prelude (the standard happy-path defence). It does NOT run on the current test's abort
      * path, so a test that aborts mid-flight leaves its cookies in the shared jar until the next test's prelude
      * sweeps them. A `Scope.ensure`-based teardown would close that on-abort gap, at the cost of duplicating the
      * sweep across every test exit. The prelude form was chosen because the sweep is idempotent and the next
      * test in any contaminated-jar scenario will still observe a clean slate before doing its work.
      */
    private def cleanupCookieJar(using Frame): Unit < (Browser & Async & Abort[BrowserReadException]) =
        Browser.cookies.map { cs =>
            Kyo.foreachDiscard(cs) { c =>
                c.domain match
                    case Present(d) => Browser.deleteCookie(c.name, d)
                    case Absent     => Browser.deleteCookie(c.name)
            }
        }

    // ---- expires (CookieWire is the JSON-derived wire shape; Browser.Cookie maps Maybe[Double] → Maybe[Instant]) ----

    "expires present decodes as Present(Double)" in {
        val json = """{"name":"n","value":"v","expires":1234567890}"""
        val wire = decode[CookieWire](json)
        assert(wire.expires == Present(1234567890.0))
        succeed
    }

    "expires absent decodes as Absent" in {
        val wire = decode[CookieWire](base)
        assert(wire.expires == Absent)
        succeed
    }

    // CDP encodes session cookies as `expires:-1`. The CookieWire decoder accepts the literal -1 value
    // (Present(-1.0) at the raw wire level); the collapse to Absent happens at the typed Cookie boundary in
    // CookieWire.toCookie, where `w.expires.filter(_ >= 0.0)` drops negative values. Pin both layers so a
    // future regression that drops the filter (re-surfacing `-1.0` as a real Instant in the past) is caught.
    "CookieWire expires:-1 (Chrome's session-cookie sentinel) decodes to Present(-1.0) at the wire and collapses to Absent in the typed Cookie" in {
        val json = """{"name":"n","value":"v","expires":-1}"""
        val wire = decode[CookieWire](json)
        assert(wire.expires == Present(-1.0), s"expected wire expires == Present(-1.0) but got ${wire.expires}")
        val cookie = CookieWire.toCookie(wire)
        assert(
            cookie.expires == Absent,
            s"expected typed Cookie.expires == Absent (negative wire is the session-cookie sentinel) but got ${cookie.expires}"
        )
        succeed
    }

    // All-fields end-to-end round-trip: decode a fully-populated CookieWire JSON, project to the typed Cookie
    // via CookieWire.toCookie, and pin every public field against the wire input. Guards against a Schema
    // regression in any single field (e.g. a corner combination silently dropping httpOnly or sameSite).
    "CookieWire all-fields decode + toCookie round-trips every populated field into the typed Cookie" in {
        // expires=2000000000 (year 2033) is a fixed, far-future seconds-since-epoch value so the Instant round-trip
        // is deterministic across test runs.
        val json =
            """{"name":"full","value":"V","domain":"example.com","path":"/p","expires":2000000000,"size":42,"httpOnly":true,"secure":false,"sameSite":"Lax"}"""
        val wire = decode[CookieWire](json)
        // wire fields
        assert(wire.name == "full", s"wire.name=${wire.name}")
        assert(wire.value == "V", s"wire.value=${wire.value}")
        assert(wire.domain == Present("example.com"), s"wire.domain=${wire.domain}")
        assert(wire.path == Present("/p"), s"wire.path=${wire.path}")
        assert(wire.expires == Present(2000000000.0), s"wire.expires=${wire.expires}")
        assert(wire.size == Present(42), s"wire.size=${wire.size}")
        assert(wire.httpOnly == Present(true), s"wire.httpOnly=${wire.httpOnly}")
        assert(wire.secure == Present(false), s"wire.secure=${wire.secure}")
        assert(wire.sameSite == Present("Lax"), s"wire.sameSite=${wire.sameSite}")

        val cookie = CookieWire.toCookie(wire)
        // typed Cookie fields (size is dropped from the public shape; expires is lifted into Instant)
        assert(cookie.name == "full", s"cookie.name=${cookie.name}")
        assert(cookie.value == "V", s"cookie.value=${cookie.value}")
        assert(cookie.domain == Present("example.com"), s"cookie.domain=${cookie.domain}")
        assert(cookie.path == Present("/p"), s"cookie.path=${cookie.path}")
        cookie.expires match
            case Present(inst) =>
                val secs = inst.toJava.getEpochSecond
                assert(secs == 2000000000L, s"expected expires epoch-seconds == 2000000000 but got $secs")
            case Absent => fail(s"expected Present(Instant) but got Absent for cookie.expires")
        end match
        assert(cookie.httpOnly == Present(true), s"cookie.httpOnly=${cookie.httpOnly}")
        assert(cookie.secure == Present(false), s"cookie.secure=${cookie.secure}")
        assert(
            cookie.sameSite == Present(Browser.Cookie.SameSite.Lax),
            s"cookie.sameSite=${cookie.sameSite}"
        )
        succeed
    }

    // ---- size ----

    "size present decodes as Present(Int)" in {
        val json = """{"name":"n","value":"v","size":42}"""
        val wire = decode[CookieWire](json)
        assert(wire.size == Present(42))
        succeed
    }

    "size absent decodes as Absent" in {
        val wire = decode[CookieWire](base)
        assert(wire.size == Absent)
        succeed
    }

    // ---- httpOnly ----

    "httpOnly true decodes as Present(true)" in {
        val json = """{"name":"n","value":"v","httpOnly":true}"""
        val wire = decode[CookieWire](json)
        assert(wire.httpOnly == Present(true))
        succeed
    }

    "httpOnly false decodes as Present(false)" in {
        val json = """{"name":"n","value":"v","httpOnly":false}"""
        val wire = decode[CookieWire](json)
        assert(wire.httpOnly == Present(false))
        succeed
    }

    "httpOnly absent decodes as Absent" in {
        val wire = decode[CookieWire](base)
        assert(wire.httpOnly == Absent)
        succeed
    }

    // ---- secure ----

    "secure true decodes as Present(true)" in {
        val json = """{"name":"n","value":"v","secure":true}"""
        val wire = decode[CookieWire](json)
        assert(wire.secure == Present(true))
        succeed
    }

    "secure false decodes as Present(false)" in {
        val json = """{"name":"n","value":"v","secure":false}"""
        val wire = decode[CookieWire](json)
        assert(wire.secure == Present(false))
        succeed
    }

    "secure absent decodes as Absent" in {
        val wire = decode[CookieWire](base)
        assert(wire.secure == Absent)
        succeed
    }

    // ---- sameSite ----

    "sameSite Strict decodes as Present(\"Strict\")" in {
        val json = """{"name":"n","value":"v","sameSite":"Strict"}"""
        val wire = decode[CookieWire](json)
        assert(wire.sameSite == Present("Strict"))
        succeed
    }

    "sameSite Lax decodes as Present(\"Lax\")" in {
        val json = """{"name":"n","value":"v","sameSite":"Lax"}"""
        val wire = decode[CookieWire](json)
        assert(wire.sameSite == Present("Lax"))
        succeed
    }

    "sameSite None decodes as Present(\"None\")" in {
        val json = """{"name":"n","value":"v","sameSite":"None"}"""
        val wire = decode[CookieWire](json)
        assert(wire.sameSite == Present("None"))
        succeed
    }

    "sameSite absent decodes as Absent" in {
        val wire = decode[CookieWire](base)
        assert(wire.sameSite == Absent)
        succeed
    }

    // ---- Cookie.SameSite enum ----

    "SameSite.parse(\"Strict\") returns Present(Strict)" in {
        assert(Browser.Cookie.SameSite.parse("Strict") == Present(Browser.Cookie.SameSite.Strict))
        succeed
    }

    "SameSite.parse(\"Lax\") returns Present(Lax)" in {
        assert(Browser.Cookie.SameSite.parse("Lax") == Present(Browser.Cookie.SameSite.Lax))
        succeed
    }

    "SameSite.parse(\"None\") returns Present(None)" in {
        assert(Browser.Cookie.SameSite.parse("None") == Present(Browser.Cookie.SameSite.None))
        succeed
    }

    "SameSite.parse of unknown string returns Absent" in {
        assert(Browser.Cookie.SameSite.parse("strict") == Absent)
        assert(Browser.Cookie.SameSite.parse("") == Absent)
        succeed
    }

    "SameSite.wire round-trips through parse for all values" in {
        import Browser.Cookie.SameSite
        Seq(SameSite.Strict, SameSite.Lax, SameSite.None).foreach { s =>
            assert(SameSite.parse(s.wire) == Present(s), s"wire round-trip failed for $s")
        }
        succeed
    }

    "CookieWire.toCookie maps wire sameSite string to typed SameSite" in {
        val wire   = decode[CookieWire]("""{"name":"n","value":"v","sameSite":"Lax"}""")
        val cookie = CookieWire.toCookie(wire)
        assert(cookie.sameSite == Present(Browser.Cookie.SameSite.Lax))
        succeed
    }

    "CookieWire.fromCookie maps typed SameSite to wire string" in {
        val cookie = Browser.Cookie("n", "v", sameSite = Present(Browser.Cookie.SameSite.Strict))
        val wire   = CookieWire.fromCookie(cookie)
        assert(wire.sameSite == Present("Strict"))
        succeed
    }

    // ---- Cookie type-level invariants ----

    "Cookie.derives CanEqual matches by structural equality" in {
        val c1 = Browser.Cookie(name = "k", value = "v", domain = Present("localhost"), path = Present("/"))
        val c2 = Browser.Cookie(name = "k", value = "v", domain = Present("localhost"), path = Present("/"))
        // Compile-time check: derived CanEqual is in scope, so == is allowed under NonImplicitAssertions.
        discard(summon[CanEqual[Browser.Cookie, Browser.Cookie]])
        assert(c1 == c2, s"expected structural equality but got c1=$c1 c2=$c2")
        succeed
    }

    // ---- Live-browser round-trips (Maybe / Instant) ----

    "cookies returns Maybe.Absent for session cookies" in run {
        // Session cookies: set without an `expires` field. Chrome reports them with `expires` absent (or `-1` in the wire shape;
        // CDP normalises absent / negative-expiry cookies to "session"; our Maybe-Instant translation surfaces that as Absent).
        withBrowserOnLocalhost {
            cleanupCookieJar.andThen(assertEmptyCookieJar).andThen {
                Browser.setCookie("session-flag", "v", "localhost").andThen {
                    Browser.cookies.map { cs =>
                        cs.find(_.name == "session-flag") match
                            case Some(c) =>
                                assert(c.expires == Absent, s"expected session cookie expires == Absent but got ${c.expires}")
                            case None =>
                                fail(s"expected 'session-flag' in cookies but got: ${cs.map(_.name)}")
                    }
                }
            }
        }
    }

    "cookies round-trips an explicit expires through Instant" in run {
        withBrowserOnLocalhost {
            cleanupCookieJar.andThen(assertEmptyCookieJar).andThen {
                Clock.now.map { now =>
                    val expected = now + 1.day
                    val cookie = Browser.Cookie(
                        name = "with-expiry",
                        value = "v",
                        domain = Present("localhost"),
                        path = Present("/"),
                        expires = Present(expected)
                    )
                    Browser.setCookie(cookie).andThen {
                        Browser.cookies.map { cs =>
                            cs.find(_.name == "with-expiry") match
                                case Some(c) =>
                                    c.expires match
                                        case Present(actual) =>
                                            val deltaMillis = math.abs(((actual - expected): Duration).toMillis)
                                            assert(
                                                deltaMillis < 1000,
                                                s"expected expires within 1s of $expected but got $actual (delta=${deltaMillis}ms)"
                                            )
                                        case Absent =>
                                            fail(s"expected expires to be Present but got Absent for cookie $c")
                                case None =>
                                    fail(s"expected 'with-expiry' in cookies but got: ${cs.map(_.name)}")
                        }
                    }
                }
            }
        }
    }

    // ---- cookies ----

    "cookies returns cookies from a real page" in run {
        // Cookies require a real HTTP URL; use Chrome's own DevTools endpoint
        withBrowserOnLocalhost {
            cleanupCookieJar.andThen(assertEmptyCookieJar).andThen {
                Browser.setCookie("testcookie", "testvalue", "localhost").andThen {
                    Browser.cookies.map { cs =>
                        val found = cs.exists(c => c.name == "testcookie" && c.value == "testvalue")
                        assert(found, s"Expected to find 'testcookie' in cookies but got: ${cs.map(_.name)}")
                    }
                }
            }
        }
    }

    // ---- setCookie / deleteCookie ----

    "setCookie and deleteCookie round-trip" in run {
        withBrowserOnLocalhost {
            cleanupCookieJar.andThen(assertEmptyCookieJar).andThen {
                Browser.setCookie("mycookie", "myvalue", "localhost").andThen {
                    Browser.cookies.map { cs =>
                        val found = cs.exists(c => c.name == "mycookie")
                        assert(found, "Expected 'mycookie' to exist after setCookie")
                    }.andThen {
                        Browser.deleteCookie("mycookie").andThen {
                            Browser.cookies.map { cs =>
                                val found = cs.exists(c => c.name == "mycookie")
                                assert(!found, "Expected 'mycookie' to be deleted")
                            }
                        }
                    }
                }
            }
        }
    }

    "setCookie(Cookie) round-trips every populated field through cookies" in run {
        withBrowserOnLocalhost {
            cleanupCookieJar.andThen(assertEmptyCookieJar).andThen {
                val full = Browser.Cookie(
                    name = "sess",
                    value = "abc123",
                    domain = Present("localhost"),
                    path = Present("/"),
                    httpOnly = Present(true),
                    secure = Present(false),
                    sameSite = Present(Browser.Cookie.SameSite.Lax)
                )
                Browser.setCookie(full).andThen {
                    Browser.cookies.map { cs =>
                        cs.find(_.name == "sess") match
                            case Some(c) =>
                                assert(c.value == "abc123", s"Expected value 'abc123' but got '${c.value}'")
                                assert(c.domain == Present("localhost"), s"Expected domain 'localhost' but got '${c.domain}'")
                                assert(c.path == Present("/"), s"Expected path '/' but got '${c.path}'")
                                assert(c.httpOnly == Present(true), s"Expected httpOnly true but got '${c.httpOnly}'")
                                assert(c.secure == Present(false), s"Expected secure false but got '${c.secure}'")
                                assert(
                                    c.sameSite == Present(Browser.Cookie.SameSite.Lax),
                                    s"Expected sameSite Lax but got '${c.sameSite}'"
                                )
                            case None =>
                                fail(s"Expected cookie 'sess' in jar but got: ${cs.map(_.name)}")
                    }
                }
            }
        }
    }

    "setCookie shorthand and full-cookie overload land identical cookies" in run {
        withBrowserOnLocalhost {
            cleanupCookieJar.andThen(assertEmptyCookieJar).andThen {
                val viaCookie = Browser.Cookie(
                    name = "fullForm",
                    value = "v",
                    domain = Present("localhost"),
                    path = Present("/")
                )
                Browser.setCookie("shortForm", "v", "localhost", "/").andThen {
                    Browser.setCookie(viaCookie).andThen {
                        Browser.cookies.map { cs =>
                            (cs.find(_.name == "shortForm"), cs.find(_.name == "fullForm")) match
                                case (Some(s), Some(f)) =>
                                    assert(s.value == f.value, s"value mismatch: short=${s.value} full=${f.value}")
                                    assert(s.domain == f.domain, s"domain mismatch: short=${s.domain} full=${f.domain}")
                                    assert(s.path == f.path, s"path mismatch: short=${s.path} full=${f.path}")
                                    assert(s.httpOnly == f.httpOnly, s"httpOnly mismatch: short=${s.httpOnly} full=${f.httpOnly}")
                                    assert(s.secure == f.secure, s"secure mismatch: short=${s.secure} full=${f.secure}")
                                    assert(s.sameSite == f.sameSite, s"sameSite mismatch: short=${s.sameSite} full=${f.sameSite}")
                                case (short, full) =>
                                    fail(
                                        s"expected both 'shortForm' and 'fullForm' but got short=${short.map(_.name)} full=${full.map(_.name)} (all: ${cs.map(_.name)})"
                                    )
                        }
                    }
                }
            }
        }
    }

    "deleteCookie(name) removes the cookie from the current page's jar" in run {
        withBrowserOnLocalhost {
            cleanupCookieJar.andThen(assertEmptyCookieJar).andThen {
                Browser.setCookie("token", "tv", "localhost").andThen {
                    Browser.cookies.map { cs =>
                        assert(cs.exists(_.name == "token"), s"Expected 'token' to exist before delete but got: ${cs.map(_.name)}")
                    }.andThen {
                        Browser.deleteCookie("token").andThen {
                            Browser.cookies.map { cs =>
                                assert(!cs.exists(_.name == "token"), s"Expected 'token' to be deleted but got: ${cs.map(_.name)}")
                            }
                        }
                    }
                }
            }
        }
    }

    "deleteCookie(name, domain) removes the cookie from the explicit domain's jar" in run {
        withBrowserOnLocalhost {
            cleanupCookieJar.andThen(assertEmptyCookieJar).andThen {
                Browser.setCookie("session", "v", "localhost").andThen {
                    Browser.cookies.map { cs =>
                        assert(cs.exists(_.name == "session"), s"Expected 'session' to exist before delete but got: ${cs.map(_.name)}")
                    }.andThen {
                        Browser.deleteCookie("session", "localhost").andThen {
                            Browser.cookies.map { cs =>
                                assert(!cs.exists(_.name == "session"), s"Expected 'session' to be deleted but got: ${cs.map(_.name)}")
                            }
                        }
                    }
                }
            }
        }
    }

    // `restoreCookies` (called transitively via `withFork`) iterates with `Kyo.foreachDiscard`. Verify that the multi-input
    // path (every cookie applied) lands all cookies in the fork; uses Chrome's localhost DevTools page (cookies are forbidden on
    // `data:` URLs). Single- and multi-cookie cases exercise the per-iteration `Network.setCookie` send.
    "withFork preserves multiple cookies (multi-input restoreCookies)" in run {
        withBrowserOnLocalhost {
            cleanupCookieJar.andThen(assertEmptyCookieJar).andThen {
                Browser.setCookie("p1", "v1", "localhost").andThen {
                    Browser.setCookie("p2", "v2", "localhost").andThen {
                        Browser.setCookie("p3", "v3", "localhost").andThen {
                            Browser.withFork {
                                Browser.cookies.map { cs =>
                                    val names = cs.map(_.name).toSet
                                    assert(names.contains("p1"), s"Expected 'p1' in clone cookies but got: ${cs.map(_.name)}")
                                    assert(names.contains("p2"), s"Expected 'p2' in clone cookies but got: ${cs.map(_.name)}")
                                    assert(names.contains("p3"), s"Expected 'p3' in clone cookies but got: ${cs.map(_.name)}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- cookie-banner heuristic robustness across realistic banner shapes ----
    //
    // The heuristic's selector list (CookieBanner.tryAcceptCookiesWithSchedule) covers:
    //   1. [id*='accept'][id*='cookie' i]
    //   2. [class*='accept'][class*='cookie' i]
    //   3. button[id*='accept' i]
    //   4. button[class*='accept' i]
    //   5. [aria-label*='accept' i]
    //   6. [data-testid*='accept' i]
    //
    // Real-world banners deviate in shape; pin two realistic variants explicitly.
    // Shadow-DOM-encapsulated banners are not reachable via document.querySelector().
    // Banners injected inside a closed shadow root (as some consent-management platforms do) silently
    // fall through to Absent. Reaching them would require traversing all shadow roots via composedPath or
    // element.shadowRoot enumeration.

    // Variant A: text-only button. NO id, NO class hint, NO aria-label, NO data-testid. The visible label
    // is "Accept all cookies", but the heuristic does not have a text-content selector. Expected behaviour:
    // the heuristic returns Absent (it does not falsely fire on something it cannot detect deterministically).
    "tryAcceptCookies returns Absent when banner button has only text content (no id/class/aria-label/data-testid hint)" in run {
        withBrowser {
            onPage(
                """<div style="display:block">
                  |  <p>We use cookies</p>
                  |  <button>Accept all cookies</button>
                  |</div>""".stripMargin
            ) {
                Browser.tryAcceptCookies.map { result =>
                    assert(
                        result == Absent,
                        s"expected Absent for text-only button (heuristic must not falsely fire on text content); got $result"
                    )
                }
            }
        }
    }

    // Variant B: lowercase-only id "cookies-accept". Selector 1 requires id matching BOTH 'accept' (case-sensitive)
    // AND 'cookie' (case-insensitive); "cookies-accept" satisfies both, so the heuristic fires on selector 1.
    "tryAcceptCookies fires on lowercase-only id 'cookies-accept' via selector [id*='accept'][id*='cookie' i]" in run {
        withBrowser {
            onPage(
                """<div id="banner" style="display:block">
                  |  <p>We use cookies</p>
                  |  <button id="cookies-accept">Accept</button>
                  |</div>
                  |<script>
                  |  document.getElementById('cookies-accept').addEventListener('click', function() {
                  |    document.getElementById('banner').style.display = 'none';
                  |  });
                  |</script>""".stripMargin
            ) {
                Browser.tryAcceptCookies.map { result =>
                    result match
                        case Present(selector) =>
                            val expected = "[id*='accept'][id*='cookie' i]"
                            assert(
                                kyo.internal.Selector.toNode(selector) == kyo.internal.SelectorNode.Css(expected),
                                s"expected matched Selector.css(\"$expected\") for lowercase 'cookies-accept' id but got ${kyo.internal.Selector.toNode(selector)}"
                            )
                        case Absent =>
                            fail("expected Present(selector) for lowercase-only id 'cookies-accept' but got Absent")
                    end match
                }
            }
        }
    }

    // ---- tryAcceptCookies ----

    "tryAcceptCookies waits for banner removal" in run {
        // button[id*='accept' i] matches "btn-accept": the 3rd selector in the heuristic list
        // The banner wrapper (#cookie-banner) is removed after 250ms; since the button is inside it, the selector
        // query returns null once the parent is gone, proving the poll loop waited.
        withBrowser {
            onPage(
                """<div id="cookie-banner" style="display:block">
              |  <p>We use cookies</p>
              |  <button id="btn-accept">Accept</button>
              |</div>
              |<script>
              |  document.getElementById('btn-accept').addEventListener('click', function() {
              |    setTimeout(function() {
              |      document.getElementById('cookie-banner').remove();
              |    }, 250);
              |  });
              |</script>""".stripMargin
            ) {
                for
                    pair <- timed(Browser.tryAcceptCookies)
                    (elapsedDur, result) = pair
                    elapsed              = elapsedDur.toMillis
                    _ = assert(result.isDefined, s"Expected tryAcceptCookies to return Present(selector) but got $result")
                    _ = assert(
                        elapsed >= cookieRemovalFloorMs,
                        s"Expected to wait at least ${cookieRemovalFloorMs}ms (banner removal at ${cookieRemovalDelayMs}ms) but elapsed was ${elapsed}ms"
                    )
                    gone <- Browser.eval("String(document.querySelector('#cookie-banner') === null)")
                yield assert(gone == "true", s"Expected banner to be removed from DOM but got: $gone")
            }
        }
    }

    "tryAcceptCookies times out when banner persists" in run {
        // Same button selector matches; click handler does NOT remove the banner, so the poll loop times out.
        withBrowser {
            onPage(
                """<div id="cookie-banner" style="display:block">
              |  <p>We use cookies</p>
              |  <button id="btn-accept">Accept</button>
              |</div>
              |<script>
              |  document.getElementById('btn-accept').addEventListener('click', function() {
              |    // intentionally does NOT remove the banner
              |  });
              |</script>""".stripMargin
            ) {
                Abort.run[BrowserAssertionException] {
                    Browser.withConfig(_.loadSchedule(Schedule.fixed(50.millis).maxDuration(500.millis))) {
                        Browser.tryAcceptCookies
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) =>
                        assert(ex.check == "tryAcceptCookies", s"Expected check == 'tryAcceptCookies' but got: ${ex.check}")
                    case other => fail(s"Expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    "tryAcceptCookies no-ops without a banner" in run {
        withBrowser {
            onPage("<div><p>No cookie banner here</p></div>") {
                for
                    pair <- timed(Browser.tryAcceptCookies)
                    (elapsedDur, result) = pair
                    elapsed              = elapsedDur.toMillis
                    // Behavior: when no banner is present, the no-op path must (a) return Absent and (b) leave the DOM untouched.
                    // No spurious banner element should be created or remain. Together these are the deterministic contract;
                    // the timing bound below is a soft envelope.
                    bannerAbsent <- Browser.eval("String(document.querySelector('#cookie-banner') === null)")
                yield
                    assert(result.isEmpty, s"Expected tryAcceptCookies to return Absent but got $result")
                    assert(
                        bannerAbsent == "true",
                        s"Expected no banner DOM node after no-op tryAcceptCookies but got bannerAbsent='$bannerAbsent'"
                    )
                    assert(elapsed < 2000, s"Expected fast path (< 2000ms soft envelope) but elapsed was ${elapsed}ms")
            }
        }
    }

    "tryAcceptCookies returns Absent when no banner is present" in run {
        withBrowser {
            onPage("<html><body><p>No cookie banner here.</p></body></html>") {
                Browser.tryAcceptCookies.map { result =>
                    assert(result == Absent, s"Expected Absent when no banner present but got $result")
                }
            }
        }
    }

    "tryAcceptCookies returns Present(selector) when a banner is matched and dismissed" in run {
        // The button id "accept-cookies" contains both "accept" and "cookie" → matches the FIRST heuristic
        // selector "[id*='accept'][id*='cookie' i]". The test asserts the matched selector identity, proving
        // the Present payload carries the actual selector that fired (not just any non-empty string).
        withBrowser {
            onPage(
                """<div id="cookie-banner" style="display:block">
                  |  <button id="accept-cookies">Accept</button>
                  |</div>
                  |<script>
                  |  document.getElementById('accept-cookies').addEventListener('click', function() {
                  |    document.getElementById('cookie-banner').style.display = 'none';
                  |  });
                  |</script>""".stripMargin
            ) {
                Browser.tryAcceptCookies.map { result =>
                    result match
                        case Present(selector) =>
                            // Selector is opaque; project to its internal SelectorNode for verification (RHS-on-internals).
                            val expected = "[id*='accept'][id*='cookie' i]"
                            assert(
                                kyo.internal.Selector.toNode(selector) == kyo.internal.SelectorNode.Css(expected),
                                s"Expected matched Selector.css(\"$expected\") but got node ${kyo.internal.Selector.toNode(selector)}"
                            )
                        case Absent => fail(s"Expected Present(selector) but got Absent")
                    end match
                }
            }
        }
    }

    "tryAcceptCookies aborts with BrowserAssertionException when a matched banner does not disappear" in run {
        // The click handler does NOT remove the banner, but a CSS rule (or simply the absence of any hide logic)
        // keeps it visible. Selector matches, click happens, banner persists → poll loop times out → typed Abort.
        withBrowser {
            onPage(
                """<div id="cookie-banner" style="display:block">
                  |  <button id="accept-cookies">Accept</button>
                  |</div>
                  |<script>
                  |  document.getElementById('accept-cookies').addEventListener('click', function() {
                  |    // intentionally does NOT remove or hide the banner
                  |  });
                  |</script>""".stripMargin
            ) {
                Abort.run[BrowserAssertionException] {
                    Browser.withConfig(_.loadSchedule(Schedule.fixed(50.millis).maxDuration(500.millis))) {
                        Browser.tryAcceptCookies
                    }
                }.map {
                    case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                    case other => fail(s"Expected Result.Failure(_: BrowserAssertionTimedOutException) but got $other")
                }
            }
        }
    }

    // ---- cookies(forUrl) ----

    "cookies(forUrl) filters by Domain attribute" in run {
        withBrowserOnLocalhost {
            cleanupCookieJar.andThen(assertEmptyCookieJar).andThen {
                Browser.setCookie("a", "1", "localhost", "/").andThen {
                    Browser.setCookie("b", "2", "127.0.0.1", "/").andThen {
                        Browser.cookies(forUrl = "http://localhost/").map { cs =>
                            assert(cs.exists(_.name == "a"), s"Expected cookie 'a' (localhost) in filtered jar but got: ${cs.map(_.name)}")
                            assert(
                                !cs.exists(_.name == "b"),
                                s"Expected cookie 'b' (127.0.0.1) NOT in filtered jar but got: ${cs.map(_.name)}"
                            )
                        }
                    }
                }
            }
        }
    }

    "cookies(forUrl) is a strict subset of cookies" in run {
        withBrowserOnLocalhost {
            cleanupCookieJar.andThen(assertEmptyCookieJar).andThen {
                Browser.setCookie("alpha", "1", "localhost", "/").andThen {
                    Browser.setCookie("beta", "2", "localhost", "/").andThen {
                        Browser.cookies.map { full =>
                            Browser.cookies(forUrl = "http://localhost/").map { filtered =>
                                assert(
                                    filtered.size <= full.size,
                                    s"Expected filtered size (${filtered.size}) <= full size (${full.size})"
                                )
                                val fullNames     = full.map(_.name).toSet
                                val filteredNames = filtered.map(_.name).toSet
                                assert(
                                    filteredNames.subsetOf(fullNames),
                                    s"Expected filtered names $filteredNames ⊆ full names $fullNames"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "cookies(forUrl) with unrelated origin returns empty" in run {
        withBrowserOnLocalhost {
            cleanupCookieJar.andThen(assertEmptyCookieJar).andThen {
                Browser.setCookie("only", "v", "localhost", "/").andThen {
                    Browser.cookies(forUrl = "http://other-host.example.org/").map { cs =>
                        assert(cs.isEmpty, s"Expected empty Chunk for unrelated origin but got: ${cs.map(_.name)}")
                    }
                }
            }
        }
    }

    "cookies(forUrl) filters by Path attribute" in run {
        withBrowserOnLocalhost {
            cleanupCookieJar.andThen(assertEmptyCookieJar).andThen {
                Browser.setCookie("apionly", "v", "localhost", "/api").andThen {
                    Browser.setCookie("adminonly", "v", "localhost", "/admin").andThen {
                        Browser.cookies(forUrl = "http://localhost/api/v1").map { cs =>
                            assert(cs.exists(_.name == "apionly"), s"Expected 'apionly' for /api/v1 but got: ${cs.map(_.name)}")
                            assert(!cs.exists(_.name == "adminonly"), s"Expected 'adminonly' NOT for /api/v1 but got: ${cs.map(_.name)}")
                        }
                    }
                }
            }
        }
    }

    "zero-arg cookies still returns the full jar after overload added" in run {
        withBrowserOnLocalhost {
            cleanupCookieJar.andThen(assertEmptyCookieJar).andThen {
                Browser.setCookie("a", "1", "localhost", "/").andThen {
                    Browser.setCookie("b", "2", "localhost", "/").andThen {
                        Browser.cookies.map { cs =>
                            assert(cs.exists(_.name == "a"), s"Expected 'a' in full jar but got: ${cs.map(_.name)}")
                            assert(cs.exists(_.name == "b"), s"Expected 'b' in full jar but got: ${cs.map(_.name)}")
                        }
                    }
                }
            }
        }
    }

    // ---- Cookie.SameSite Scala 3 enum ----

    "Cookie.SameSite is a Scala 3 enum: derives CanEqual and supports == across all three cases" in {
        import Browser.Cookie.SameSite
        assert(SameSite.Strict == SameSite.Strict)
        assert(SameSite.Lax == SameSite.Lax)
        assert(SameSite.None == SameSite.None)
        assert(SameSite.Strict != SameSite.Lax)
        assert(SameSite.Lax != SameSite.None)
        assert(SameSite.None != SameSite.Strict)
    }

    "Cookie.SameSite is exhaustively matched across all three enum cases" in {
        import Browser.Cookie.SameSite
        def label(s: SameSite): String = s match
            case SameSite.Strict => "strict"
            case SameSite.Lax    => "lax"
            case SameSite.None   => "none"
        assert(label(SameSite.Strict) == "strict")
        assert(label(SameSite.Lax) == "lax")
        assert(label(SameSite.None) == "none")
    }

end BrowserCookieTest
