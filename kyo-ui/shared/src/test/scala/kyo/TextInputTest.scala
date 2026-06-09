package kyo

import kyo.Browser.*

class TextInputTest extends UITest:

    "password type" in {
        withUI(UI.div(UI.passwordInput.id("p"))) {
            Browser.assertAttribute(Selector.id("p"), "type", "password").unit
        }
    }

    "password onInput" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.passwordInput.id("p").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("p"), "secret")
                _ <- Browser.assertText(Selector.id("v"), "secret")
            yield ()
        }
    }

    "password placeholder" in {
        withUI(UI.div(UI.passwordInput.placeholder("Enter password").id("p"))) {
            Browser.assertAttribute(Selector.id("p"), "placeholder", "Enter password").unit
        }
    }

    "password disabled" in {
        withUI(UI.div(UI.passwordInput.disabled(true).id("p"))) {
            Browser.assertDisabled(Selector.id("p")).unit
        }
    }

    "password value signalRef" in {
        val app: UI < Async =
            for ref <- Signal.initRef("init")
            yield UI.div(
                UI.passwordInput.value(ref).id("p").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "init")
                _ <- Browser.fill(Selector.id("p"), "new")
                _ <- Browser.assertText(Selector.id("v"), "new")
            yield ()
        }
    }

    "email type" in {
        withUI(UI.div(UI.emailInput.id("e"))) {
            Browser.assertAttribute(Selector.id("e"), "type", "email").unit
        }
    }

    "email onInput" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.emailInput.id("e").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("e"), "test@example.com")
                _ <- Browser.assertText(Selector.id("v"), "test@example.com")
            yield ()
        }
    }

    "email placeholder" in {
        withUI(UI.div(UI.emailInput.placeholder("your@email.com").id("e"))) {
            Browser.assertAttribute(Selector.id("e"), "placeholder", "your@email.com").unit
        }
    }

    "email disabled" in {
        withUI(UI.div(UI.emailInput.disabled(true).id("e"))) {
            Browser.assertDisabled(Selector.id("e")).unit
        }
    }

    "tel type" in {
        withUI(UI.div(UI.telInput.id("t"))) {
            Browser.assertAttribute(Selector.id("t"), "type", "tel").unit
        }
    }

    "tel onInput" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.telInput.id("t").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "+1234567890")
                _ <- Browser.assertText(Selector.id("v"), "+1234567890")
            yield ()
        }
    }

    "tel disabled" in {
        withUI(UI.div(UI.telInput.disabled(true).id("t"))) {
            Browser.assertDisabled(Selector.id("t")).unit
        }
    }

    "url type" in {
        withUI(UI.div(UI.urlInput.id("u"))) {
            Browser.assertAttribute(Selector.id("u"), "type", "url").unit
        }
    }

    "url onInput" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.urlInput.id("u").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("u"), "https://kyo.dev")
                _ <- Browser.assertText(Selector.id("v"), "https://kyo.dev")
            yield ()
        }
    }

    "url disabled" in {
        withUI(UI.div(UI.urlInput.disabled(true).id("u"))) {
            Browser.assertDisabled(Selector.id("u")).unit
        }
    }

    "search type" in {
        withUI(UI.div(UI.searchInput.id("s"))) {
            Browser.assertAttribute(Selector.id("s"), "type", "search").unit
        }
    }

    "search onInput" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.searchInput.id("s").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("s"), "query")
                _ <- Browser.assertText(Selector.id("v"), "query")
            yield ()
        }
    }

    "search placeholder" in {
        withUI(UI.div(UI.searchInput.placeholder("Search...").id("s"))) {
            Browser.assertAttribute(Selector.id("s"), "placeholder", "Search...").unit
        }
    }

    "search disabled" in {
        withUI(UI.div(UI.searchInput.disabled(true).id("s"))) {
            Browser.assertDisabled(Selector.id("s")).unit
        }
    }

    "two different types independent" in {
        val app: UI < Async =
            for
                p <- Signal.initRef("")
                e <- Signal.initRef("")
            yield UI.div(
                UI.passwordInput.id("p").onInput(v => p.set(v)),
                UI.emailInput.id("e").onInput(v => e.set(v)),
                p.map(v => UI.span(s"p:$v").id("pv")),
                e.map(v => UI.span(s"e:$v").id("ev"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("p"), "pass")
                _ <- Browser.fill(Selector.id("e"), "mail")
                _ <- Browser.assertText(Selector.id("pv"), "p:pass")
                _ <- Browser.assertText(Selector.id("ev"), "e:mail")
            yield ()
        }
    }

    "all variants readOnly" in {
        withUI(UI.div(
            UI.passwordInput.readOnly(true).id("p"),
            UI.emailInput.readOnly(true).id("e"),
            UI.telInput.readOnly(true).id("t"),
            UI.urlInput.readOnly(true).id("u"),
            UI.searchInput.readOnly(true).id("s")
        )) {
            for
                _ <- Browser.assertAttribute(Selector.id("p"), "readonly", "")
                _ <- Browser.assertAttribute(Selector.id("e"), "readonly", "")
                _ <- Browser.assertAttribute(Selector.id("t"), "readonly", "")
                _ <- Browser.assertAttribute(Selector.id("u"), "readonly", "")
                _ <- Browser.assertAttribute(Selector.id("s"), "readonly", "")
            yield ()
        }
    }

    "all variants focus" in {
        withUI(UI.div(
            UI.passwordInput.id("p"),
            UI.emailInput.id("e"),
            UI.telInput.id("t"),
            UI.urlInput.id("u"),
            UI.searchInput.id("s")
        )) {
            for
                _ <- Browser.click(Selector.id("p"))
                _ <- Browser.click(Selector.id("e"))
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.click(Selector.id("u"))
                _ <- Browser.click(Selector.id("s"))
                _ <- Browser.assertVisible(Selector.id("s"))
            yield ()
        }
    }

    "textInputAttrs delegation" - {
        "input value delegates through textInputAttrs" in {
            import kyo.UI.Bound
            UI.input.value("x").value match
                case Present(Bound.Const("x")) => ()
                case other                     => fail(s"expected Present(Bound.Const(x)) but got $other")
        }

        "password placeholder delegates through textInputAttrs" in {
            assert(UI.passwordInput.placeholder("hint").placeholder == Present("hint"))
        }

        "email readOnly delegates through textInputAttrs" in {
            assert(UI.emailInput.readOnly(true).readOnly == Present(true))
        }

        "tel disabled delegates through textInputAttrs" in {
            assert(UI.telInput.disabled(true).disabled == Present(true))
        }

        "url onInput delegates through textInputAttrs" in {
            assert(UI.urlInput.onInput(_ => Kyo.lift(())).onInput.isDefined)
        }

        "search onChange delegates through textInputAttrs" in {
            assert(UI.searchInput.onChange(_ => Kyo.lift(())).onChange.isDefined)
        }

        "number retains extra fields and shared attrs" in {
            import kyo.UI.Bound
            UI.numberInput.min(0).max(100).value("5").value match
                case Present(Bound.Const("5")) => ()
                case other                     => fail(s"expected Present(Bound.Const(5)) but got $other")
        }

        "textarea placeholder delegates through textInputAttrs" in {
            assert(UI.textarea.placeholder("hint").placeholder == Present("hint"))
        }
    }

end TextInputTest
