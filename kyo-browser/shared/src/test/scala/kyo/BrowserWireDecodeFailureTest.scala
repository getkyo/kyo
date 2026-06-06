package kyo

import kyo.internal.BrowserEval
import kyo.internal.CdpEvalDecoder

/** Pins that wire-decode failures in `textAll`, `attributeAll`, and `consoleLogs` surface as `BrowserProtocolErrorException` rather than
  * degrading silently to `Chunk.empty`.
  *
  * Pure unit tests exercise `CdpEvalDecoder.decodeStringListReply` directly with malformed JSON and assert that a
  * `BrowserProtocolErrorException` is raised (no Chrome needed). The `consoleLogs` integration test drives a real browser to verify that
  * malformed wire surfaces as a typed Abort. Genuine-empty-case tests pin the invariant that "no match" and "wire-decode failed" are not
  * conflated.
  */
class BrowserWireDecodeFailureTest extends BrowserTest:

    override def timeout = 90.seconds

    // ─── textAll / attributeAll malformed wire (unit tests) ───────
    //
    // These are pure unit tests that exercise `CdpEvalDecoder.decodeStringListReply` directly
    // with malformed JSON input. No Chrome / `withBrowser` / `onPage` required.

    "textAll wire-decode raises BrowserProtocolErrorException on malformed JSON" in {
        val malformed = """{"not":"an_array"}"""
        Abort.run[BrowserReadException] {
            CdpEvalDecoder.decodeStringListReply("textAll", malformed)
        }.map {
            case Result.Failure(ex: BrowserProtocolErrorException) => assert(ex.method == "textAll")
            case other =>
                fail(
                    s"Expected Abort.fail(BrowserProtocolErrorException) for malformed " +
                        s"textAll wire, but got: $other"
                )
        }
    }

    "attributeAll wire-decode raises BrowserProtocolErrorException on malformed JSON" in {
        val malformed = """"not_an_array""""
        Abort.run[BrowserReadException] {
            CdpEvalDecoder.decodeStringListReply("attributeAll", malformed)
        }.map {
            case Result.Failure(ex: BrowserProtocolErrorException) => assert(ex.method == "attributeAll")
            case other =>
                fail(
                    s"Expected Abort.fail(BrowserProtocolErrorException) for malformed " +
                        s"attributeAll wire, but got: $other"
                )
        }
    }

    // ─── consoleLogs malformed wire ──────────────────────────────────
    //
    // `consoleLogs` reads `window.__kyoConsoleLogs` directly from the page global. Injecting an
    // object (not an array) before the call makes the internal `JSON.stringify(logs)` produce a
    // JSON object string, which `Json.decode[Seq[String]](json)` rejects; the contract says the
    // failure must surface as a typed Abort rather than collapse to `Chunk.empty`.

    "consoleLogs Aborts with BrowserProtocolErrorException on malformed wire" in {
        withBrowser {
            onPage("<html><body></body></html>") {
                // Inject a non-array object into the console-log global that consoleLogs reads.
                // The JS inside consoleLogs does: const logs = window.__kyoConsoleLogs || [];
                // An object is truthy, so || [] is NOT taken; JSON.stringify({not:"array"})
                // yields '{"not":"array"}' which is not decodable as Seq[String].
                BrowserEval.evalJs("""window.__kyoConsoleLogs = {not: "array"}; "ok"""").andThen {
                    Abort.run[BrowserReadException] {
                        Browser.consoleLogs
                    }.map {
                        case Result.Failure(ex: BrowserProtocolErrorException) => assert(ex.method == "consoleLogs")
                        case other =>
                            fail(
                                s"Expected Abort.fail(BrowserProtocolErrorException) for malformed " +
                                    s"consoleLogs wire, but got: $other"
                            )
                    }
                }
            }
        }
    }

    // ─── genuine-empty case ───────────────────────────────────────
    //
    // These pin the "no elements matched" / "no console output" fast-path so that the
    // failure branch does not accidentally break the success-empty path.

    "textAll returns empty Chunk on truly-empty selector match" in {
        withBrowser {
            onPage("<div>No list items here</div>") {
                Browser.textAll(Browser.Selector.css("li.nonexistent")).map { texts =>
                    assert(texts.isEmpty, s"Expected Chunk.empty for no-match selector but got: $texts")
                }
            }
        }
    }

    "attributeAll returns empty Chunk on truly-empty selector match" in {
        withBrowser {
            onPage("<div>No anchors here</div>") {
                Browser.attributeAll(Browser.Selector.css("a.nonexistent"), "href").map { hrefs =>
                    assert(hrefs.isEmpty, s"Expected Chunk.empty for no-match selector but got: $hrefs")
                }
            }
        }
    }

    "consoleLogs returns empty Chunk on no console output" in {
        withBrowser {
            onPage("<html><body><p>Silent page</p></body></html>") {
                // No console.log calls were made; the buffer is empty.
                Browser.consoleLogs.map { logs =>
                    assert(logs.isEmpty, s"Expected Chunk.empty for silent page but got: $logs")
                }
            }
        }
    }

end BrowserWireDecodeFailureTest
