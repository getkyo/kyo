package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class TextInputTest extends UITest:

    "password type" in run {
        withUI(UI.div(UI.passwordInput.id("p"))) {
            Browser.assertAttribute(Selector.id("p"), "type", "password").andThen(succeed)
        }
    }

    "password onInput" in run {
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
            yield succeed
        }
    }

    "password placeholder" in run {
        withUI(UI.div(UI.passwordInput.placeholder("Enter password").id("p"))) {
            Browser.assertAttribute(Selector.id("p"), "placeholder", "Enter password").andThen(succeed)
        }
    }

    "password disabled" in run {
        withUI(UI.div(UI.passwordInput.disabled(true).id("p"))) {
            Browser.assertDisabled(Selector.id("p")).andThen(succeed)
        }
    }

    "password value signalRef" in run {
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
            yield succeed
        }
    }

    "email type" in run {
        withUI(UI.div(UI.emailInput.id("e"))) {
            Browser.assertAttribute(Selector.id("e"), "type", "email").andThen(succeed)
        }
    }

    "email onInput" in run {
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
            yield succeed
        }
    }

    "email placeholder" in run {
        withUI(UI.div(UI.emailInput.placeholder("your@email.com").id("e"))) {
            Browser.assertAttribute(Selector.id("e"), "placeholder", "your@email.com").andThen(succeed)
        }
    }

    "email disabled" in run {
        withUI(UI.div(UI.emailInput.disabled(true).id("e"))) {
            Browser.assertDisabled(Selector.id("e")).andThen(succeed)
        }
    }

    "tel type" in run {
        withUI(UI.div(UI.telInput.id("t"))) {
            Browser.assertAttribute(Selector.id("t"), "type", "tel").andThen(succeed)
        }
    }

    "tel onInput" in run {
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
            yield succeed
        }
    }

    "tel disabled" in run {
        withUI(UI.div(UI.telInput.disabled(true).id("t"))) {
            Browser.assertDisabled(Selector.id("t")).andThen(succeed)
        }
    }

    "url type" in run {
        withUI(UI.div(UI.urlInput.id("u"))) {
            Browser.assertAttribute(Selector.id("u"), "type", "url").andThen(succeed)
        }
    }

    "url onInput" in run {
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
            yield succeed
        }
    }

    "url disabled" in run {
        withUI(UI.div(UI.urlInput.disabled(true).id("u"))) {
            Browser.assertDisabled(Selector.id("u")).andThen(succeed)
        }
    }

    "search type" in run {
        withUI(UI.div(UI.searchInput.id("s"))) {
            Browser.assertAttribute(Selector.id("s"), "type", "search").andThen(succeed)
        }
    }

    "search onInput" in run {
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
            yield succeed
        }
    }

    "search placeholder" in run {
        withUI(UI.div(UI.searchInput.placeholder("Search...").id("s"))) {
            Browser.assertAttribute(Selector.id("s"), "placeholder", "Search...").andThen(succeed)
        }
    }

    "search disabled" in run {
        withUI(UI.div(UI.searchInput.disabled(true).id("s"))) {
            Browser.assertDisabled(Selector.id("s")).andThen(succeed)
        }
    }

    "two different types independent" in run {
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
            yield succeed
        }
    }

    "all variants readOnly" in run {
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
            yield succeed
        }
    }

    "all variants focus" in run {
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
            yield succeed
        }
    }

    "textInputAttrs delegation" - {
        "input value delegates through textInputAttrs" in {
            import kyo.UI.Bound
            UI.input.value("x").value match
                case Present(Bound.Const("x")) => succeed
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
                case Present(Bound.Const("5")) => succeed
                case other                     => fail(s"expected Present(Bound.Const(5)) but got $other")
        }

        "textarea placeholder delegates through textInputAttrs" in {
            assert(UI.textarea.placeholder("hint").placeholder == Present("hint"))
        }
    }

end TextInputTest
