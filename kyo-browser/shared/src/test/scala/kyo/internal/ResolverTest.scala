package kyo.internal

import kyo.*
import kyo.internal.CdpTypes.*

/** Stress, direct, and uniqueness tests for [[Resolver]].
  *
  *   - `resolveAll` many-matches stress (count > 3, here 10 + 50).
  *   - `Within(parent, child)` directly via `Resolver.resolveOne`.
  *   - decode failure path → `BrowserProtocolErrorException` (pure).
  *   - `freshAttr` uniqueness across parallel `resolveOne`.
  */
class ResolverTest extends kyo.BrowserTest:

    override def timeout = 60.seconds

    // ── resolveAll stress (10 matches, 50 matches) ────────────────────

    "resolveAll returns all matches when count == 10" in {
        val items = (0 until 10).map(i => s"<p class='m' id='p$i'>$i</p>").mkString
        val html  = page(s"<div>$items</div>")
        withBrowserOnLocalhost {
            Browser.goto(html).andThen {
                Resolver.resolveAll(Selector.css(".m")).map { all =>
                    assert(all.length == 10, s"expected 10 matches but got ${all.length}")
                }
            }
        }
    }

    "resolveAll returns all matches when count == 50 (stress)" in {
        val items = (0 until 50).map(i => s"<p class='m' id='p$i'>$i</p>").mkString
        val html  = page(s"<div>$items</div>")
        withBrowserOnLocalhost {
            Browser.goto(html).andThen {
                Resolver.resolveAll(Selector.css(".m")).map { all =>
                    assert(all.length == 50, s"expected 50 matches but got ${all.length}")
                }
            }
        }
    }

    // ── Within(parent, child) directly via resolveOne ─────────────────

    "Resolver.resolveOne(Within(parent, child)) directly resolves the child element" in {
        val html = page(
            "<div id='outer'>" +
                "<button id='inside-btn'>Inside</button>" +
                "</div>" +
                "<button id='outside-btn'>Outside</button>"
        )
        withBrowserOnLocalhost {
            Browser.goto(html).andThen {
                val parent = Selector.css("#outer")
                val child  = Selector.css("button")
                val within = parent.find(child)
                Resolver.resolveOne(within).map { found =>
                    Resolver.resolveOne(Selector.id("inside-btn")).map { inside =>
                        Resolver.resolveOne(Selector.id("outside-btn")).map { outside =>
                            (found, inside, outside) match
                                case (Present(f), Present(i), Present(o)) =>
                                    assert(f == i, s"Within should resolve to inside-btn ($i) but got $f")
                                    assert(f != o, s"Within should NOT resolve to outside-btn ($o) but got $f")
                                case other =>
                                    fail(s"expected three Present refs but got $other")
                        }
                    }
                }
            }
        }
    }

    // ── decode failure raises BrowserProtocolErrorException ──────────

    "decode failure path raises BrowserProtocolErrorException (pure)" in {
        val malformed = "{this is not valid JSON for GetFrameTreeResult}"
        Abort.run[BrowserConnectionException] {
            CdpBackend.decodeOrFail[GetFrameTreeResult](malformed, "DOM.getDocument")
        }.map {
            case Result.Failure(ex: BrowserProtocolErrorException) =>
                assert(ex.method == "DOM.getDocument", s"expected method='DOM.getDocument' but got '${ex.method}'")
                assert(
                    ex.error.startsWith("decode failed:"),
                    s"expected error startsWith 'decode failed:' but got '${ex.error}'"
                )
            case other =>
                fail(s"expected Result.Failure(BrowserProtocolErrorException) but got $other")
        }
    }

    // ── parallel resolveOne uniqueness (handle-stable pipeline) ──────

    "uniqueness across N=10 parallel resolveOne via Async.zip" in {
        // 10 distinct elements; each parallel resolveOne picks one. The Resolver pipeline
        // (Runtime.evaluate + objectId + DOM.describeNode) gives each invocation its own JS handle
        // that resolves to its own backendNodeId; concurrent CDP traffic on the same tab cannot
        // race because there is no shared rootNodeId between round-trips. The 10 NodeRefs must be
        // distinct (one per `#bN` button).
        val items = (0 until 10).map(i => s"<button id='b$i' class='m'>$i</button>").mkString
        val html  = page(s"<div>$items</div>")
        withBrowserOnLocalhost {
            Browser.goto(html).andThen {
                // Clone-isolated parallel resolveOne; confirms handle-stable resolution survives
                // concurrent CDP traffic.
                Browser.isolate.clone.use {
                    Async.zip(
                        Resolver.resolveOne(Selector.id("b0")),
                        Resolver.resolveOne(Selector.id("b1")),
                        Resolver.resolveOne(Selector.id("b2")),
                        Resolver.resolveOne(Selector.id("b3")),
                        Resolver.resolveOne(Selector.id("b4")),
                        Resolver.resolveOne(Selector.id("b5")),
                        Resolver.resolveOne(Selector.id("b6")),
                        Resolver.resolveOne(Selector.id("b7")),
                        Resolver.resolveOne(Selector.id("b8")),
                        Resolver.resolveOne(Selector.id("b9"))
                    ).map { (r0, r1, r2, r3, r4, r5, r6, r7, r8, r9) =>
                        val refs = Chunk(r0, r1, r2, r3, r4, r5, r6, r7, r8, r9).map {
                            case Present(r) => r
                            case Absent     => fail("expected Present resolveOne result for parallel id() but got Absent")
                        }
                        val refSet = refs.toSet
                        assert(
                            refSet.size == 10,
                            s"expected 10 unique NodeRefs from parallel resolveOne but got ${refSet.size}: $refSet"
                        )
                    }
                }
            }
        }
    }

    // ── isStaleNodeError classification (pure) ───────────────────────

    "Resolver.isStaleNodeError recognises the CDP stale-node sentinel substring" in {
        // Match the live wire shape: CDP returns the message verbatim inside the error payload, then
        // CdpBackend.decodeOrFail composes it into a `BrowserProtocolErrorException(error=...)` whose
        // `error` field carries the CDP message. The classifier substring-matches against that field.
        val stale = BrowserProtocolErrorException(
            "DOM.describeNode",
            s"CDP error: ${CdpErrorStrings.StaleNodeErrorMessage} with given id"
        )
        assert(Resolver.isStaleNodeError(stale), s"expected isStaleNodeError=true for stale-node error: ${stale.error}")

        val other = BrowserProtocolErrorException("Runtime.evaluate", "some unrelated protocol failure")
        assert(!Resolver.isStaleNodeError(other), s"expected isStaleNodeError=false for unrelated error: ${other.error}")

        val empty = BrowserProtocolErrorException("DOM.requestNode", "")
        assert(!Resolver.isStaleNodeError(empty), s"expected isStaleNodeError=false for empty error message")
    }

    // ── Lazy m_document bootstrap retry (live) ───────────────────────

    // After `Browser.goto` Chromium may briefly have a null `m_document`. The first `requestNode` after
    // navigation can return `nodeId: 0`; describeByObjectId then issues `DOM.getDocument` once and retries.
    // We exercise this by resolving immediately after a navigation: the bootstrap path either fires (slow
    // page) or the agent already had a document (fast page); either way the resolveOne must succeed.
    "Resolver.resolveOne immediately after navigation succeeds (covers lazy m_document bootstrap retry path)" in {
        val html = page("<button id='early'>Hello</button>")
        withBrowserOnLocalhost {
            Browser.goto(html).andThen {
                // No prior `Browser.eval`/`Browser.text` round-trip between goto and resolveOne; the
                // first CDP read MUST exercise the requestAndDescribe path with a potentially-null
                // m_document. The lazy-bootstrap retry in describeByObjectId / requestAndDescribe
                // handles the nodeId=0 case.
                Resolver.resolveOne(Selector.id("early")).map {
                    case Present(ref) =>
                        assert(ref.backendNodeId > 0, s"expected non-zero backendNodeId, got ${ref.backendNodeId}")
                    case Absent =>
                        fail("expected Present(NodeRef) after first-eval-after-navigate but got Absent")
                }
            }
        }
    }

    // ── Release-on-Abort coverage ─────────────────────────────────────

    // Direct fault-injection between `describeByObjectId` and `releaseObjectQuiet` would require seams
    // into `Resolver`'s private internals or a mock `CdpClient`. The implementation uses
    // `Scope.run + Scope.ensure(releaseObjectQuiet(...))(describeByObjectId(...))`, which fires the
    // release on success AND on Abort/Panic / interruption. We exercise the Abort-after-resolve path
    // end-to-end against a real Chromium: a successful `resolveOne` is followed by an injected
    // `Abort.fail` inside the same scope. The test asserts the Abort surfaces cleanly without hangs
    // or panics; the underlying `Scope.ensure` semantics guarantee the JS handle is released even
    // though the surrounding fiber aborts.
    "resolveOne release path survives Abort raised after resolution" in {
        val html  = page("<button id='b0'>0</button>")
        val cause = BrowserConnectionLostException("simulated post-resolve abort", Maybe.empty)
        withBrowserOnLocalhost {
            Browser.goto(html).andThen {
                Abort.run[BrowserConnectionException] {
                    Resolver.resolveOne(Selector.id("b0")).map {
                        case Present(_) => Abort.fail(cause): Unit < Abort[BrowserConnectionException]
                        case Absent     => fail("expected Present resolveOne result but got Absent")
                    }
                }.map {
                    case Result.Failure(c: BrowserConnectionException) =>
                        assert(c eq cause, s"Expected the original cause to surface, got $c")
                    case other => fail(s"Expected Result.Failure(BrowserConnectionException) but got $other")
                }
            }
        }
    }

end ResolverTest
