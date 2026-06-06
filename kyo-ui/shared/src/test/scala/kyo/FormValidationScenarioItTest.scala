package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class FormValidationScenarioItTest extends UITest:

    "submit empty shows first validation error" in {
        val app: UI < Async =
            for
                name  <- Signal.initRef("")
                email <- Signal.initRef("")
                error <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        n <- name.get
                        e <- email.get
                        _ <-
                            if n.isEmpty then error.set("Name required")
                            else if e.isEmpty then error.set("Email required")
                            else error.set("")
                    yield ()
                }(
                    UI.input.id("name").value(name),
                    UI.input.id("email").value(email),
                    UI.button("Submit").id("sub")
                ),
                error.map(v => UI.span(v).id("err"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("err"), "Name required")
            yield ()
        }
    }

    "fill name submit shows email error name preserved" in {
        val app: UI < Async =
            for
                name  <- Signal.initRef("")
                email <- Signal.initRef("")
                error <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        n <- name.get
                        e <- email.get
                        _ <-
                            if n.isEmpty then error.set("Name required")
                            else if e.isEmpty then error.set("Email required")
                            else error.set("")
                    yield ()
                }(
                    UI.input.id("name").value(name),
                    UI.input.id("email").value(email),
                    UI.button("Submit").id("sub")
                ),
                error.map(v => UI.span(v).id("err")),
                name.map(v => UI.span(s"name:$v").id("vn"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("err"), "Email required")
                _ <- Browser.assertText(Selector.id("vn"), "name:Alice")
            yield ()
        }
    }

    "fill all submit success" in {
        val app: UI < Async =
            for
                name   <- Signal.initRef("")
                email  <- Signal.initRef("")
                result <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        n <- name.get
                        e <- email.get
                        _ <-
                            if n.nonEmpty && e.nonEmpty then result.set(s"ok:$n,$e")
                            else result.set("error")
                    yield ()
                }(
                    UI.input.id("name").value(name),
                    UI.input.id("email").value(email),
                    UI.button("Submit").id("sub")
                ),
                result.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.fill(Selector.id("email"), "a@b.com")
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "ok:Alice,a@b.com")
            yield ()
        }
    }

    "check advanced shows extra fields fill submit captures all" in {
        val app: UI < Async =
            for
                name     <- Signal.initRef("")
                phone    <- Signal.initRef("")
                advanced <- Signal.initRef(false)
                result   <- Signal.initRef("")
            yield UI.div(
                UI.input.id("name").value(name),
                UI.checkbox.id("adv").checked(advanced),
                UI.when(advanced)(UI.input.id("phone").value(phone)),
                UI.button("Submit").id("sub").onClick {
                    for
                        n <- name.get
                        a <- advanced.get
                        p <- phone.get
                        _ <- result.set(if a then s"$n|$p" else s"$n|none")
                    yield ()
                },
                result.map(v => UI.span(s"result:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.click(Selector.id("adv"))
                _ <- Browser.fill(Selector.id("phone"), "555-1234")
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "result:Alice|555-1234")
            yield ()
        }
    }

    "uncheck advanced submit only basic fields" in {
        val app: UI < Async =
            for
                name     <- Signal.initRef("")
                phone    <- Signal.initRef("")
                advanced <- Signal.initRef(true)
                result   <- Signal.initRef("")
            yield UI.div(
                UI.input.id("name").value(name),
                UI.checkbox.id("adv").checked(advanced),
                UI.when(advanced)(UI.input.id("phone").value(phone)),
                UI.button("Submit").id("sub").onClick {
                    for
                        n <- name.get
                        a <- advanced.get
                        p <- phone.get
                        _ <- result.set(if a then s"$n|$p" else s"$n|none")
                    yield ()
                },
                result.map(v => UI.span(s"result:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Bob")
                _ <- Browser.fill(Selector.id("phone"), "555-9999")
                _ <- Browser.click(Selector.id("adv"))
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "result:Bob|none")
            yield ()
        }
    }

    "error disappears when field corrected" in {
        val app: UI < Async =
            for
                name  <- Signal.initRef("")
                error <- Signal.initRef("")
            yield UI.div(
                UI.input.id("name").value(name).onInput { v =>
                    if v.nonEmpty then error.set("") else Kyo.lift(())
                },
                UI.button("Submit").id("sub").onClick {
                    for
                        n <- name.get
                        _ <- if n.isEmpty then error.set("Required") else error.set("")
                    yield ()
                },
                error.map(v => UI.span(v).id("err"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("err"), "Required")
                _ <- Browser.fill(Selector.id("name"), "x")
                _ <- Browser.assertText(Selector.id("err"), "")
            yield ()
        }
    }

    "disabled submit until required filled" in {
        val app: UI < Async =
            for
                name    <- Signal.initRef("")
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("name").value(name),
                name.map { n =>
                    UI.button("Submit").id("sub").disabled(n.isEmpty).onClick(counter.getAndUpdate(_ + 1).unit)
                },
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("sub"))
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.assertEnabled(Selector.id("sub"))
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "submit error fix resubmit success" in {
        val app: UI < Async =
            for
                name    <- Signal.initRef("")
                error   <- Signal.initRef("")
                success <- Signal.initRef(false)
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        n <- name.get
                        _ <-
                            if n.isEmpty then error.set("Name required")
                            else error.set("").andThen(success.set(true))
                    yield ()
                }(
                    UI.input.id("name").value(name),
                    UI.button("Submit").id("sub")
                ),
                error.map(v => UI.span(s"err:$v").id("err")),
                success.map(v => UI.span(s"ok:$v").id("ok"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("err"), "err:Name required")
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("ok"), "ok:true")
            yield ()
        }
    }

    "fill name submit email error name preserved fill email success" in {
        val app: UI < Async =
            for
                name   <- Signal.initRef("")
                email  <- Signal.initRef("")
                error  <- Signal.initRef("")
                result <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        n <- name.get
                        e <- email.get
                        _ <-
                            if n.isEmpty then error.set("Name required")
                            else if e.isEmpty then error.set("Email required")
                            else error.set("").andThen(result.set(s"$n|$e"))
                    yield ()
                }(
                    UI.input.id("name").value(name),
                    UI.input.id("email").value(email),
                    UI.button("Submit").id("sub")
                ),
                error.map(v => UI.span(s"err:$v").id("err")),
                result.map(v => UI.span(s"ok:$v").id("ok")),
                name.map(v => UI.span(s"name:$v").id("vn"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("err"), "err:Email required")
                _ <- Browser.assertText(Selector.id("vn"), "name:Alice")
                _ <- Browser.fill(Selector.id("email"), "a@b.com")
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("ok"), "ok:Alice|a@b.com")
            yield ()
        }
    }

end FormValidationScenarioItTest
