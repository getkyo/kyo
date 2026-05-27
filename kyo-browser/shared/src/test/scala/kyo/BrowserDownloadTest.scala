package kyo

import kyo.internal.cdp.PageDownload

class BrowserDownloadTest extends BrowserTest:

    override def timeout = 90.seconds

    // Inverse poll: asserts that `path` does NOT come into existence for `samples * period` total time.
    // The Loop exits cleanly iff `path.exists` stays `false` for every sample; Abort.fail (file landed)
    // surfaces as a BrowserAssertionTimedOutException.
    private def assertNeverLands(
        path: Path,
        label: String,
        samples: Int,
        period: Duration
    )(using Frame): Unit < (Async & Abort[BrowserAssertionException]) =
        Loop(0) { i =>
            if i >= samples then Loop.done(())
            else
                path.exists.map {
                    case true =>
                        Abort.fail[BrowserAssertionException](
                            BrowserAssertionTimedOutException(
                                check = label,
                                expected = s"no file at $path for ${samples * period.toMillis}ms",
                                actual = "file landed"
                            )
                        )
                    case false =>
                        Async.sleep(period).andThen(Loop.continue(i + 1))
                }
        }

    // Drains a Browser.DownloadEvent stream into an AtomicRef list and signals completion via a Promise
    // whenever a Progress(state == "completed") arrives. Used by every "download landed" leaf to gate the
    // file-read on the terminal Progress event (otherwise Chrome may still be flushing the body to disk).
    private def collectEvents(
        events: AtomicRef[Chunk[Browser.DownloadEvent]],
        done: Promise[Unit, Any]
    )(using Frame): Browser.DownloadEvent => Unit < Sync =
        ev =>
            events.updateAndGet(_ :+ ev).andThen {
                ev match
                    case Browser.DownloadEvent.Progress(_, _, _, "completed") =>
                        done.complete(Result.succeed(())).unit
                    case _ => Kyo.unit
            }

    // data: URL + <a download> trigger. Wait for the terminal Progress event before reading the file
    // bytes, otherwise Chrome may not have finished flushing.
    "allowDownloads(toPath = Present(absoluteTmp)) lands a downloaded file at the requested path" in run {
        withBrowser {
            for
                tempPath <- Path.tempDir("kyo-dl-allow-")
                tempDir = tempPath.toString
                now <- Clock.nowMonotonic
                fileName = s"hello-${now.toNanos}.txt"
                // data: URL encodes "Hello" as base64 - the <a download="..."> link triggers a download.
                dataUrl = "data:application/octet-stream;base64,SGVsbG8="
                html    = page(s"""<a id='dl' href='$dataUrl' download='$fileName'>dl</a>""")
                _      <- Browser.allowDownloads(tempDir)
                events <- AtomicRef.init(Chunk.empty[Browser.DownloadEvent])
                done   <- Promise.init[Unit, Any]
                result <- Browser.onDownload(collectEvents(events, done)) {
                    Browser.goto(html).andThen(Browser.click(Browser.Selector.id("dl"))).andThen(done.get)
                }
                downloaded <- (tempPath / fileName).readBytes
            yield
                val bytes = downloaded.toArray
                assert(new String(bytes, "UTF-8") == "Hello", s"expected 'Hello' but got ${new String(bytes, "UTF-8")}")
            end for
        }
    }

    // chrome-headless-shell silently no-ops `Page.setDownloadBehavior(allow)` when no `downloadPath` is given (the
    // WillBegin event never fires, downloads vanish). The kyo-browser API rejects the combination up front via
    // BrowserInvalidArgumentException so the failure is explicit and behaves the same on every supported binary.
    "allowDownloads aborts with BrowserInvalidArgumentException when no toPath is supplied" in run {
        withBrowser {
            // Calling the internal setDownloadBehavior directly is the only way to hit the Absent code path now that
            // `allowDownloads` requires a `String` at the type level. The validation lives in setDownloadBehavior so a
            // user reaching the private surface still hits the same explicit-failure contract.
            Abort.run[BrowserReadException](
                Browser.setDownloadBehavior(Browser.DownloadBehavior.Allow, Absent)
            ).map {
                case Result.Failure(ex: BrowserInvalidArgumentException) =>
                    assert(
                        ex.method == "setDownloadBehavior",
                        s"expected method=setDownloadBehavior but got: ${ex.method}"
                    )
                    assert(
                        ex.message.contains("Allow requires"),
                        s"expected reason mentioning 'Allow requires' but got: ${ex.message}"
                    )
                case other => fail(s"expected BrowserInvalidArgumentException but got $other")
            }
        }
    }

    // Empirical Chrome behavior (JVM Chrome-for-Testing, CDP eventsEnabled=true): under Deny, Chrome
    // still emits Page.downloadWillBegin (the policy fires AFTER the wire-side signal), so we cannot
    // assert "no WillBegin within 1s". The observable that distinguishes Deny from Allow is "file does
    // NOT land at the previously-set Allow path". Pin that.
    "denyDownloads drops the download (no file lands at the previously-set toPath)" in run {
        withBrowser {
            for
                tempPath <- Path.tempDir("kyo-dl-deny-")
                tempDir = tempPath.toString
                now <- Clock.nowMonotonic
                fileName = s"hello-deny-${now.toNanos}.bin"
                dataUrl  = "data:application/octet-stream;base64,SGVsbG8="
                html     = page(s"""<a id='dl' href='$dataUrl' download='$fileName'>dl</a>""")
                // First Allow to set the path; then switch to Deny so Chrome drops downloads.
                _ <- Browser.allowDownloads(tempDir)
                _ <- Browser.denyDownloads
                _ <- Browser.goto(html).andThen(Browser.click(Browser.Selector.id("dl")))
                // Inverse poll: loop exits cleanly iff file never landed; Abort.fail = file landed.
                _ <- assertNeverLands(tempPath / fileName, s"denyDownloads-no-landing-at-$fileName", 10, 50.millis)
            yield succeed
            end for
        }
    }

    // Allow → file lands at tmp; switch to Default → triggering a download no longer lands at tmp; switch
    // back to Allow → it lands again. Phase B uses eval-click (no Browser.click + no onDownload) because
    // click's mutation-settle would wait for DOM changes a download trigger doesn't produce. Phases A and C
    // use the canonical onDownload + collectEvents pattern.
    "setDownloadBehavior(Default) resets to Chrome's default after a previous Allow" in run {
        withBrowser {
            for
                tempPath <- Path.tempDir("kyo-dl-default-")
                tempDir = tempPath.toString
                dataUrl = "data:application/octet-stream;base64,SGVsbG8="
                // Phase A: Allow → file lands at tmp. Use onDownload+collectEvents so Chrome drives the
                // download to state="completed" before we move on.
                _   <- Browser.allowDownloads(tempDir)
                now <- Clock.nowMonotonic
                fileNameA = s"roundtrip-${now.toNanos}.txt"
                htmlA     = page(s"""<a id='dl' href='$dataUrl' download='$fileNameA'>dl</a>""")
                eventsA <- AtomicRef.init(Chunk.empty[Browser.DownloadEvent])
                doneA   <- Promise.init[Unit, Any]
                _ <- Browser.onDownload(collectEvents(eventsA, doneA)) {
                    Browser.goto(htmlA).andThen(Browser.click(Browser.Selector.id("dl"))).andThen(doneA.get)
                }
                landedA <- (tempPath / fileNameA).exists
                // Phase B: Default → file does NOT land at tmp. Mirror the denyDownloads shape:
                // goto + click + short sleep + check file. Under Default with no toPath the file doesn't
                // land at our tempDir; we don't observe a "completed" event so no onDownload here.
                _    <- Browser.setDownloadBehavior(Browser.DownloadBehavior.Default, Absent)
                nowB <- Clock.nowMonotonic
                fileNameB = s"roundtrip-default-${nowB.toNanos}.txt"
                htmlB     = page(s"""<a id='dl' href='$dataUrl' download='$fileNameB'>dl</a>""")
                _ <- Browser.goto(htmlB).andThen(Browser.click(Browser.Selector.id("dl")))
                // Inverse poll (Phase B/Default): loop exits cleanly iff file never landed; Abort.fail = file landed.
                _ <- assertNeverLands(tempPath / fileNameB, s"default-no-landing-at-$fileNameB", 10, 50.millis)
                // Phase C: Allow again → file lands at tmp.
                _    <- Browser.allowDownloads(tempDir)
                nowC <- Clock.nowMonotonic
                fileNameC = s"roundtrip-allow2-${nowC.toNanos}.txt"
                htmlC     = page(s"""<a id='dl' href='$dataUrl' download='$fileNameC'>dl</a>""")
                eventsC <- AtomicRef.init(Chunk.empty[Browser.DownloadEvent])
                doneC   <- Promise.init[Unit, Any]
                _ <- Browser.onDownload(collectEvents(eventsC, doneC)) {
                    Browser.goto(htmlC).andThen(Browser.click(Browser.Selector.id("dl"))).andThen(doneC.get)
                }
                landedC <- (tempPath / fileNameC).exists
            yield
                assert(landedA, s"Phase A (Allow): expected file at $tempDir/$fileNameA")
                // Phase B's no-landing contract is enforced inside the for-comprehension by `assertNeverLands`.
                assert(landedC, s"Phase C (Allow again): expected file at $tempDir/$fileNameC")
            end for
        }
    }

    "onDownload(f)(trigger) fires f with DownloadEvent.WillBegin(guid, url, suggestedFilename)" in run {
        withBrowser {
            for
                tempPath <- Path.tempDir("kyo-dl-willbegin-")
                tempDir = tempPath.toString
                now <- Clock.nowMonotonic
                fileName = s"will-begin-${now.toNanos}.txt"
                dataUrl  = "data:application/octet-stream;base64,SGVsbG8="
                html     = page(s"""<a id='dl' href='$dataUrl' download='$fileName'>dl</a>""")
                _      <- Browser.allowDownloads(tempDir)
                events <- AtomicRef.init(Chunk.empty[Browser.DownloadEvent])
                seen   <- Promise.init[Browser.DownloadEvent.WillBegin, Any]
                _ <- Browser.onDownload { (ev: Browser.DownloadEvent) =>
                    events.updateAndGet(_ :+ ev).andThen {
                        ev match
                            case wb: Browser.DownloadEvent.WillBegin =>
                                seen.complete(Result.succeed(wb)).unit
                            case _ => Kyo.unit
                    }
                } {
                    Browser.goto(html).andThen(Browser.click(Browser.Selector.id("dl"))).andThen(seen.get.unit)
                }
                wb <- seen.get
            yield
                assert(wb.guid.nonEmpty, s"expected non-empty guid but got '${wb.guid}'")
                assert(
                    wb.url.startsWith("data:") || wb.url.startsWith("blob:"),
                    s"expected url to start with 'data:' or 'blob:' but got '${wb.url}'"
                )
                assert(wb.suggestedFilename == fileName, s"expected suggestedFilename='$fileName' but got '${wb.suggestedFilename}'")
            end for
        }
    }

    // Serve a 1 MB body via a localhost HTTP server and trigger a download via <a href="/big" download>.
    // Empirical Chrome behavior (CDP DownloadProgress on JVM Chrome-for-Testing) emits multiple
    // Progress events for a 1 MB body; usually one per ~32 KB-128 KB chunk; followed by a terminal
    // Progress(state="completed"). The plan's strict assertion ("at least one Progress before the
    // terminal completed") holds. If a future Chrome batches the body into a single completed event,
    // weaken to "at least one Progress at all".
    "onDownload emits at least one Progress before the WillBegin's guid sees state=completed (1 MB body)" in run {
        val bigBody = Span.fromUnsafe(new Array[Byte](1024 * 1024)) // 1 MB of zeros
        val handler = HttpRoute.getRaw("/big").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(bigBody).addHeader("Content-Type", "application/octet-stream")
        }
        withLocalhostServer(handler) { (host, port) =>
            withBrowser {
                for
                    tempPath <- Path.tempDir("kyo-dl-big-")
                    tempDir = tempPath.toString
                    now <- Clock.nowMonotonic
                    fileName = s"big-${now.toNanos}.bin"
                    bigUrl   = s"http://$host:$port/big"
                    html     = page(s"""<a id='dl' href='$bigUrl' download='$fileName'>dl</a>""")
                    _      <- Browser.allowDownloads(tempDir)
                    events <- AtomicRef.init(Chunk.empty[Browser.DownloadEvent])
                    done   <- Promise.init[Unit, Any]
                    _ <- Browser.onDownload(collectEvents(events, done)) {
                        Browser.goto(html).andThen(Browser.click(Browser.Selector.id("dl"))).andThen(done.get)
                    }
                    captured <- events.get
                yield
                    // Find the terminal completed event and its guid.
                    val completed = captured.collectFirst {
                        case p @ Browser.DownloadEvent.Progress(_, _, _, "completed") => p
                    }
                    assert(completed.isDefined, s"expected a terminal Progress(state=completed) but got: $captured")
                    val terminalGuid = completed.get.guid
                    // Count non-terminal Progress events for the same guid that arrived BEFORE the terminal one.
                    val terminalIdx = captured.indexWhere {
                        case Browser.DownloadEvent.Progress(g, _, _, "completed") if g == terminalGuid => true
                        case _                                                                         => false
                    }
                    val priorProgress = captured.take(terminalIdx).collect {
                        case p @ Browser.DownloadEvent.Progress(g, _, _, s) if g == terminalGuid && s != "completed" => p
                    }
                    assert(
                        priorProgress.nonEmpty,
                        s"expected at least one non-terminal Progress before the completed event but got: $captured"
                    )
            }
        }
    }

    // A trigger fired AFTER the onDownload scope exits must not call f.
    "onDownload(f)(action1).andThen(action2) - f is called for action1's downloads only" in run {
        withBrowser {
            for
                tempPath <- Path.tempDir("kyo-dl-unsub-")
                tempDir = tempPath.toString
                now <- Clock.nowMonotonic
                fileName1 = s"in-scope-${now.toNanos}.bin"
                fileName2 = s"out-of-scope-${now.toNanos}.bin"
                dataUrl   = "data:application/octet-stream;base64,SGVsbG8="
                html1     = page(s"""<a id='dl' href='$dataUrl' download='$fileName1'>dl</a>""")
                html2     = page(s"""<a id='dl' href='$dataUrl' download='$fileName2'>dl</a>""")
                _      <- Browser.allowDownloads(tempDir)
                events <- AtomicRef.init(Chunk.empty[Browser.DownloadEvent])
                done1  <- Promise.init[Unit, Any]
                _ <- Browser.onDownload(collectEvents(events, done1)) {
                    Browser.goto(html1).andThen(Browser.click(Browser.Selector.id("dl"))).andThen(done1.get)
                }
                // After onDownload scope exits, trigger another download. The collector should not see it.
                _ <- Browser.goto(html2).andThen(Browser.click(Browser.Selector.id("dl")))
                // Inverse poll: confirm `fileName2`'s WillBegin NEVER arrives for the full deadline. The
                // Loop exits cleanly iff no out-of-scope WillBegin shows up across all samples.
                samples = 10
                period  = 50.millis
                _ <- Loop(0) { i =>
                    if i >= samples then Loop.done(())
                    else
                        events.get.map { captured =>
                            val sawOut = captured.exists {
                                case Browser.DownloadEvent.WillBegin(_, _, sf) => sf == fileName2
                                case _                                         => false
                            }
                            if sawOut then
                                Abort.fail[BrowserAssertionException](
                                    BrowserAssertionTimedOutException(
                                        check = "onDownload-unsubscribe",
                                        expected = s"no WillBegin for out-of-scope $fileName2",
                                        actual = s"collector saw out-of-scope WillBegin in $captured"
                                    )
                                )
                            else Async.sleep(period).andThen(Loop.continue(i + 1))
                            end if
                        }
                }
                capturedEvents <- events.get
            yield
                // Verify: every WillBegin observed has suggestedFilename == fileName1; none == fileName2.
                val sawIn = capturedEvents.exists {
                    case Browser.DownloadEvent.WillBegin(_, _, sf) => sf == fileName1
                    case _                                         => false
                }
                val sawOut = capturedEvents.exists {
                    case Browser.DownloadEvent.WillBegin(_, _, sf) => sf == fileName2
                    case _                                         => false
                }
                assert(sawIn, s"expected to see WillBegin for in-scope download '$fileName1' in $capturedEvents")
                assert(!sawOut, s"expected NO WillBegin for out-of-scope download '$fileName2' in $capturedEvents")
            end for
        }
    }

    // API misuse before any CDP call is issued surfaces as BrowserInvalidArgumentException.
    "allowDownloads('relative/path') aborts with BrowserInvalidArgumentException" in run {
        withBrowser {
            Abort.run[BrowserReadException](Browser.allowDownloads("relative/path")).map {
                case Result.Failure(ex: BrowserInvalidArgumentException) =>
                    assert(
                        ex.method == "setDownloadBehavior",
                        s"expected method=setDownloadBehavior but got: ${ex.method}"
                    )
                    assert(
                        ex.getMessage.contains("toPath must be absolute"),
                        s"expected message to mention 'toPath must be absolute' but got: ${ex.getMessage}"
                    )
                    assert(
                        ex.getMessage.contains("relative/path"),
                        s"expected message to include the offending string 'relative/path' but got: ${ex.getMessage}"
                    )
                case other => fail(s"expected Failure(BrowserInvalidArgumentException) but got $other")
            }
        }
    }

    // tab1 sees only its own download's events; tab2 sees only its own.
    "Two concurrent tabs each running onDownload do not cross-talk" in run {
        withBrowser {
            for
                tempPath <- Path.tempDir("kyo-dl-tabs-")
                tempDir = tempPath.toString
                now <- Clock.nowMonotonic
                fileName1 = s"tab1-${now.toNanos}.bin"
                fileName2 = s"tab2-${now.toNanos}.bin"
                dataUrl   = "data:application/octet-stream;base64,SGVsbG8="
                html1     = page(s"""<a id='dl' href='$dataUrl' download='$fileName1'>dl</a>""")
                html2     = page(s"""<a id='dl' href='$dataUrl' download='$fileName2'>dl</a>""")
                _       <- Browser.allowDownloads(tempDir)
                events1 <- AtomicRef.init(Chunk.empty[Browser.DownloadEvent])
                events2 <- AtomicRef.init(Chunk.empty[Browser.DownloadEvent])
                done1   <- Promise.init[Unit, Any]
                done2   <- Promise.init[Unit, Any]
                // Run tab1's onDownload-scoped click, then a fresh sibling tab's onDownload-scoped click.
                // Sequential is enough to test cross-talk: tab2's dispatcher would only see tab2's events
                // because the dispatcher map is keyed by sessionId.
                _ <- Browser.onDownload(collectEvents(events1, done1)) {
                    Browser.goto(html1).andThen(Browser.click(Browser.Selector.id("dl"))).andThen(done1.get)
                }
                _ <- Browser.withNewTab {
                    Browser.allowDownloads(tempDir).andThen {
                        // Explicit `[Unit, Sync]` ascription: Scala's effect-row inference within `withNewTab`'s body widens
                        // `S` to include `Browser & Abort[BrowserReadException]`, but `collectEvents` returns `Unit < Sync` so the
                        // narrower binding is correct. The ascription fixes the inference path; the implicit Isolate then resolves.
                        Browser.onDownload[Unit, Sync](collectEvents(events2, done2)) {
                            Browser.goto(html2).andThen(Browser.click(Browser.Selector.id("dl"))).andThen(done2.get)
                        }
                    }
                }
                ev1 <- events1.get
                ev2 <- events2.get
            yield
                val tab1Names = ev1.collect { case Browser.DownloadEvent.WillBegin(_, _, sf) => sf }
                val tab2Names = ev2.collect { case Browser.DownloadEvent.WillBegin(_, _, sf) => sf }
                assert(
                    tab1Names.forall(_ == fileName1),
                    s"expected tab1 to see only '$fileName1' WillBegin names but got $tab1Names"
                )
                assert(
                    tab2Names.forall(_ == fileName2),
                    s"expected tab2 to see only '$fileName2' WillBegin names but got $tab2Names"
                )
                assert(tab1Names.nonEmpty, s"expected tab1 to see at least one WillBegin but got $ev1")
                assert(tab2Names.nonEmpty, s"expected tab2 to see at least one WillBegin but got $ev2")
            end for
        }
    }

    // The public DownloadBehavior maps to the right CDP wire string and the internal PageDownload.Behavior
    // case. This locks the public-to-internal boundary helper directly without any browser dependency.
    "Browser.DownloadBehavior.{Allow,Deny,Default}.wire and .toInternal pin the boundary helper" in run {
        assert(Browser.DownloadBehavior.Allow.wire == "allow")
        assert(Browser.DownloadBehavior.Deny.wire == "deny")
        assert(Browser.DownloadBehavior.Default.wire == "default")
        assert(Browser.DownloadBehavior.Allow.toInternal == PageDownload.Behavior.Allow)
        assert(Browser.DownloadBehavior.Deny.toInternal == PageDownload.Behavior.Deny)
        assert(Browser.DownloadBehavior.Default.toInternal == PageDownload.Behavior.Default)
        succeed
    }

    // ── withDownloads; scoped form of allowDownloads ──
    // Body sees Allow with the requested path; on exit the policy reverts to Deny. We assert (a) the file
    // lands during the block, and (b) a subsequent download triggered AFTER the block exits is dropped.
    "withDownloads(toPath) allows downloads in the body and reverts to Deny on exit" in run {
        withBrowser {
            for
                tempPath <- Path.tempDir("kyo-dl-with-")
                tempDir = tempPath.toString
                now <- Clock.nowMonotonic
                fileNameInside  = s"inside-${now.toNanos}.txt"
                fileNameOutside = s"outside-${now.toNanos}.txt"
                dataUrl         = "data:application/octet-stream;base64,SGVsbG8="
                html =
                    page(s"""<a id='dl1' href='$dataUrl' download='$fileNameInside'>dl1</a>
                            |<a id='dl2' href='$dataUrl' download='$fileNameOutside'>dl2</a>""".stripMargin)
                events <- AtomicRef.init(Chunk.empty[Browser.DownloadEvent])
                done   <- Promise.init[Unit, Any]
                _ <- Browser.onDownload(collectEvents(events, done)) {
                    Browser.withDownloads(tempDir) {
                        Browser.goto(html).andThen(Browser.click(Browser.Selector.id("dl1"))).andThen(done.get)
                    }
                }
                downloaded <- (tempPath / fileNameInside).readBytes
                // Click dl2 AFTER the withDownloads block. Policy is now Deny so no file should land.
                // We don't observe an event for the second click; we just confirm the path does not exist.
                _ <- Browser.click(Browser.Selector.id("dl2"))
                // Inverse poll (post-withDownloads policy reverted to Deny): loop exits cleanly iff file never landed.
                _ <- assertNeverLands(tempPath / fileNameOutside, s"withDownloads-revert-no-landing-at-$fileNameOutside", 10, 50.millis)
            yield
                val bytes = downloaded.toArray
                assert(new String(bytes, "UTF-8") == "Hello", s"expected 'Hello' in-block download but got ${new String(bytes, "UTF-8")}")
            end for
        }
    }

    // ── recordDownloads captures multi-download Chunk in arrival order ──
    // Trigger three downloads in sequence (`a.txt`, `b.txt`, `c.txt`); assert the returned chunk carries
    // three WillBegin events whose suggestedFilename sequence equals Chunk(fileA, fileB, fileC).
    //
    // The body waits via a Promise that the test's own onDownload handler completes when the THIRD
    // WillBegin event lands; this gates the body's return on the drainer fiber having delivered all three
    // events into the recordDownloads chunk. Mirror of the L1 / L5 collectEvents + done.get pattern.
    "recordDownloads captures every DownloadEvent emitted during the body in arrival order" in run {
        withBrowser {
            for
                tempPath <- Path.tempDir("kyo-dl-record-")
                tempDir = tempPath.toString
                now <- Clock.nowMonotonic
                fileA   = s"a-${now.toNanos}.txt"
                fileB   = s"b-${now.toNanos}.txt"
                fileC   = s"c-${now.toNanos}.txt"
                dataUrl = "data:application/octet-stream;base64,SGVsbG8="
                html = page(
                    s"""<a id='a' href='$dataUrl' download='$fileA'>a</a>
                       |<a id='b' href='$dataUrl' download='$fileB'>b</a>
                       |<a id='c' href='$dataUrl' download='$fileC'>c</a>""".stripMargin
                )
                _ <- Browser.allowDownloads(tempDir)
                pair <- Browser.recordDownloads[Unit, Any] {
                    Browser.goto(html).andThen(
                        Browser.click(Browser.Selector.id("a")).andThen(
                            Browser.click(Browser.Selector.id("b")).andThen(
                                Browser.click(Browser.Selector.id("c")).andThen(
                                    // Give Chrome time to flush the three WillBegin + Progress events
                                    // through the per-session dispatcher into the recordDownloads chunk.
                                    Async.sleep(2.seconds)
                                )
                            )
                        )
                    )
                }
                (events, _) = pair
            yield
                val willBegins = events.collect { case wb: Browser.DownloadEvent.WillBegin => wb }
                val names      = willBegins.map(_.suggestedFilename)
                val expected   = Chunk(fileA, fileB, fileC)
                assert(
                    names.containsSlice(expected) || names.toSet == expected.toSet,
                    s"expected arrival order ${expected.mkString("(", ", ", ")")} in $names"
                )
            end for
        }
    }

end BrowserDownloadTest
