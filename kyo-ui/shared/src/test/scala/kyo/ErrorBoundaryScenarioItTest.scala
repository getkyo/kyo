package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class ErrorBoundaryScenarioItTest extends UITest:

    "handler throws other elements still interactive" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Boom").id("boom").onClick(Abort.panic(new RuntimeException("section2 boom"))),
                UI.button("Good").id("good").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("boom"))
                _ <- Browser.click(Selector.id("good"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "onInput throws type again works" in run {
        val app: UI < Async =
            for
                broken  <- Signal.initRef(true)
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("i").onInput { _ =>
                    for
                        b <- broken.get
                        _ <- if b then Abort.panic(new RuntimeException("boom")) else counter.getAndUpdate(_ + 1).unit
                    yield ()
                },
                UI.button("Fix").id("fix").onClick(broken.set(false)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "a")
                _ <- Browser.click(Selector.id("fix"))
                _ <- Browser.fill(Selector.id("i"), "b")
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "handler throws first call succeeds second" in run {
        val app: UI < Async =
            for callCount <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").onClick {
                    callCount.getAndUpdate(_ + 1).map { n =>
                        if n == 0 then Abort.panic(new RuntimeException("first boom"))
                        else ()
                    }
                },
                callCount.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "2")
            yield succeed
        }
    }

    "multiple buttons one throws others work" in run {
        val app: UI < Async =
            for bCount <- Signal.initRef(0)
            yield UI.div(
                UI.button("A").id("a").onClick(Abort.panic(new RuntimeException("a boom"))),
                UI.button("B").id("b").onClick(bCount.getAndUpdate(_ + 1).unit),
                bCount.map(n => UI.span(s"b:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "b:2")
            yield succeed
        }
    }

    "handler throws retry succeeds state correct" in run {
        val app: UI < Async =
            for
                attempts <- Signal.initRef(0)
                result   <- Signal.initRef("")
            yield UI.div(
                UI.button("Go").id("b").onClick {
                    attempts.getAndUpdate(_ + 1).map { n =>
                        if n < 2 then Abort.panic(new RuntimeException(s"attempt $n failed"))
                        else result.set(s"success on attempt $n")
                    }
                },
                result.map(v => UI.span(v).id("v")),
                attempts.map(n => UI.span(s"attempts:$n").id("va"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "success on attempt 2")
            yield succeed
        }
    }

end ErrorBoundaryScenarioItTest
