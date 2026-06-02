package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class SettingsScenarioItTest extends UITest:

    "check notifications shows email and frequency" in run {
        val app: UI < Async =
            for
                notify <- Signal.initRef(false)
                email  <- Signal.initRef("")
                freq   <- Signal.initRef("daily")
            yield UI.div(
                UI.checkbox.id("notify").checked(notify),
                UI.when(notify)(
                    UI.div(
                        UI.input.id("email").value(email),
                        UI.select(UI.option("Daily").value("daily"), UI.option("Weekly").value("weekly"))
                            .id("freq").value(freq)
                    )
                )
            )
        withUI(app) {
            for
                _ <- Browser.assertNotExists(Selector.id("email"))
                _ <- Browser.click(Selector.id("notify"))
                _ <- Browser.assertVisible(Selector.id("email"))
                _ <- Browser.assertVisible(Selector.id("freq"))
            yield succeed
        }
    }

    "fill email select frequency save captures all" in run {
        val app: UI < Async =
            for
                notify <- Signal.initRef(false)
                email  <- Signal.initRef("")
                freq   <- Signal.initRef("daily")
                result <- Signal.initRef("")
            yield UI.div(
                UI.checkbox.id("notify").checked(notify),
                UI.when(notify)(
                    UI.div(
                        UI.input.id("email").value(email),
                        UI.select(UI.option("Daily").value("daily"), UI.option("Weekly").value("weekly"))
                            .id("freq").value(freq).onChange(v => freq.set(v))
                    )
                ),
                UI.button("Save").id("save").onClick {
                    for
                        n <- notify.get
                        e <- email.get
                        f <- freq.get
                        _ <- result.set(if n then s"on|$e|$f" else "off")
                    yield ()
                },
                result.map(v => UI.span(s"result:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("notify"))
                _ <- Browser.fill(Selector.id("email"), "me@co.com")
                _ <- Browser.select(Selector.id("freq"), "weekly")
                _ <- Browser.click(Selector.id("save"))
                _ <- Browser.assertText(Selector.id("v"), "result:on|me@co.com|weekly")
            yield succeed
        }
    }

    "uncheck notifications fields disappear" in run {
        val app: UI < Async =
            for notify <- Signal.initRef(true)
            yield UI.div(
                UI.checkbox.id("notify").checked(notify),
                UI.when(notify)(UI.input.id("email"))
            )
        withUI(app) {
            for
                _ <- Browser.assertVisible(Selector.id("email"))
                _ <- Browser.click(Selector.id("notify"))
                _ <- Browser.assertNotExists(Selector.id("email"))
            yield succeed
        }
    }

    "recheck notifications previous values restored" in run {
        val app: UI < Async =
            for
                notify <- Signal.initRef(false)
                email  <- Signal.initRef("")
            yield UI.div(
                UI.checkbox.id("notify").checked(notify),
                UI.when(notify)(UI.input.id("email").value(email)),
                email.map(v => UI.span(s"email:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("notify"))
                _ <- Browser.fill(Selector.id("email"), "me@co.com")
                _ <- Browser.assertText(Selector.id("v"), "email:me@co.com")
                _ <- Browser.click(Selector.id("notify"))
                _ <- Browser.click(Selector.id("notify"))
                _ <- Browser.assertText(Selector.id("v"), "email:me@co.com")
            yield succeed
        }
    }

    "dropdown changes visible section" in run {
        val app: UI < Async =
            for mode <- Signal.initRef("light")
            yield UI.div(
                UI.select(
                    UI.option("Light").value("light"),
                    UI.option("Dark").value("dark")
                ).id("theme").value(mode),
                mode.map {
                    case "light" => UI.span("Light mode").id("info")
                    case _       => UI.span("Dark mode").id("info")
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("info"), "Light mode")
                _ <- Browser.select(Selector.id("theme"), "dark")
                _ <- Browser.assertText(Selector.id("info"), "Dark mode")
            yield succeed
        }
    }

    "reset to defaults clears all" in run {
        val app: UI < Async =
            for
                name  <- Signal.initRef("")
                email <- Signal.initRef("")
            yield UI.div(
                UI.input.id("name").value(name),
                UI.input.id("email").value(email),
                UI.button("Reset").id("reset").onClick(name.set("").andThen(email.set(""))),
                name.map(v => UI.span(s"name:[$v]").id("vn")),
                email.map(v => UI.span(s"email:[$v]").id("ve"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.fill(Selector.id("email"), "a@b.com")
                _ <- Browser.click(Selector.id("reset"))
                _ <- Browser.assertText(Selector.id("vn"), "name:[]")
                _ <- Browser.assertText(Selector.id("ve"), "email:[]")
            yield succeed
        }
    }

    "check digest shows more fields" in run {
        val app: UI < Async =
            for
                notify <- Signal.initRef(false)
                digest <- Signal.initRef(false)
            yield UI.div(
                UI.checkbox.id("notify").checked(notify),
                UI.when(notify)(
                    UI.div(
                        UI.input.id("email"),
                        UI.checkbox.id("digest").checked(digest),
                        UI.when(digest)(UI.input.id("digest-email"))
                    )
                )
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("notify"))
                _ <- Browser.assertVisible(Selector.id("email"))
                _ <- Browser.click(Selector.id("digest"))
                _ <- Browser.assertVisible(Selector.id("digest-email"))
            yield succeed
        }
    }

    "save captures all visible and hidden signal values" in run {
        val app: UI < Async =
            for
                name   <- Signal.initRef("")
                hidden <- Signal.initRef("secret")
                result <- Signal.initRef("")
            yield UI.div(
                UI.input.id("name").value(name),
                UI.button("Save").id("save").onClick {
                    for
                        n <- name.get
                        h <- hidden.get
                        _ <- result.set(s"$n|$h")
                    yield ()
                },
                result.map(v => UI.span(s"result:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.click(Selector.id("save"))
                _ <- Browser.assertText(Selector.id("v"), "result:Alice|secret")
            yield succeed
        }
    }

end SettingsScenarioItTest
