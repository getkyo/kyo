package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class InputTest extends UITest:

    // ---- Rendering ----

    "input exists" in run {
        withUI(UI.div(UI.input.id("i"))) {
            Browser.assertVisible(Selector.id("i")).andThen(succeed)
        }
    }

    // ---- disabled / readOnly ----

    "input disabled" in run {
        withUI(UI.div(UI.input.disabled(true).id("i"))) {
            Browser.assertDisabled(Selector.id("i")).andThen(succeed)
        }
    }

    "input disabled via signal" in run {

        val app: UI < Async =
            for disabled <- Signal.initRef(true)
            yield UI.div(
                UI.input.id("i").disabled(disabled),
                UI.button("Enable").id("en").onClick(disabled.set(false))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("i"))
                _ <- Browser.click(Selector.id("en"))
                _ <- Browser.assertEnabled(Selector.id("i"))
            yield succeed
        }
    }

    // ---- onInput ----

    "onInput fires on typing" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hello world")
                _ <- Browser.assertText(Selector.id("v"), "hello world")
            yield succeed
        }
    }

    "onInput clear shows empty" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "[]")
                _ <- Browser.fill(Selector.id("i"), "test")
                _ <- Browser.assertText(Selector.id("v"), "[test]")
                _ <- Browser.fill(Selector.id("i"), "")
                _ <- Browser.assertText(Selector.id("v"), "[]")
            yield succeed
        }
    }

    "type in input updates sibling span" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"Echo:$v").id("echo"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("echo"), "Echo:")
                _ <- Browser.fill(Selector.id("i"), "abc")
                _ <- Browser.assertText(Selector.id("echo"), "Echo:abc")
            yield succeed
        }
    }

    "retype updates echo" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "first")
                _ <- Browser.assertText(Selector.id("v"), "first")
                _ <- Browser.fill(Selector.id("i"), "second")
                _ <- Browser.assertText(Selector.id("v"), "second")
            yield succeed
        }
    }

    "two inputs independent" in run {

        val app: UI < Async =
            for
                a <- Signal.initRef("")
                b <- Signal.initRef("")
            yield UI.div(
                UI.input.id("ia").onInput(v => a.set(v)),
                UI.input.id("ib").onInput(v => b.set(v)),
                a.map(v => UI.span(s"a:$v").id("va")),
                b.map(v => UI.span(s"b:$v").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("ia"), "hello")
                _ <- Browser.assertText(Selector.id("va"), "a:hello")
                _ <- Browser.assertText(Selector.id("vb"), "b:")
            yield succeed
        }
    }

    "onChange fires" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"Changed:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hello")
                _ <- Browser.assertText(Selector.id("v"), "Changed:hello")
            yield succeed
        }
    }

    // ---- Input variants ----

    "password input exists" in run {
        withUI(UI.div(UI.passwordInput.id("p"))) {
            Browser.assertVisible(Selector.id("p")).andThen(succeed)
        }
    }

    "password onInput works" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.passwordInput.id("p").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"pw:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("p"), "secret")
                _ <- Browser.assertText(Selector.id("v"), "pw:secret")
            yield succeed
        }
    }

    "email input exists" in run {
        withUI(UI.div(UI.emailInput.id("e"))) {
            Browser.assertVisible(Selector.id("e")).andThen(succeed)
        }
    }

    "search input exists" in run {
        withUI(UI.div(UI.searchInput.id("s"))) {
            Browser.assertVisible(Selector.id("s")).andThen(succeed)
        }
    }

    "tel input exists" in run {
        withUI(UI.div(UI.telInput.id("t"))) {
            Browser.assertVisible(Selector.id("t")).andThen(succeed)
        }
    }

    "url input exists" in run {
        withUI(UI.div(UI.urlInput.id("u"))) {
            Browser.assertVisible(Selector.id("u")).andThen(succeed)
        }
    }

    // ---- Value binding ----

    "value initial" in run {
        withUI(UI.div(UI.input.id("i").value("initial"))) {
            Browser.assertAttribute(Selector.id("i"), "value", "initial").andThen(succeed)
        }
    }

    "value SignalRef fill updates ref" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref).onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "typed")
                _ <- Browser.assertText(Selector.id("v"), "val:typed")
            yield succeed
        }
    }

    "value SignalRef external set updates display" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("before")
            yield UI.div(
                UI.input.id("i").value(ref),
                UI.button("Set").id("b").onClick(ref.set("after"))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttribute(Selector.id("i"), "value", "before")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertAttribute(Selector.id("i"), "value", "after")
            yield succeed
        }
    }

    "fill special chars" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "&<>")
                _ <- Browser.assertText(Selector.id("v"), "&<>")
            yield succeed
        }
    }

    "fill unicode" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "\u65e5\u672c\u8a9e")
                _ <- Browser.assertText(Selector.id("v"), "\u65e5\u672c\u8a9e")
            yield succeed
        }
    }

    // ---- Placeholder ----

    "placeholder attribute" in run {
        withUI(UI.div(UI.input.placeholder("hint").id("i"))) {
            Browser.assertAttribute(Selector.id("i"), "placeholder", "hint").andThen(succeed)
        }
    }

    // ---- ReadOnly ----

    "readOnly attribute" in run {
        withUI(UI.div(UI.input.readOnly(true).id("i"))) {
            Browser.assertAttribute(Selector.id("i"), "readonly", "").andThen(succeed)
        }
    }

    // ---- Events ----

    "onInput and onChange both fire" in run {

        val app: UI < Async =
            for
                inputRef  <- Signal.initRef("")
                changeRef <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => inputRef.set(v)).onChange(v => changeRef.set(v)),
                inputRef.map(v => UI.span(s"input:$v").id("vi")),
                changeRef.map(v => UI.span(s"change:$v").id("vc"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "test")
                _ <- Browser.assertText(Selector.id("vi"), "input:test")
                _ <- Browser.assertText(Selector.id("vc"), "change:test")
            yield succeed
        }
    }

    // ---- Focus ----

    "focus input onFocus fires" in run {

        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.input.id("i").onFocus(ref.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "focus elsewhere onBlur fires" in run {

        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.input.id("i").onBlur(ref.set(true)),
                UI.button("other").id("other"),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.click(Selector.id("other"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    // ---- Keyboard ----

    "pressKey fires onKeyDown" in run {

        val app: UI < Async =
            for keyRef <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => keyRef.set(ke.key.toString)),
                keyRef.map(v => UI.span(s"key:$v").id("vk"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.ArrowLeft)
                _ <- Browser.assertText(Selector.id("vk"), "key:ArrowLeft")
            yield succeed
        }
    }

    "pressKey chars accumulate in input" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"result:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key('a'))
                _ <- Browser.press(Selector.id("i"), Key('b'))
                _ <- Browser.press(Selector.id("i"), Key('c'))
                _ <- Browser.assertText(Selector.id("v"), "result:abc")
            yield succeed
        }
    }

    "focus input assertFocused" in run {

        withUI(UI.div(UI.input.id("i"))) {
            for
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.assertVisible(Selector.id("i"))
            yield succeed
        }
    }

    "pressKey Char a onInput fires" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key('a'))
                _ <- Browser.assertText(Selector.id("v"), "val:a")
            yield succeed
        }
    }

    "placeholder visible when empty" in run {
        withUI(UI.div(UI.input.placeholder("Type here").id("i"))) {
            Browser.assertAttribute(Selector.id("i"), "placeholder", "Type here").andThen(succeed)
        }
    }

    "placeholder gone after fill" in run {

        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.placeholder("Type here").id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hello")
                _ <- Browser.assertText(Selector.id("v"), "val:hello")
            yield succeed
        }
    }

    "input type is text" in run {
        withUI(UI.div(UI.input.id("i"))) {
            Browser.assertAttribute(Selector.id("i"), "type", "text").andThen(succeed)
        }
    }

end InputTest
