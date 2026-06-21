package kyo

import kyo.internal.CdpBackend
import kyo.internal.SharedChrome

class BrowserSessionTest extends BrowserTest:

    override def timeout = 60.seconds

    "cookies set in one Browser.run do not leak into the next run on the same shared Chrome" in {
        withBrowserOnLocalhost {
            Browser.setCookie("session-cookie", "v1", "localhost")
        }.andThen {
            withBrowserOnLocalhost {
                Browser.cookies.map { cs =>
                    val leaked = cs.exists(_.name == "session-cookie")
                    assert(!leaked, s"cookie leaked across Browser.run calls on shared Chrome: ${cs.map(_.name)}")
                }
            }
        }
    }

    "localStorage set in one run does not leak across runs on the same shared Chrome" in {
        withBrowserOnLocalhost {
            Browser.eval("localStorage.setItem('session-key','session-val'); 'ok'").unit
        }.andThen {
            withBrowserOnLocalhost {
                Browser.eval("localStorage.getItem('session-key')").map { v =>
                    assert(v == "null", s"localStorage leaked across runs on shared Chrome: expected 'null' but got '$v'")
                }
            }
        }
    }

    "sessionStorage set in one run does not leak across runs on the same shared Chrome" in {
        withBrowserOnLocalhost {
            Browser.eval("sessionStorage.setItem('s-key','s-val'); 'ok'").unit
        }.andThen {
            withBrowserOnLocalhost {
                Browser.eval("sessionStorage.getItem('s-key')").map { v =>
                    assert(v == "null", s"sessionStorage leaked across runs on shared Chrome: expected 'null' but got '$v'")
                }
            }
        }
    }

    // HTTP cache isolation: writes a marker into a fetched response, then in a second Browser.run,
    // intercepts the same URL with a different mock body. If the HTTP cache leaked, the second fetch
    // could return the cached body from the first run. We verify the second run observes only the
    // second-run mock's body.
    "HTTP cache does not leak across Browser.run calls on the same shared Chrome" in {
        withBrowserOnLocalhost {
            // First run: mock /cache-leak-probe to return "first-run-body"; fetch it so any HTTP cache
            // entry that COULD leak is populated.
            Browser.mockFetchResponse("/cache-leak-probe", 200, "first-run-body").andThen {
                Browser.eval(
                    """fetch('/cache-leak-probe').then(r => r.text()).then(t => { window.__firstBody = t; }); 'queued'"""
                ).andThen {
                    Retry[BrowserScriptException](Schedule.fixed(50.millis).take(40)) {
                        Browser.eval("typeof window.__firstBody === 'string' ? window.__firstBody : '__PENDING__'").map {
                            case "__PENDING__" => Abort.fail[BrowserScriptException](BrowserScriptErrorException("first body pending"))
                            case v             => v
                        }
                    }.map { firstBody =>
                        assert(firstBody == "first-run-body", s"expected first-run-body but got '$firstBody'")
                    }
                }
            }
        }.andThen {
            withBrowserOnLocalhost {
                // Second run: register a different mock body. If the HTTP cache leaked the first body,
                // the fetch would resolve to the cached "first-run-body" instead of hitting the new
                // interceptor.
                Browser.mockFetchResponse("/cache-leak-probe", 200, "second-run-body").andThen {
                    Browser.eval(
                        """fetch('/cache-leak-probe').then(r => r.text()).then(t => { window.__secondBody = t; }); 'queued'"""
                    ).andThen {
                        Retry[BrowserScriptException](Schedule.fixed(50.millis).take(40)) {
                            Browser.eval("typeof window.__secondBody === 'string' ? window.__secondBody : '__PENDING__'").map {
                                case "__PENDING__" => Abort.fail[BrowserScriptException](BrowserScriptErrorException("second body pending"))
                                case v             => v
                            }
                        }.map { secondBody =>
                            assert(
                                secondBody == "second-run-body",
                                s"HTTP cache leaked across runs on shared Chrome: expected 'second-run-body' but got '$secondBody'"
                            )
                        }
                    }
                }
            }
        }
    }

    "per-run browser context is disposed when the Browser.run scope exits" in {
        // Capture the shared Chrome's wsUrl once so both the outer scope (used to observe targets) and the
        // inner Browser.run (whose Scope.run absorbs the per-run context) attach to the SAME Chrome process.
        SharedChrome.init.map { wsUrl =>
            Browser.run(wsUrl) {
                Browser.use { parent =>
                    val client = parent.backend
                    CdpBackend.getTargets(client).map { before =>
                        val beforeIds = before.targetInfos.map(_.targetId).toSet
                        AtomicRef.init(Set.empty[String]).map { innerIdsRef =>
                            // Inner Browser.run on the same shared Chrome: its Scope.run absorbs the per-run
                            // browser context and tears it down when the body completes.
                            Browser.run(wsUrl) {
                                Browser.use { _ =>
                                    CdpBackend.getTargets(client).map { during =>
                                        val duringIds = during.targetInfos.map(_.targetId).toSet -- beforeIds
                                        innerIdsRef.set(duringIds)
                                    }
                                }
                            }.andThen {
                                innerIdsRef.get.map { innerIds =>
                                    assert(
                                        innerIds.nonEmpty,
                                        "Expected the inner Browser.run to have created at least one new target"
                                    )
                                    Retry[BrowserConnectionException](Schedule.fixed(50.millis).take(40)) {
                                        CdpBackend.getTargets(client).map { after =>
                                            val afterIds   = after.targetInfos.map(_.targetId).toSet
                                            val stillThere = innerIds.intersect(afterIds)
                                            if stillThere.nonEmpty then
                                                Abort.fail[BrowserConnectionException](
                                                    BrowserProtocolErrorException(
                                                        "context-disposal-fixture",
                                                        s"context not yet disposed; targets still present: $stillThere"
                                                    )
                                                )
                                            else ()
                                            end if
                                        }
                                    }.andThen {
                                        CdpBackend.getTargets(client).map { after =>
                                            val afterIds = after.targetInfos.map(_.targetId).toSet
                                            assert(
                                                innerIds.intersect(afterIds).isEmpty,
                                                s"per-run context not disposed on scope exit; surviving targets: ${innerIds.intersect(afterIds)}"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end BrowserSessionTest
