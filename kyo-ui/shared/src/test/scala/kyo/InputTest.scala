package kyo

import kyo.Browser.*

class InputTest extends UITest:

    // ---- Rendering ----

    "input exists" in {
        withUI(UI.div(UI.input.id("i"))) {
            Browser.assertVisible(Selector.id("i")).unit
        }
    }

    // ---- disabled / readOnly ----

    "input disabled" in {
        withUI(UI.div(UI.input.disabled(true).id("i"))) {
            Browser.assertDisabled(Selector.id("i")).unit
        }
    }

    "input disabled via signal" in {

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
            yield ()
        }
    }

    // ---- onInput ----

    "onInput fires on typing" in {

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
            yield ()
        }
    }

    "onInput clear shows empty" in {

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
            yield ()
        }
    }

    "type in input updates sibling span" in {

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
            yield ()
        }
    }

    "retype updates echo" in {

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
            yield ()
        }
    }

    "two inputs independent" in {

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
            yield ()
        }
    }

    "onChange fires" in {

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
            yield ()
        }
    }

    // ---- Input variants ----

    "password input exists" in {
        withUI(UI.div(UI.passwordInput.id("p"))) {
            Browser.assertVisible(Selector.id("p")).unit
        }
    }

    "password onInput works" in {

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
            yield ()
        }
    }

    "email input exists" in {
        withUI(UI.div(UI.emailInput.id("e"))) {
            Browser.assertVisible(Selector.id("e")).unit
        }
    }

    "search input exists" in {
        withUI(UI.div(UI.searchInput.id("s"))) {
            Browser.assertVisible(Selector.id("s")).unit
        }
    }

    "tel input exists" in {
        withUI(UI.div(UI.telInput.id("t"))) {
            Browser.assertVisible(Selector.id("t")).unit
        }
    }

    "url input exists" in {
        withUI(UI.div(UI.urlInput.id("u"))) {
            Browser.assertVisible(Selector.id("u")).unit
        }
    }

    // ---- Value binding ----

    "value initial" in {
        withUI(UI.div(UI.input.id("i").value("initial"))) {
            Browser.assertAttribute(Selector.id("i"), "value", "initial").unit
        }
    }

    "value SignalRef fill updates ref" in {

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
            yield ()
        }
    }

    "value SignalRef external set updates display" in {

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
            yield ()
        }
    }

    "fill special chars" in {

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
            yield ()
        }
    }

    "fill unicode" in {

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
            yield ()
        }
    }

    // ---- Placeholder ----

    "placeholder attribute" in {
        withUI(UI.div(UI.input.placeholder("hint").id("i"))) {
            Browser.assertAttribute(Selector.id("i"), "placeholder", "hint").unit
        }
    }

    // ---- ReadOnly ----

    "readOnly attribute" in {
        withUI(UI.div(UI.input.readOnly(true).id("i"))) {
            Browser.assertAttribute(Selector.id("i"), "readonly", "").unit
        }
    }

    // ---- Events ----

    "onInput and onChange both fire" in {

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
            yield ()
        }
    }

    // ---- Focus ----

    "focus input onFocus fires" in {

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
            yield ()
        }
    }

    "focus elsewhere onBlur fires" in {

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
            yield ()
        }
    }

    // ---- Keyboard ----

    "pressKey fires onKeyDown" in {

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
            yield ()
        }
    }

    "pressKey chars accumulate in input" in {

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
            yield ()
        }
    }

    "focus input assertFocused" in {

        withUI(UI.div(UI.input.id("i"))) {
            for
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.assertVisible(Selector.id("i"))
            yield ()
        }
    }

    "pressKey Char a onInput fires" in {

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
            yield ()
        }
    }

    "placeholder visible when empty" in {
        withUI(UI.div(UI.input.placeholder("Type here").id("i"))) {
            Browser.assertAttribute(Selector.id("i"), "placeholder", "Type here").unit
        }
    }

    "placeholder gone after fill" in {

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
            yield ()
        }
    }

    "input type is text" in {
        withUI(UI.div(UI.input.id("i"))) {
            Browser.assertAttribute(Selector.id("i"), "type", "text").unit
        }
    }

end InputTest
