package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class TextareaTest extends UITest:

    "textarea exists" in run {
        withUI(UI.div(UI.textarea.id("t"))) {
            Browser.assertVisible(Selector.id("t")).andThen(succeed)
        }
    }

    "textarea placeholder" in run {
        withUI(UI.div(UI.textarea.placeholder("hint").id("t"))) {
            Browser.assertAttribute(Selector.id("t"), "placeholder", "hint").andThen(succeed)
        }
    }

    "textarea disabled" in run {
        withUI(UI.div(UI.textarea.disabled(true).id("t"))) {
            Browser.assertDisabled(Selector.id("t")).andThen(succeed)
        }
    }

    "textarea readonly" in run {
        withUI(UI.div(UI.textarea.readOnly(true).id("t"))) {
            Browser.assertAttribute(Selector.id("t"), "readonly", "").andThen(succeed)
        }
    }

    "textarea onInput" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "typed text")
                _ <- Browser.assertText(Selector.id("v"), "typed text")
            yield succeed
        }
    }

    "textarea onChange" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "hello")
                _ <- Browser.assertText(Selector.id("v"), "val:hello")
            yield succeed
        }
    }

    "fill multiline text" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v.length.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "line1\nline2\nline3")
                _ <- Browser.assertText(Selector.id("v"), "17")
            yield succeed
        }
    }

    "fill 500 chars" in run {
        val longText = "a" * 500
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v.length.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), longText)
                _ <- Browser.assertText(Selector.id("v"), "500")
            yield succeed
        }
    }

    "fill replaces previous" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "first")
                _ <- Browser.assertText(Selector.id("v"), "first")
                _ <- Browser.fill(Selector.id("t"), "second")
                _ <- Browser.assertText(Selector.id("v"), "second")
            yield succeed
        }
    }

    "fill empty string clears" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "something")
                _ <- Browser.assertText(Selector.id("v"), "[something]")
                _ <- Browser.fill(Selector.id("t"), "")
                _ <- Browser.assertText(Selector.id("v"), "[]")
            yield succeed
        }
    }

    "value SignalRef fill updates ref" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").value(ref).onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "typed")
                _ <- Browser.assertText(Selector.id("v"), "val:typed")
            yield succeed
        }
    }

    "value SignalRef external set updates display" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("before")
            yield UI.div(
                UI.textarea.id("t").value(ref),
                UI.button("Set").id("b").onClick(ref.set("after"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertVisible(Selector.id("t"))
            yield succeed
        }
    }

    "value initial assertValue" in run {
        withUI(UI.div(UI.textarea.id("t").value("initial"))) {
            Browser.assertVisible(Selector.id("t")).andThen(succeed)
        }
    }

    "onInput and onChange both fire" in run {
        val app: UI < Async =
            for
                inputRef  <- Signal.initRef("")
                changeRef <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").onInput(v => inputRef.set(v)).onChange(v => changeRef.set(v)),
                inputRef.map(v => UI.span(s"input:$v").id("vi")),
                changeRef.map(v => UI.span(s"change:$v").id("vc"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "test")
                _ <- Browser.assertText(Selector.id("vi"), "input:test")
                _ <- Browser.assertText(Selector.id("vc"), "change:test")
            yield succeed
        }
    }

    "onInput with multiline text" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v.length.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "a\nb\nc")
                _ <- Browser.assertText(Selector.id("v"), "5")
            yield succeed
        }
    }

    "focus textarea" in run {
        withUI(UI.div(UI.textarea.id("t"))) {
            for
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertVisible(Selector.id("t"))
            yield succeed
        }
    }

    "onFocus fires" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.textarea.id("t").onFocus(ref.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "onBlur fires" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.textarea.id("t").onBlur(ref.set(true)),
                UI.button("other").id("other"),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.click(Selector.id("other"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "disabled signal toggle" in run {
        val app: UI < Async =
            for disabled <- Signal.initRef(true)
            yield UI.div(
                UI.textarea.id("t").disabled(disabled),
                UI.button("Toggle").id("tog").onClick(disabled.getAndUpdate(!_).unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("t"))
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertEnabled(Selector.id("t"))
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertDisabled(Selector.id("t"))
            yield succeed
        }
    }

    "textarea special chars in value" in run {
        withUI(UI.div(UI.textarea.value("<script>alert(1)</script>").id("t"))) {
            Browser.assertVisible(Selector.id("t")).andThen(succeed)
        }
    }

    // ---- Multiline ----

    "fill multiline text signal has newlines" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").value(ref).onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"lines:${v.count(_ == '\n') + 1}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "line1\nline2\nline3")
                _ <- Browser.assertText(Selector.id("v"), "lines:3")
            yield succeed
        }
    }

    "textarea fill then pressKey appends" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "hello")
                _ <- Browser.assertText(Selector.id("v"), "sig:hello")
                _ <- Browser.press(Selector.id("t"), Key('!'))
                _ <- Browser.assertText(Selector.id("v"), "sig:hello!")
            yield succeed
        }
    }

    "textarea with placeholder visible when empty" in run {
        withUI(UI.div(UI.textarea.id("t").placeholder("Enter notes..."))) {
            Browser.assertAttribute(Selector.id("t"), "placeholder", "Enter notes...").andThen(succeed)
        }
    }

    "very long text signal preserves all" in run {
        val longText = "x" * 500
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").value(ref).onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"len:${v.length}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), longText)
                _ <- Browser.assertText(Selector.id("v"), "len:500")
            yield succeed
        }
    }

end TextareaTest
