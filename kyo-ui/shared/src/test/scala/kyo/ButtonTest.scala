package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class ButtonTest extends UITest:

    // ---- Rendering ----

    "renders button with text" in run {
        withUI(UI.div(UI.button("Click me").id("b"))) {
            Browser.assertText(Selector.id("b"), "Click me").andThen(succeed)
        }
    }

    "renders multiple children" in run {
        withUI(UI.div(UI.button(UI.span("A").id("a"), UI.span("B").id("b")).id("btn"))) {
            for
                _ <- Browser.assertVisible(Selector.id("a"))
                _ <- Browser.assertVisible(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("a"), "A")
                _ <- Browser.assertText(Selector.id("b"), "B")
            yield succeed
        }
    }

    "disabled attribute present when true" in run {
        withUI(UI.div(UI.button("X").disabled(true).id("b"))) {
            Browser.assertDisabled(Selector.id("b")).andThen(succeed)
        }
    }

    "empty button renders" in run {
        withUI(UI.div(UI.button.id("b"))) {
            Browser.assertVisible(Selector.id("b")).andThen(succeed)
        }
    }

    "button with mixed children span and text" in run {
        withUI(UI.div(UI.button(UI.span("inner").id("s"), "text").id("b"))) {
            for
                _ <- Browser.assertVisible(Selector.id("s"))
                _ <- Browser.assertText(Selector.id("s"), "inner")
            yield succeed
        }
    }

    "default type is submit" in run {
        withUI(UI.div(UI.button("X").id("b"))) {
            Browser.assertAttribute(Selector.id("b"), "type", "submit").andThen(succeed)
        }
    }

    // ---- Click (pending due to kyo-browser double-click bug) ----

    "click button increments counter" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Click me").id("btn").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(s"count:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "count:0")
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "count:1")
            yield succeed
        }
    }

    "enabled button fires onClick" in run {

        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "disabled button is not clickable" in run {

        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").disabled(true).onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield succeed
        }
    }

    "disabled via signal blocks click then enables" in run {

        val app: UI < Async =
            for
                counter  <- Signal.initRef(0)
                disabled <- Signal.initRef(true)
            yield UI.div(
                UI.button("Go").id("b").onClick(counter.getAndUpdate(_ + 1).unit).disabled(disabled),
                UI.button("Enable").id("en").onClick(disabled.set(false)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "0")
                _ <- Browser.click(Selector.id("en"))
                _ <- Browser.assertEnabled(Selector.id("b"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "click updates sibling reactively" in run {

        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("+").id("b").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "1")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "2")
            yield succeed
        }
    }

    "rapid 5 clicks counter equals 5" in run {

        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "5")
            yield succeed
        }
    }

    "two buttons click one other unchanged" in run {

        val app: UI < Async =
            for
                a <- Signal.initRef(0)
                b <- Signal.initRef(0)
            yield UI.div(
                UI.button("A").id("a").onClick(a.getAndUpdate(_ + 1).unit),
                UI.button("B").id("b").onClick(b.getAndUpdate(_ + 1).unit),
                a.map(n => UI.span(s"a:$n").id("va")),
                b.map(n => UI.span(s"b:$n").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("va"), "a:1")
                _ <- Browser.assertText(Selector.id("vb"), "b:0")
            yield succeed
        }
    }

    "click handler reads signal value" in run {

        val app: UI < Async =
            for
                source <- Signal.initRef(10)
                target <- Signal.initRef(0)
            yield UI.div(
                UI.button("Copy").id("b").onClick {
                    for
                        v <- source.get
                        _ <- target.set(v)
                    yield ()
                },
                target.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "10")
            yield succeed
        }
    }

    "click handler updates two signals" in run {

        val app: UI < Async =
            for
                a <- Signal.initRef(0)
                b <- Signal.initRef(0)
            yield UI.div(
                UI.button("Both").id("b").onClick {
                    a.getAndUpdate(_ + 1).unit.andThen(b.getAndUpdate(_ + 10).unit)
                },
                a.map(n => UI.span(s"a:$n").id("va")),
                b.map(n => UI.span(s"b:$n").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("va"), "a:1")
                _ <- Browser.assertText(Selector.id("vb"), "b:10")
            yield succeed
        }
    }

    "click on nested child bubbles to button onClick" in run {

        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button(UI.span("inner").id("inner")).id("b").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("inner"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    // ---- Focus ----

    "focus button onFocus fires" in run {

        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.button("X").id("b").onFocus(ref.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "focus then elsewhere onBlur fires" in run {

        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.button("A").id("a").onBlur(ref.set(true)),
                UI.button("B").id("b2"),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.click(Selector.id("b2"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    // ---- Keyboard ----

    "pressKey Enter on button fires onClick" in run {

        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("b"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "pressKey Space on button fires onClick" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("b"), Key.Space)
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "button with style attribute" in run {
        withUI(UI.div(UI.button("Styled").id("b").style(_.bold))) {
            Browser.assertAttribute(Selector.id("b"), "style", "font-weight: bold;").andThen(succeed)
        }
    }

end ButtonTest
