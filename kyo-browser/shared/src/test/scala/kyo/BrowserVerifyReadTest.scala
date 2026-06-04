package kyo

import kyo.internal.SettleRead

class BrowserVerifyReadTest extends BrowserTest:

    override def timeout = 90.seconds

    // Test 1: settle returns the stabilized value of a converging expression (pins INV-001).
    // The page-side JS counter starts at 0, increments by 1 every 20ms, and stops at 42 after ~840ms.
    // With assertionStabilityWindow = 100ms (default), settle must wait until the counter holds at 42
    // for 100ms before returning. The result must be 42, not an early intermediate value.
    "settle returns the stabilized value of a converging expression" in run {
        withBrowser {
            onPage("""<body>
                <script>
                    window.__kyoVal = 0;
                    setInterval(() => { if (window.__kyoVal < 42) window.__kyoVal++; }, 20);
                </script>
            </body>""") {
                SettleRead.settle("settle-stabilizes", "String(window.__kyoVal)") { raw =>
                    raw.toInt
                }.map { result =>
                    assert(result == 42, s"expected 42 but got $result")
                }
            }
        }
    }

    // Test 2: settle maps a stable-absent sentinel through decode without raising (pins INV-001).
    // The JS expression always returns the literal string "__absent__". With a tight assertionStabilityWindow,
    // the sentinel is trivially stable and decode maps it to Absent. No BrowserAssertionTimedOutException is raised.
    "settle maps a stable-absent sentinel through decode without raising" in run {
        withBrowser {
            onPage("<body></body>") {
                Browser.withConfig(_.assertionStabilityWindow(50.millis)) {
                    SettleRead.settle("settle-absent", "'__absent__'") { raw =>
                        if raw == "__absent__" then Absent
                        else Present(raw)
                    }.map { result =>
                        assert(result == Absent, s"expected Absent but got $result")
                    }
                }
            }
        }
    }

    // Test 3: settle re-samples on a never-converging expression and aborts (pins INV-001, INV-008).
    // The JS counter flickers at 5ms, faster than the 100ms assertionStabilityWindow, so sampleWindow
    // always returns Unstable. The retrySchedule is capped at 300ms so the test completes quickly.
    // The result must be a Failure containing a BrowserReadException.
    "settle re-samples on a never-converging expression and aborts" in run {
        withBrowser {
            onPage("""<body>
                <script>
                    window.__kyoFlicker = Date.now();
                    setInterval(() => { window.__kyoFlicker = Date.now(); }, 5);
                </script>
            </body>""") {
                Browser.withConfig(
                    _.retrySchedule(Schedule.fixed(50.millis).maxDuration(300.millis))
                        .assertionStabilityWindow(100.millis)
                ) {
                    Abort.run[BrowserReadException] {
                        SettleRead.settle("settle-never-converges", "String(window.__kyoFlicker)") { raw =>
                            raw
                        }
                    }.map { result =>
                        assert(result.isFailure, s"expected a Failure but got $result")
                    }
                }
            }
        }
    }

    // Test 4: settle reads its bound from configLocal, not a hardcoded literal (pins INV-008, PRE-004).
    // Two runs use the same never-converging counter. Override A uses a tight 200ms retrySchedule;
    // override B uses a wider 600ms retrySchedule. Both must abort a BrowserReadException.
    // The elapsed time under A must be strictly less than the elapsed time under B,
    // proving the bound comes from configLocal rather than a hardcoded constant.
    "settle reads its bound from configLocal and not a hardcoded constant" in run {
        withBrowser {
            onPage("""<body>
                <script>
                    window.__kyoFlicker2 = Date.now();
                    setInterval(() => { window.__kyoFlicker2 = Date.now(); }, 5);
                </script>
            </body>""") {
                val runSettle =
                    Abort.run[BrowserReadException] {
                        SettleRead.settle("settle-config-bound", "String(window.__kyoFlicker2)") { raw =>
                            raw
                        }
                    }
                timed(Browser.withConfig(
                    _.retrySchedule(Schedule.fixed(50.millis).maxDuration(200.millis))
                        .assertionStabilityWindow(80.millis)
                )(runSettle)).map { case (elapsedA, resultA) =>
                    timed(Browser.withConfig(
                        _.retrySchedule(Schedule.fixed(50.millis).maxDuration(600.millis))
                            .assertionStabilityWindow(80.millis)
                    )(runSettle)).map { case (elapsedB, resultB) =>
                        assert(resultA.isFailure, s"expected Failure under override A but got $resultA")
                        assert(resultB.isFailure, s"expected Failure under override B but got $resultB")
                        assert(elapsedA < 500.millis, s"override A elapsed ${elapsedA} exceeds 500ms ceiling")
                        assert(elapsedB < 900.millis, s"override B elapsed ${elapsedB} exceeds 900ms ceiling")
                        assert(
                            elapsedA < elapsedB,
                            s"expected override A ($elapsedA) to finish before override B ($elapsedB)"
                        )
                    }
                }
            }
        }
    }

end BrowserVerifyReadTest
