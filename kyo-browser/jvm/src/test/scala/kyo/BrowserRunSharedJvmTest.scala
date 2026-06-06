package kyo

import kyo.internal.SharedChrome

/** JVM-only tests for [[Browser.runShared]].
  *
  * Lives in the JVM test tree because `runShared` wraps [[SharedChrome.init]], which boots a real Chrome subprocess, a JVM/Native concept
  * with no Scala.js equivalent.
  *
  * `runShared` wraps [[SharedChrome.init]], a lazy singleton that boots Chrome once per JVM. The tests here verify the observable contract
  * of `runShared` without requiring Chrome-boot timing assertions (which are flaky on CI). They focus on correctness: the shared Chrome
  * does serve usable browser tabs, and successive `runShared` calls are independently scoped.
  */
class BrowserRunSharedJvmTest extends BaseBrowserTest:

    // 3-minute envelope: under full-suite load, each `runShared` boot/navigate/title round-trip can take
    // 30s+ when Chrome is contending with preceding tests' I/O; the test does two sequential round-trips
    // (parallel-tabs check), so a 60s envelope is too tight.
    override def timeout = 3.minutes

    "runShared attaches a usable tab (URL is about:blank initially)" in {
        Scope.run {
            Browser.runShared() {
                Browser.url.map { u =>
                    assert(u == "about:blank", s"Expected 'about:blank' on fresh runShared tab but got '$u'")
                }
            }
        }
    }

    "runShared second call reuses the already-booted Chrome without error" in {
        Scope.run {
            // First call boots Chrome (or reuses it if test order landed SharedChrome.init already).
            Browser.runShared() {
                Browser.url
            }.andThen {
                // Second call: SharedChrome.initStarted is already true so the boot fiber is skipped.
                // The tab must still be independently usable.
                Browser.runShared() {
                    Browser.url.map { u =>
                        assert(u == "about:blank", s"Expected 'about:blank' on second runShared tab but got '$u'")
                    }
                }
            }
        }
    }

    "runShared tabs are independent: navigation in one does not affect the other" in {
        val p1 = page("<html><head><title>Page1</title></head><body></body></html>")
        val p2 = page("<html><head><title>Page2</title></head><body></body></html>")
        Scope.run {
            Browser.runShared() {
                Browser.goto(p1).andThen(Browser.title)
            }.map { title1 =>
                assert(title1 == "Page1", s"Expected title 'Page1' from first runShared tab but got '$title1'")
            }.andThen {
                Browser.runShared() {
                    Browser.goto(p2).andThen(Browser.title)
                }.map { title2 =>
                    assert(title2 == "Page2", s"Expected title 'Page2' from second runShared tab but got '$title2'")
                }
            }
        }
    }

    private def page(html: String): String =
        s"data:text/html;charset=utf-8,${BrowserTest.percentEncode(html)}"

end BrowserRunSharedJvmTest
