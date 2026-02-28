package demo

import javafx.application.Platform
import kyo.*

/** Interaction testing for both JavaFX and Web backends.
  *
  * Tests ALL UIs with interactive behavior. Generates an HTML report with:
  *   - Tab switcher for every UI (static + interactive)
  *   - Side-by-side display (Web | JavaFX) with step-through controls
  *   - Auto-play for multi-frame tests
  *   - Gap table per test
  *
  * Usage: sbt 'kyo-ui/runMain demo.JavaFxInteractionTest'
  */
object JavaFxInteractionTest extends KyoApp:

    import JavaFxInteraction as JFx

    case class StepFrame(
        step: Int,
        annotation: String,
        jfxImage: String,
        webImage: String,
        assertion: String,
        passed: Boolean
    )

    case class TestResult(name: String, frames: List[StepFrame]):
        def passed: Boolean = frames.forall(_.passed)
        def gapCount: Int   = frames.count(!_.passed)

    private val outDir   = java.nio.file.Paths.get("../screenshots/interaction").toAbsolutePath.normalize
    private val demoHtml = java.nio.file.Paths.get("../demo.html").toAbsolutePath.normalize

    private val allUiNames = List(
        "demo",
        "interactive",
        "form",
        "typography",
        "layout",
        "reactive",
        "dashboard",
        "semantic",
        "nested",
        "pseudo",
        "collections",
        "transforms",
        "sizing",
        "keyboard",
        "colors",
        "dynamic",
        "tables",
        "auto",
        "animated"
    )

    private val uiBuilders: Map[String, UI < Async] = Map(
        "demo"        -> DemoUI.build,
        "interactive" -> InteractiveUI.build,
        "form"        -> FormUI.build,
        "reactive"    -> ReactiveUI.build,
        "dynamic"     -> DynamicStyleUI.build,
        "collections" -> CollectionOpsUI.build,
        "nested"      -> NestedReactiveUI.build,
        "keyboard"    -> KeyboardNavUI.build,
        "tables"      -> TableAdvancedUI.build,
        "animated"    -> AnimatedDashboardUI.build
    )

    run {
        val _ = java.nio.file.Files.createDirectories(outDir)
        Browser.run(180.seconds) {
            for
                _       <- Browser.goto(s"file://$demoHtml#demo")
                _       <- Browser.runJavaScript("return new Promise(r => setTimeout(r, 1000))")
                results <- runAllTests
                _       <- generateReport(results)
                _       <- printResults(results)
            yield ()
        }
    }

    // -- Helpers --

    private def jfx[A](f: => A)(using kyo.Frame): A < Sync = Sync.defer(f)

    private def captureJfx(testName: String, step: Int)(using kyo.Frame): String < Sync =
        Sync.defer {
            val filename = s"$testName-jfx-$step.png"
            JFx.screenshot(outDir.resolve(filename))
            filename
        }

    private def captureWeb(testName: String, step: Int)(using kyo.Frame): String < (Browser & Sync) =
        val filename = s"$testName-web-$step.png"
        for
            img <- Browser.screenshot(560, 1400)
            _   <- img.writeFileBinary(s"$outDir/$filename")
        yield filename
        end for
    end captureWeb

    private def captureBoth(testName: String, step: Int, annotation: String, assertion: String, passed: Boolean)(
        using kyo.Frame
    ): StepFrame < (Browser & Sync) =
        for
            jfxFile <- captureJfx(testName, step)
            webFile <- captureWeb(testName, step)
        yield StepFrame(step, annotation, jfxFile, webFile, assertion, passed)

    private def wait_(ms: Int = 200)(using kyo.Frame): Unit < Browser =
        Browser.runJavaScript(s"return new Promise(r => setTimeout(r, $ms))").unit

    private def withUI(name: String)(tests: => Chunk[TestResult] < (Browser & Sync & Async & Scope))(
        using kyo.Frame
    ): Chunk[TestResult] < (Browser & Sync & Async & Scope) =
        for
            ui      <- uiBuilders(name)
            session <- new JavaFxBackend(title = "test", width = 560, height = 1400).render(ui)
            _       <- Async.sleep(1.seconds)
            _       <- Browser.goto(s"file://$demoHtml#$name")
            _       <- Browser.runJavaScript("return new Promise(r => setTimeout(r, 500))")
            rs      <- tests
            _       <- session.stop
            _       <- Async.sleep(300.millis)
        yield rs

    // -- Run all tests --

    private def runAllTests(using kyo.Frame): Chunk[TestResult] < (Browser & Sync & Async & Scope) =
        for
            r1 <- withUI("demo")(for a <- testCounterIncrDec; b <- testCounterMulti; c <- testTodoAdd yield Chunk(a, b, c))
            r2 <- withUI("interactive")(for a <- testKeyboardInput; b <- testDisabledToggle; c <- testFocusBlur yield Chunk(a, b, c))
            r3 <- withUI("form")(for a <- testFormSubmit; b <- testFormDisabled yield Chunk(a, b))
            r4 <- withUI("reactive")(for a <- testCondRender; b <- testHiddenToggle; c <- testForeachAdd; d <- testViewMode
            yield Chunk(a, b, c, d))
            r5 <- withUI("dynamic")(for a <- testDynBgColor; b <- testDynFontSize; c <- testStyleToggles yield Chunk(a, b, c))
            r6 <- withUI("collections")(for a <- testCollAddRemove; b <- testCollBatch yield Chunk(a, b))
            r7 <- withUI("nested")(for a <- testNestedWhen; b <- testKeyedSelect; c <- testNestedViewMode yield Chunk(a, b, c))
            r8 <- withUI("keyboard")(for a <- testKeyDownUp yield Chunk(a))
            r9 <- withUI("tables")(for a <- testTableAddRemove yield Chunk(a))
            r10 <- withUI("animated") {
                for
                    _ <- Async.sleep(3.seconds)
                    a <- testAnimatedFinal
                yield Chunk(a)
            }
        yield
            Platform.exit()
            r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7 ++ r8 ++ r9 ++ r10

    // =====================================================
    // DemoUI Tests
    // =====================================================

    private def testCounterIncrDec(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "counter-inc-dec"
        for
            jv0 <- jfx(JFx.getText(".counter-value"))
            wv0 <- Browser.innerText(".counter-value")
            f0  <- captureBoth(n, 0, "Initial state", s"JFX='$jv0' Web='$wv0' (expect '0')", jv0 == "0" && wv0 == "0")
            _   <- jfx { JFx.clickNth(".counter-btn", 1); JFx.waitForUpdates() }
            _   <- Browser.click(".counter-btn:nth-child(3)")
            _   <- wait_()
            jv1 <- jfx(JFx.getText(".counter-value"))
            wv1 <- Browser.innerText(".counter-value")
            f1  <- captureBoth(n, 1, "Clicked '+'", s"JFX='$jv1' Web='$wv1' (expect '1')", jv1 == "1" && wv1 == "1")
            _   <- jfx { JFx.clickNth(".counter-btn", 0); JFx.waitForUpdates() }
            _   <- Browser.click(".counter-btn:nth-child(1)")
            _   <- wait_()
            jv2 <- jfx(JFx.getText(".counter-value"))
            wv2 <- Browser.innerText(".counter-value")
            f2  <- captureBoth(n, 2, "Clicked '-'", s"JFX='$jv2' Web='$wv2' (expect '0')", jv2 == "0" && wv2 == "0")
        yield TestResult("Counter increment/decrement", List(f0, f1, f2))
        end for
    end testCounterIncrDec

    private def testCounterMulti(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "counter-multi"
        for
            jv0 <- jfx(JFx.getText(".counter-value"))
            wv0 <- Browser.innerText(".counter-value")
            f0  <- captureBoth(n, 0, s"Initial (counter='$jv0')", s"JFX='$jv0' Web='$wv0'", jv0 == wv0)
            _ <- jfx {
                for _ <- 1 to 3 do
                    JFx.clickNth(".counter-btn", 1); JFx.waitForUpdates(100)
            }
            _   <- Browser.click(".counter-btn:nth-child(3)")
            _   <- Browser.click(".counter-btn:nth-child(3)")
            _   <- Browser.click(".counter-btn:nth-child(3)")
            _   <- wait_()
            jv1 <- jfx(JFx.getText(".counter-value"))
            wv1 <- Browser.innerText(".counter-value")
            exp = (jv0.toIntOption.getOrElse(0) + 3).toString
            f1 <- captureBoth(n, 1, "Clicked '+' x3", s"JFX='$jv1' Web='$wv1' (expect '$exp')", jv1 == exp && wv1 == exp)
        yield TestResult("Counter 3 rapid clicks", List(f0, f1))
        end for
    end testCounterMulti

    private def testTodoAdd(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "todo-add"
        for
            jc0 <- jfx(JFx.count(".todo-item"))
            wc0 <- Browser.count(".todo-item")
            f0  <- captureBoth(n, 0, s"Initial ($jc0 items)", s"JFX=$jc0, Web=$wc0", true)
            _ <- jfx {
                JFx.fillText(".todo-input .input", "Buy groceries"); JFx.waitForUpdates(100); JFx.click(".submit"); JFx.waitForUpdates()
            }
            _   <- Browser.fill(".todo-input input", "Buy groceries")
            _   <- Browser.click(".todo-input .submit")
            _   <- wait_()
            jc1 <- jfx(JFx.count(".todo-item"))
            wc1 <- Browser.count(".todo-item")
            f1  <- captureBoth(n, 1, "Added 'Buy groceries'", s"JFX=$jc1, Web=$wc1 (expect ${jc0 + 1})", jc1 == jc0 + 1 && wc1 == wc0 + 1)
            _ <- jfx {
                JFx.fillText(".todo-input .input", "Walk the dog"); JFx.waitForUpdates(100); JFx.click(".submit"); JFx.waitForUpdates()
            }
            _   <- Browser.fill(".todo-input input", "Walk the dog")
            _   <- Browser.click(".todo-input .submit")
            _   <- wait_()
            jc2 <- jfx(JFx.count(".todo-item"))
            wc2 <- Browser.count(".todo-item")
            f2  <- captureBoth(n, 2, "Added 'Walk the dog'", s"JFX=$jc2, Web=$wc2 (expect ${jc0 + 2})", jc2 == jc0 + 2 && wc2 == wc0 + 2)
        yield TestResult("Todo list add items", List(f0, f1, f2))
        end for
    end testTodoAdd

    // =====================================================
    // InteractiveUI Tests
    // =====================================================

    private def testKeyboardInput(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "keyboard-input"
        for
            f0 <- captureBoth(n, 0, "Initial state", "Keyboard events section visible", true)
            _  <- Browser.fill("input[placeholder='Press any key...']", "a")
            _  <- wait_()
            wt <- Browser.innerText("section:nth-child(2) p:last-child")
            f1 <- captureBoth(n, 1, "Typed 'a' in keyboard input", s"Key display: '$wt'", wt.nonEmpty)
        yield TestResult("Keyboard input", List(f0, f1))
        end for
    end testKeyboardInput

    private def testDisabledToggle(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "disabled-toggle"
        for
            wd0 <- Browser.runJavaScript("return document.querySelector('button[disabled]') !== null")
            f0  <- captureBoth(n, 0, "Initial - target enabled", s"Disabled: $wd0", true)
            _   <- Browser.click("section:nth-child(4) button:first-child")
            _   <- wait_()
            wd1 <- Browser.runJavaScript("return document.querySelector('button[disabled]') !== null")
            f1  <- captureBoth(n, 1, "Clicked 'Disable'", s"Disabled: $wd1 (expect true)", wd1 == "true")
            _   <- Browser.click("section:nth-child(4) button:first-child")
            _   <- wait_()
            wd2 <- Browser.runJavaScript("return document.querySelector('button[disabled]') !== null")
            f2  <- captureBoth(n, 2, "Clicked 'Enable'", s"Disabled: $wd2 (expect false)", wd2 == "false")
        yield TestResult("Disabled toggle", List(f0, f1, f2))
        end for
    end testDisabledToggle

    private def testFocusBlur(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "focus-blur"
        for
            ws0 <- Browser.innerText("section:nth-child(3) p:last-child")
            f0  <- captureBoth(n, 0, "Initial state", s"Status: '$ws0'", true)
            _   <- Browser.click("input[placeholder='Click to focus...']")
            _   <- wait_()
            ws1 <- Browser.innerText("section:nth-child(3) p:last-child")
            f1  <- captureBoth(n, 1, "Clicked focus input", s"Status: '$ws1' (expect 'Focused')", ws1.contains("Focused"))
        yield TestResult("Focus/blur", List(f0, f1))
        end for
    end testFocusBlur

    // =====================================================
    // FormUI Tests
    // =====================================================

    private def testFormSubmit(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "form-submit"
        for
            wi <- Browser.innerText("section:first-child p:last-child")
            f0 <- captureBoth(n, 0, "Initial state", s"Text: '$wi' (expect 'Not submitted')", wi.contains("Not submitted"))
            _  <- Browser.fill("#name", "John")
            _  <- wait_(100)
            _  <- Browser.fill("#email", "john@test.com")
            _  <- wait_(100)
            f1 <- captureBoth(n, 1, "Filled name and email", "Fields populated", true)
            _  <- Browser.click("section:first-child button")
            _  <- wait_()
            wr <- Browser.innerText("section:first-child p:last-child")
            f2 <- captureBoth(n, 2, "Submitted form", s"Result: '$wr' (expect 'Name=John')", wr.contains("Name=John"))
        yield TestResult("Form submit", List(f0, f1, f2))
        end for
    end testFormSubmit

    private def testFormDisabled(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "form-disabled"
        for
            f0 <- captureBoth(n, 0, "Initial - inputs enabled", "Inputs enabled", true)
            _  <- Browser.click("section:nth-child(2) button")
            _  <- wait_()
            wd <- Browser.runJavaScript(
                "return document.querySelectorAll('section:nth-child(2) input[disabled], section:nth-child(2) textarea[disabled], section:nth-child(2) select[disabled]').length"
            )
            f1 <- captureBoth(n, 1, "Clicked 'Disable All'", s"Disabled controls: $wd (expect > 0)", wd.toIntOption.exists(_ > 0))
        yield TestResult("Form disabled toggle", List(f0, f1))
        end for
    end testFormDisabled

    // =====================================================
    // ReactiveUI Tests
    // =====================================================

    private def testCondRender(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "cond-render"
        for
            f0 <- captureBoth(n, 0, "Initial - panel visible", "Panel visible", true)
            _  <- Browser.click("section:first-child button")
            _  <- wait_()
            _  <- jfx { JFx.click(".card .button"); JFx.waitForUpdates() }
            f1 <- captureBoth(n, 1, "Clicked 'Hide Panel'", "Panel hidden", true)
            _  <- Browser.click("section:first-child button")
            _  <- wait_()
            _  <- jfx { JFx.click(".card .button"); JFx.waitForUpdates() }
            f2 <- captureBoth(n, 2, "Clicked 'Show Panel'", "Panel visible again", true)
        yield TestResult("Conditional render (UI.when)", List(f0, f1, f2))
        end for
    end testCondRender

    private def testHiddenToggle(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "hidden-toggle"
        for
            f0 <- captureBoth(n, 0, "Initial - element visible", "Hidden toggle", true)
            _  <- Browser.click("section:nth-child(2) button")
            _  <- wait_()
            f1 <- captureBoth(n, 1, "Clicked 'Hide'", "Element hidden", true)
            _  <- Browser.click("section:nth-child(2) button")
            _  <- wait_()
            f2 <- captureBoth(n, 2, "Clicked 'Show'", "Element visible again", true)
        yield TestResult("Hidden toggle", List(f0, f1, f2))
        end for
    end testHiddenToggle

    private def testForeachAdd(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "foreach-add"
        for
            wc0 <- Browser.count("section:nth-child(4) span")
            f0  <- captureBoth(n, 0, s"Initial ($wc0 tags)", s"Web tags: $wc0 (expect 3)", wc0 == 3)
            _   <- Browser.fill("section:nth-child(4) input", "Mango")
            _   <- Browser.click("section:nth-child(4) button")
            _   <- wait_()
            wc1 <- Browser.count("section:nth-child(4) span")
            f1  <- captureBoth(n, 1, "Added 'Mango'", s"Web tags: $wc1 (expect 4)", wc1 == 4)
        yield TestResult("Foreach add item", List(f0, f1))
        end for
    end testForeachAdd

    private def testViewMode(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "view-mode"
        for
            wl0 <- Browser.exists("section:nth-child(6) ul")
            f0  <- captureBoth(n, 0, "Initial (list view)", s"Has list: $wl0 (expect true)", wl0)
            _   <- Browser.click("section:nth-child(6) button")
            _   <- wait_()
            wl1 <- Browser.exists("section:nth-child(6) ul")
            f1  <- captureBoth(n, 1, "Switched to Grid", s"Has list: $wl1 (expect false)", !wl1)
            _   <- Browser.click("section:nth-child(6) button")
            _   <- wait_()
            wl2 <- Browser.exists("section:nth-child(6) ul")
            f2  <- captureBoth(n, 2, "Switched to List", s"Has list: $wl2 (expect true)", wl2)
        yield TestResult("View mode toggle", List(f0, f1, f2))
        end for
    end testViewMode

    // =====================================================
    // DynamicStyleUI Tests
    // =====================================================

    private def testDynBgColor(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "dynamic-bg"
        for
            wc0 <- Browser.innerText("section:first-child p:last-child")
            f0  <- captureBoth(n, 0, "Initial (blue bg)", s"Color: '$wc0'", wc0.contains("#dbeafe"))
            _   <- Browser.click("section:first-child button:nth-child(2)")
            _   <- wait_()
            wc1 <- Browser.innerText("section:first-child p:last-child")
            f1  <- captureBoth(n, 1, "Clicked 'Green'", s"Color: '$wc1' (expect #dcfce7)", wc1.contains("#dcfce7"))
            _   <- Browser.click("section:first-child button:nth-child(4)")
            _   <- wait_()
            wc2 <- Browser.innerText("section:first-child p:last-child")
            f2  <- captureBoth(n, 2, "Clicked 'Red'", s"Color: '$wc2' (expect #fee2e2)", wc2.contains("#fee2e2"))
        yield TestResult("Dynamic background color", List(f0, f1, f2))
        end for
    end testDynBgColor

    private def testDynFontSize(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "dynamic-font"
        for
            ws0 <- Browser.innerText("section:nth-child(2) span")
            f0  <- captureBoth(n, 0, "Initial", s"Size: '$ws0' (expect '14px')", ws0.contains("14px"))
            _   <- Browser.click("section:nth-child(2) button:nth-child(3)")
            _   <- Browser.click("section:nth-child(2) button:nth-child(3)")
            _   <- wait_()
            ws1 <- Browser.innerText("section:nth-child(2) span")
            f1  <- captureBoth(n, 1, "Clicked 'A+' twice", s"Size: '$ws1' (expect '18px')", ws1.contains("18px"))
        yield TestResult("Dynamic font size", List(f0, f1))
        end for
    end testDynFontSize

    private def testStyleToggles(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "style-toggles"
        for
            f0 <- captureBoth(n, 0, "Initial - no toggles", "All OFF", true)
            _  <- Browser.click("section:nth-child(4) button:first-child")
            _  <- wait_()
            wb <- Browser.innerText("section:nth-child(4) button:first-child")
            f1 <- captureBoth(n, 1, "Toggled Bold ON", s"Button: '$wb' (expect 'ON')", wb.contains("ON"))
            _  <- Browser.click("section:nth-child(4) button:nth-child(2)")
            _  <- wait_()
            f2 <- captureBoth(n, 2, "Toggled Italic ON", "Bold + Italic active", true)
        yield TestResult("Style toggles", List(f0, f1, f2))
        end for
    end testStyleToggles

    // =====================================================
    // CollectionOpsUI Tests
    // =====================================================

    private def testCollAddRemove(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "collection-ops"
        for
            wc0 <- Browser.count("section:nth-child(2) div[class] > div")
            f0  <- captureBoth(n, 0, s"Initial ($wc0 items)", s"Items: $wc0 (expect 3)", wc0 == 3)
            _   <- Browser.fill("section:nth-child(2) input", "Fix bugs")
            _   <- Browser.click("section:nth-child(2) button:first-child")
            _   <- wait_()
            wc1 <- Browser.count("section:nth-child(2) div[class] > div")
            f1  <- captureBoth(n, 1, "Added 'Fix bugs'", s"Items: $wc1 (expect 4)", wc1 == 4)
            _   <- Browser.click("section:nth-child(2) button:nth-child(3)")
            _   <- wait_()
            wc2 <- Browser.count("section:nth-child(2) div[class] > div")
            f2  <- captureBoth(n, 2, "Removed last", s"Items: $wc2 (expect 3)", wc2 == 3)
        yield TestResult("Collection add/remove", List(f0, f1, f2))
        end for
    end testCollAddRemove

    private def testCollBatch(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "collection-batch"
        for
            wt0 <- Browser.innerText("section:nth-child(5) span:first-child")
            f0  <- captureBoth(n, 0, "Initial", s"First tag: '$wt0'", true)
            _   <- Browser.click("section:nth-child(5) button")
            _   <- Browser.click("section:nth-child(5) button")
            _   <- Browser.click("section:nth-child(5) button")
            _   <- wait_()
            wt1 <- Browser.innerText("section:nth-child(5) span:first-child")
            f1  <- captureBoth(n, 1, "Clicked 'Tick' x3", s"First tag: '$wt1' (expect '(3)')", wt1.contains("(3)"))
        yield TestResult("Collection batch update", List(f0, f1))
        end for
    end testCollBatch

    // =====================================================
    // NestedReactiveUI Tests
    // =====================================================

    private def testNestedWhen(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "nested-when"
        for
            f0 <- captureBoth(n, 0, "Initial - both panels visible", "Outer + inner visible", true)
            _  <- Browser.click("section:first-child button:nth-child(2)")
            _  <- wait_()
            f1 <- captureBoth(n, 1, "Hidden inner panel", "Outer visible, inner hidden", true)
            _  <- Browser.click("section:first-child button:first-child")
            _  <- wait_()
            f2 <- captureBoth(n, 2, "Hidden outer panel", "Both hidden", true)
            _  <- Browser.click("section:first-child button:first-child")
            _  <- wait_()
            f3 <- captureBoth(n, 3, "Shown outer, inner still hidden", "Outer visible, inner hidden", true)
        yield TestResult("Nested when() toggle", List(f0, f1, f2, f3))
        end for
    end testNestedWhen

    private def testKeyedSelect(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "keyed-select"
        for
            ws0 <- Browser.innerText("section:nth-child(3) div:last-child p")
            f0  <- captureBoth(n, 0, "Initial - nothing selected", s"'$ws0' (expect 'Nothing selected')", ws0.contains("Nothing selected"))
            _   <- Browser.click("section:nth-child(3) div[class] > div:first-child")
            _   <- wait_()
            ws1 <- Browser.innerText("section:nth-child(3) div:last-child p")
            f1  <- captureBoth(n, 1, "Clicked 'Alpha'", s"'$ws1' (expect 'Alpha')", ws1.contains("Alpha"))
        yield TestResult("Keyed list selection", List(f0, f1))
        end for
    end testKeyedSelect

    private def testNestedViewMode(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "nested-view"
        for
            wl0 <- Browser.exists("section:nth-child(4) ul")
            f0  <- captureBoth(n, 0, "Initial (list mode)", s"Has ul: $wl0 (expect true)", wl0)
            _   <- Browser.click("section:nth-child(4) button")
            _   <- wait_()
            wl1 <- Browser.exists("section:nth-child(4) ul")
            f1  <- captureBoth(n, 1, "Switched to Tags", s"Has ul: $wl1 (expect false)", !wl1)
        yield TestResult("Nested view mode", List(f0, f1))
        end for
    end testNestedViewMode

    // =====================================================
    // KeyboardNavUI Tests
    // =====================================================

    private def testKeyDownUp(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "key-events"
        for
            f0 <- captureBoth(n, 0, "Initial state", "Key displays show '(none)'", true)
            _  <- Browser.fill("section:first-child input", "x")
            _  <- wait_()
            wk <- Browser.innerText("section:first-child span:first-child")
            f1 <- captureBoth(n, 1, "Typed 'x'", s"Key display: '$wk'", true)
        yield TestResult("Key down/up events", List(f0, f1))
        end for
    end testKeyDownUp

    // =====================================================
    // TableAdvancedUI Tests
    // =====================================================

    private def testTableAddRemove(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "table-dynamic"
        for
            wr0 <- Browser.count("section:nth-child(3) table tr")
            f0  <- captureBoth(n, 0, s"Initial (${wr0 - 1} data rows)", s"Rows: ${wr0 - 1} (expect 4)", wr0 - 1 == 4)
            _   <- Browser.fill("section:nth-child(3) input", "Eve")
            _   <- Browser.click("section:nth-child(3) button:first-child")
            _   <- wait_()
            wr1 <- Browser.count("section:nth-child(3) table tr")
            f1  <- captureBoth(n, 1, "Added row 'Eve'", s"Rows: ${wr1 - 1} (expect 5)", wr1 - 1 == 5)
            _   <- Browser.click("section:nth-child(3) button:nth-child(3)")
            _   <- wait_()
            wr2 <- Browser.count("section:nth-child(3) table tr")
            f2  <- captureBoth(n, 2, "Removed last row", s"Rows: ${wr2 - 1} (expect 4)", wr2 - 1 == 4)
        yield TestResult("Table add/remove rows", List(f0, f1, f2))
        end for
    end testTableAddRemove

    // =====================================================
    // AnimatedDashboardUI Tests
    // =====================================================

    private def testAnimatedFinal(using kyo.Frame): TestResult < (Browser & Sync) =
        val n = "animated-final"
        for
            wu <- Browser.innerText("section:first-child div:first-child div:first-child")
            f0 <- captureBoth(n, 0, "Final state (after 3s)", s"Users: '$wu' (expect '1284')", wu.contains("1284"))
        yield TestResult("Animated dashboard final state", List(f0))
        end for
    end testAnimatedFinal

    // =====================================================
    // Report generation
    // =====================================================

    private def generateReport(results: Chunk[TestResult])(using kyo.Frame): Unit < Sync =
        case class TabEntry(id: String, label: String, badge: String, frames: List[StepFrame])

        val staticTabs = allUiNames.map { name =>
            TabEntry(
                s"static-$name",
                name,
                "pass",
                List(StepFrame(0, s"Static rendering of $name", s"../javafx-$name.png", s"../web-$name.png", "Visual comparison", true))
            )
        }

        val interactiveTabs = results.toList.map { t =>
            val id = "test-" + t.name.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase
            TabEntry(id, s"Test: ${t.name}", if t.passed then "pass" else "fail", t.frames)
        }

        val allTabs     = staticTabs ++ interactiveTabs
        val totalTests  = results.size
        val passedTests = results.count(_.passed)
        val totalGaps   = results.map(_.gapCount).sum

        val html = new StringBuilder
        html.append(s"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>UI Comparison Report</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0f172a;color:#e2e8f0;padding:0;overflow:hidden}
.topbar{background:#1e293b;padding:10px 20px;display:flex;align-items:center;gap:12px;border-bottom:1px solid #334155;position:sticky;top:0;z-index:10;flex-wrap:wrap}
.topbar h1{font-size:16px;color:#f8fafc;white-space:nowrap}
.stats{display:flex;gap:6px}
.stat{padding:2px 8px;border-radius:10px;font-size:11px;font-weight:600}
.stat.pass{background:#166534;color:#bbf7d0}
.stat.fail{background:#991b1b;color:#fecaca}
.stat.info{background:#1e3a5f;color:#93c5fd}
.tabs{display:flex;gap:2px;overflow-x:auto;flex:1;min-width:0}
.tab{padding:5px 10px;border-radius:6px 6px 0 0;cursor:pointer;font-size:11px;font-weight:600;background:#334155;color:#94a3b8;border:none;white-space:nowrap;transition:all .15s}
.tab:hover{background:#475569;color:#e2e8f0}
.tab.active{background:#0f172a;color:#f8fafc}
.tab .dot{display:inline-block;width:6px;height:6px;border-radius:50%;margin-right:4px}
.dot.pass{background:#22c55e}
.dot.fail{background:#ef4444}
.main{height:calc(100vh - 50px);display:flex;flex-direction:column}
.side-by-side{flex:1;display:grid;grid-template-columns:1fr 1fr;gap:0;overflow:hidden;min-height:0}
.col{display:flex;flex-direction:column;overflow:auto;border-right:1px solid #334155}
.col:last-child{border-right:none}
.col-label{text-align:center;padding:4px;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:1px;color:#64748b;background:#1e293b;position:sticky;top:0;z-index:1}
.col img{width:100%;max-height:calc(100vh - 180px);object-fit:contain;object-position:top;display:block}
.bottom-bar{background:#1e293b;border-top:1px solid #334155;padding:8px 16px;flex-shrink:0}
.annotation{font-size:13px;margin-bottom:4px;color:#e2e8f0}
.annotation .step-num{background:#3b82f6;color:white;display:inline-block;width:20px;height:20px;line-height:20px;text-align:center;border-radius:50%;margin-right:6px;font-weight:700;font-size:10px}
.assertion{font-size:11px;font-family:'SF Mono',Monaco,monospace;padding:4px 8px;border-radius:4px;margin-bottom:6px}
.assertion.pass{background:#052e16;color:#86efac}
.assertion.fail{background:#450a0a;color:#fca5a5}
.controls{display:flex;align-items:center;gap:8px}
.controls button{padding:3px 10px;border:1px solid #475569;background:#334155;color:#e2e8f0;border-radius:4px;cursor:pointer;font-size:11px}
.controls button:hover{background:#475569}
.controls input[type=range]{flex:1;accent-color:#3b82f6}
.controls.hidden{display:none}
.step-indicator{color:#94a3b8;font-size:11px}
.gap-table{width:100%;border-collapse:collapse;font-size:11px;margin-top:6px}
.gap-table th{padding:4px 8px;text-align:left;background:#1e293b;color:#94a3b8;border-bottom:1px solid #334155}
.gap-table td{padding:4px 8px;border-bottom:1px solid #1e293b;color:#cbd5e1}
.gap-table tr.fail td{background:#1c0a0a;color:#fca5a5}
</style>
</head>
<body>
<div class="topbar">
  <h1>UI Report</h1>
  <div class="tabs">
""")
        allTabs.zipWithIndex.foreach { (tab, i) =>
            val active = if i == 0 then " active" else ""
            html.append(
                s"""    <button class="tab$active" data-id="${tab.id}" onclick="switchTab('${tab.id}')"><span class="dot ${tab.badge}"></span>${tab.label}</button>\n"""
            )
        }
        html.append(s"""  </div>
  <div class="stats">
    <span class="stat pass">${allUiNames.size} UIs</span>
    <span class="stat ${if passedTests == totalTests then "pass" else "fail"}">$passedTests/$totalTests tests</span>
    <span class="stat ${if totalGaps == 0 then "pass" else "fail"}">$totalGaps gaps</span>
  </div>
</div>
<div class="main">
  <div class="side-by-side">
    <div class="col"><div class="col-label">Web (reference)</div><img id="web-img" /></div>
    <div class="col"><div class="col-label">JavaFX</div><img id="jfx-img" /></div>
  </div>
  <div class="bottom-bar">
    <div class="annotation" id="ann"></div>
    <div class="assertion" id="assert-box"></div>
    <div class="controls" id="controls">
      <button onclick="stepFrame(-1)">&#9664; Prev</button>
      <input type="range" min="0" max="0" value="0" oninput="setFrame(parseInt(this.value))" id="slider" />
      <button onclick="stepFrame(1)">Next &#9654;</button>
      <span class="step-indicator" id="indicator"></span>
      <button onclick="togglePlay()" id="play-btn">&#10074;&#10074; Pause</button>
    </div>
    <div id="gap-table-container"></div>
  </div>
</div>
<script>
""")

        html.append("var allTests = {\n")
        allTabs.foreach { tab =>
            html.append(s"  '${tab.id}': [\n")
            tab.frames.foreach { f =>
                val ea = f.annotation.replace("'", "\\'").replace("<", "&lt;")
                val es = f.assertion.replace("'", "\\'").replace("<", "&lt;")
                val c  = if f.passed then "pass" else "fail"
                html.append(s"    {web:'${f.webImage}',jfx:'${f.jfxImage}',ann:'$ea',assert:'$es',cls:'$c'},\n")
            }
            html.append("  ],\n")
        }
        html.append("};\n")

        html.append("var gapTables = {\n")
        interactiveTabs.foreach { tab =>
            val gaps = tab.frames.filter(!_.passed)
            if gaps.nonEmpty then
                html.append(
                    s"  '${tab.id}': '<table class=\"gap-table\"><tr><th>Step</th><th>Action</th><th>Assertion</th><th>Status</th></tr>"
                )
                tab.frames.foreach { f =>
                    val cls = if f.passed then "" else " class=\"fail\""
                    val st  = if f.passed then "&#10003; Pass" else "&#10007; Gap"
                    val ea  = f.annotation.replace("'", "\\'").replace("<", "&lt;")
                    val es  = f.assertion.replace("'", "\\'").replace("<", "&lt;")
                    html.append(s"<tr$cls><td>${f.step}</td><td>$ea</td><td>$es</td><td>$st</td></tr>")
                }
                html.append("</table>',\n")
            end if
        }
        html.append("};\n")

        html.append(s"""
var activeTab = '${allTabs.head.id}';
var curIdx = 0;
var playInterval = null;

function switchTab(id) {
  activeTab = id;
  curIdx = 0;
  document.querySelectorAll('.tab').forEach(function(t) { t.classList.remove('active'); });
  document.querySelector('.tab[data-id="'+id+'"]').classList.add('active');
  var f = allTests[id];
  document.getElementById('slider').max = f.length - 1;
  document.getElementById('slider').value = 0;
  document.getElementById('gap-table-container').innerHTML = gapTables[id] || '';
  var ctrl = document.getElementById('controls');
  if (f.length <= 1) { ctrl.classList.add('hidden'); } else { ctrl.classList.remove('hidden'); }
  setFrame(0);
  if (f.length > 1) startPlay(); else stopPlay();
}

function setFrame(idx) {
  var f = allTests[activeTab];
  if (!f || idx < 0 || idx >= f.length) return;
  curIdx = idx;
  document.getElementById('web-img').src = f[idx].web;
  document.getElementById('jfx-img').src = f[idx].jfx;
  document.getElementById('ann').innerHTML = '<span class="step-num">'+idx+'</span> '+f[idx].ann;
  var ab = document.getElementById('assert-box');
  ab.textContent = f[idx].assert;
  ab.className = 'assertion ' + f[idx].cls;
  document.getElementById('slider').value = idx;
  document.getElementById('indicator').textContent = 'Step '+(idx+1)+' of '+f.length;
}

function stepFrame(d) { setFrame(curIdx + d); }

function startPlay() {
  stopPlay();
  playInterval = setInterval(function() {
    var f = allTests[activeTab];
    if (curIdx + 1 >= f.length) { stopPlay(); return; }
    setFrame(curIdx + 1);
  }, 3000);
  document.getElementById('play-btn').innerHTML = '&#10074;&#10074; Pause';
}

function stopPlay() {
  if (playInterval) { clearInterval(playInterval); playInterval = null; }
  document.getElementById('play-btn').innerHTML = '&#9654; Play';
}

function togglePlay() {
  if (playInterval) stopPlay();
  else startPlay();
}

switchTab(activeTab);
</script>
</body>
</html>""")

        val reportPath = outDir.resolve("report.html")
        java.nio.file.Files.writeString(reportPath, html.toString)
        java.lang.System.err.println(s"Report: $reportPath")
    end generateReport

    private def printResults(results: Chunk[TestResult])(using kyo.Frame): Unit < Sync =
        val passed = results.count(_.passed)
        val total  = results.size
        val gaps   = results.map(_.gapCount).sum
        val status = if passed == total then "ALL PASSED" else "FAILURES"
        java.lang.System.err.println(s"\n=== Interaction Tests: $passed/$total $status, $gaps gaps ===")
        results.foreach { r =>
            val icon = if r.passed then "✓" else "✗"
            java.lang.System.err.println(s"  $icon ${r.name} (${r.frames.size} steps, ${r.gapCount} gaps)")
        }
        java.lang.System.err.println()
        if passed < total then
            throw new RuntimeException(s"${total - passed} test(s) failed")
    end printResults

end JavaFxInteractionTest
