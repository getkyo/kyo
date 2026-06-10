package kyo

import kyo.Browser.*
import kyo.UI.foreach
import scala.language.implicitConversions

class AppIntegrationItTest extends UITest:

    "employee form fill all fields and submit" in {
        val app: UI < Async =
            for
                name    <- Signal.initRef("")
                email   <- Signal.initRef("")
                role    <- Signal.initRef("dev")
                active  <- Signal.initRef(true)
                entries <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        n <- name.get
                        e <- email.get
                        r <- role.get
                        a <- active.get
                        _ <- entries.getAndUpdate(_.appended(s"$n|$e|$r|$a"))
                        _ <- name.set("")
                        _ <- email.set("")
                    yield ()
                }(
                    UI.input.id("name").value(name).onInput(v => name.set(v)),
                    UI.input.id("email").value(email).onInput(v => email.set(v)),
                    UI.select(
                        UI.option("Dev").value("dev"),
                        UI.option("QA").value("qa")
                    ).id("role").value(role).onChange(v => role.set(v)),
                    UI.checkbox.id("active").checked(true).onChange(v => active.set(v)),
                    UI.button("Add").id("add")
                ),
                entries.map { items =>
                    if items.isEmpty then UI.span("none").id("out")
                    else UI.span(items.toSeq.mkString("; ")).id("out")
                }
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.fill(Selector.id("email"), "alice@co.com")
                _ <- Browser.select(Selector.id("role"), "qa")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("out"), "Alice|alice@co.com|qa|true")
            yield ()
        }
    }

    "employee form add two entries" in {
        val app: UI < Async =
            for
                name  <- Signal.initRef("")
                count <- Signal.initRef(0)
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        _ <- count.getAndUpdate(_ + 1)
                        _ <- name.set("")
                    yield ()
                }(
                    UI.input.id("name").value(name).onInput(v => name.set(v)),
                    UI.button("Add").id("add")
                ),
                count.map(n => UI.span(s"count:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("v"), "count:1")
                _ <- Browser.fill(Selector.id("name"), "Bob")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("v"), "count:2")
            yield ()
        }
    }

    "todo list add items via form" in {
        val app: UI < Async =
            for
                input <- Signal.initRef("")
                todos <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        v <- input.get
                        _ <- if v.nonEmpty then todos.getAndUpdate(_.appended(v)).andThen(input.set(""))
                        else Kyo.lift(())
                    yield ()
                }(
                    UI.input.id("inp").value(input).onInput(v => input.set(v)),
                    UI.button("Add").id("add")
                ),
                todos.map { items =>
                    UI.div(items.toSeq.zipWithIndex.map { case (t, i) =>
                        UI.span(t).id(s"t$i")
                    }*)
                }
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "Buy milk")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("t0"), "Buy milk")
                _ <- Browser.fill(Selector.id("inp"), "Walk dog")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("t1"), "Walk dog")
            yield ()
        }
    }

    "todo list add via Enter key" in {
        val app: UI < Async =
            for
                input <- Signal.initRef("")
                todos <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        v <- input.get
                        _ <- if v.nonEmpty then todos.getAndUpdate(_.appended(v)).andThen(input.set(""))
                        else Kyo.lift(())
                    yield ()
                }(
                    UI.input.id("inp").value(input).onInput(v => input.set(v)),
                    UI.button("Add").id("add")
                ),
                todos.map(items => UI.span(s"count:${items.size}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "Task1")
                _ <- Browser.press(Selector.id("inp"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "count:1")
            yield ()
        }
    }

    "todo list check off items" in {
        val app: UI < Async =
            for done <- Signal.initRef(Chunk.empty[Int])
            yield UI.div(
                UI.checkbox.id("c0").onChange(v => if v then done.getAndUpdate(_.appended(0)).unit else Kyo.lift(())),
                UI.checkbox.id("c1").onChange(v => if v then done.getAndUpdate(_.appended(1)).unit else Kyo.lift(())),
                UI.checkbox.id("c2").onChange(v => if v then done.getAndUpdate(_.appended(2)).unit else Kyo.lift(())),
                done.map(d => UI.span(s"done:${d.size}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c0"))
                _ <- Browser.assertText(Selector.id("v"), "done:1")
                _ <- Browser.click(Selector.id("c2"))
                _ <- Browser.assertText(Selector.id("v"), "done:2")
            yield ()
        }
    }

    "login form validation shows error then submits" in {
        val app: UI < Async =
            for
                email  <- Signal.initRef("")
                pass   <- Signal.initRef("")
                error  <- Signal.initRef("")
                result <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        e <- email.get
                        p <- pass.get
                        _ <-
                            if e.isEmpty then error.set("Email required")
                            else if p.isEmpty then error.set("Password required")
                            else error.set("").andThen(result.set(s"login:$e"))
                    yield ()
                }(
                    UI.input.id("email").value(email).onInput(v => email.set(v)),
                    UI.passwordInput.id("pass").value(pass).onInput(v => pass.set(v)),
                    UI.button("Login").id("login")
                ),
                error.map(v => UI.span(v).id("err")),
                result.map(v => UI.span(v).id("out"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("login"))
                _ <- Browser.assertText(Selector.id("err"), "Email required")
                _ <- Browser.fill(Selector.id("email"), "a@b.com")
                _ <- Browser.click(Selector.id("login"))
                _ <- Browser.assertText(Selector.id("err"), "Password required")
                _ <- Browser.fill(Selector.id("pass"), "secret")
                _ <- Browser.click(Selector.id("login"))
                _ <- Browser.assertText(Selector.id("out"), "login:a@b.com")
            yield ()
        }
    }

    "settings toggle enables dependent field" in {
        val app: UI < Async =
            for
                notify   <- Signal.initRef(false)
                emailRef <- Signal.initRef("")
                saved    <- Signal.initRef("")
            yield UI.div(
                UI.checkbox.id("notify").onChange(v => notify.set(v)),
                notify.map { n =>
                    if n then UI.input.id("email").onInput(v => emailRef.set(v))
                    else UI.span("Notifications off").id("off")
                },
                UI.button("Save").id("save").onClick {
                    for
                        n <- notify.get
                        e <- emailRef.get
                        _ <- saved.set(if n then s"notify:$e" else "off")
                    yield ()
                },
                saved.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("notify"))
                _ <- Browser.fill(Selector.id("email"), "me@x.com")
                _ <- Browser.click(Selector.id("save"))
                _ <- Browser.assertText(Selector.id("v"), "notify:me@x.com")
            yield ()
        }
    }

    "settings dropdown changes visible section" in {
        val app: UI < Async =
            for mode <- Signal.initRef("light")
            yield UI.div(
                UI.select(
                    UI.option("Light").value("light"),
                    UI.option("Dark").value("dark"),
                    UI.option("System").value("system")
                ).id("theme").value(mode).onChange(v => mode.set(v)),
                mode.map {
                    case "light"  => UI.span("Light mode active").id("info")
                    case "dark"   => UI.span("Dark mode active").id("info")
                    case "system" => UI.span("Following system").id("info")
                    case _        => UI.span("Unknown").id("info")
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("info"), "Light mode active")
                _ <- Browser.select(Selector.id("theme"), "dark")
                _ <- Browser.assertText(Selector.id("info"), "Dark mode active")
                _ <- Browser.select(Selector.id("theme"), "system")
                _ <- Browser.assertText(Selector.id("info"), "Following system")
            yield ()
        }
    }

    "counter increment decrement" in {
        val app: UI < Async =
            for count <- Signal.initRef(0)
            yield UI.div(
                UI.button("-").id("dec").onClick(count.getAndUpdate(_ - 1).unit),
                count.map(n => UI.span(n.toString).id("v")),
                UI.button("+").id("inc").onClick(count.getAndUpdate(_ + 1).unit)
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("v"), "3")
                _ <- Browser.click(Selector.id("dec"))
                _ <- Browser.assertText(Selector.id("v"), "2")
            yield ()
        }
    }

    "wizard next back with data preservation" in {
        val app: UI < Async =
            for
                step   <- Signal.initRef(1)
                name   <- Signal.initRef("")
                role   <- Signal.initRef("dev")
                result <- Signal.initRef("")
            yield UI.div(
                step.map {
                    case 1 => UI.div(
                            UI.input.id("name").value(name).onInput(v => name.set(v)),
                            UI.button("Next").id("next").onClick(step.set(2))
                        )
                    case 2 => UI.div(
                            UI.select(
                                UI.option("Dev").value("dev"),
                                UI.option("QA").value("qa")
                            ).id("role").value(role).onChange(v => role.set(v)),
                            UI.button("Back").id("back").onClick(step.set(1)),
                            UI.button("Done").id("done").onClick {
                                for
                                    n <- name.get
                                    r <- role.get
                                    _ <- result.set(s"Result:$n/$r")
                                    _ <- step.set(3)
                                yield ()
                            }
                        )
                    case _ => UI.span("done")
                },
                result.map(v => UI.span(v).id("result"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.click(Selector.id("next"))
                _ <- Browser.select(Selector.id("role"), "qa")
                _ <- Browser.click(Selector.id("back"))
                _ <- Browser.assertAttribute(Selector.id("name"), "value", "Alice")
                _ <- Browser.click(Selector.id("next"))
                _ <- Browser.assertAttribute(Selector.id("role"), "value", "qa")
                _ <- Browser.click(Selector.id("done"))
                _ <- Browser.assertText(Selector.id("result"), "Result:Alice/qa")
            yield ()
        }
    }

    "employee form submit clears fields" in {
        val app: UI < Async =
            for
                name  <- Signal.initRef("")
                count <- Signal.initRef(0)
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        _ <- count.getAndUpdate(_ + 1)
                        _ <- name.set("")
                    yield ()
                }(
                    UI.input.id("name").value(name),
                    UI.button("Add").id("add")
                ),
                count.map(n => UI.span(s"count:$n").id("v")),
                name.map(v => UI.span(s"name:[$v]").id("nv"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("v"), "count:1")
                _ <- Browser.assertText(Selector.id("nv"), "name:[]")
            yield ()
        }
    }

    "todo list check off updates counter" in {
        val app: UI < Async =
            for
                done <- Signal.initRef(Chunk.empty[Int])
            yield UI.div(
                UI.checkbox.id("c0").onChange(v => if v then done.getAndUpdate(_.appended(0)).unit else Kyo.lift(())),
                UI.checkbox.id("c1").onChange(v => if v then done.getAndUpdate(_.appended(1)).unit else Kyo.lift(())),
                done.map(d => UI.span(s"complete:${d.size}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c0"))
                _ <- Browser.assertText(Selector.id("v"), "complete:1")
                _ <- Browser.click(Selector.id("c1"))
                _ <- Browser.assertText(Selector.id("v"), "complete:2")
            yield ()
        }
    }

    "form Enter on input inside nested div still submits" in {
        val app: UI < Async =
            for
                ref       <- Signal.initRef("")
                submitted <- Signal.initRef(false)
            yield UI.div(
                UI.form.id("f").onSubmit(submitted.set(true))(
                    UI.div(UI.input.id("i").value(ref).onInput(v => ref.set(v)))
                ),
                submitted.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "test")
                _ <- Browser.press(Selector.id("i"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "settings toggle disables field value not captured" in {
        val app: UI < Async =
            for
                notify <- Signal.initRef(true)
                email  <- Signal.initRef("preset")
                saved  <- Signal.initRef("")
            yield UI.div(
                UI.checkbox.id("notify").checked(notify).onChange(v => notify.set(v)),
                notify.map { n =>
                    if n then UI.input.id("email").value(email).onInput(v => email.set(v))
                    else UI.span("Off").id("off")
                },
                UI.button("Save").id("save").onClick {
                    for
                        n <- notify.get
                        e <- email.get
                        _ <- saved.set(if n then s"notify:$e" else "off")
                    yield ()
                },
                saved.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("notify"))
                _ <- Browser.click(Selector.id("save"))
                _ <- Browser.assertText(Selector.id("v"), "off")
            yield ()
        }
    }

    "wizard conditional step based on selection" in {
        val app: UI < Async =
            for
                step   <- Signal.initRef(1)
                kind   <- Signal.initRef("free")
                cardNo <- Signal.initRef("")
                result <- Signal.initRef("")
            yield UI.div(
                step.map {
                    case 1 => UI.div(
                            UI.select(
                                UI.option("Free").value("free"),
                                UI.option("Pro").value("pro")
                            ).id("kind").value(kind).onChange(v => kind.set(v)),
                            UI.button("Next").id("next").onClick(step.set(2))
                        )
                    case 2 => UI.div(
                            kind.map { k =>
                                if k == "pro" then UI.input.id("card").value(cardNo).onInput(v => cardNo.set(v))
                                else UI.span("Free plan").id("free")
                            },
                            UI.button("Done").id("done").onClick {
                                for
                                    k <- kind.get
                                    c <- cardNo.get
                                    _ <- result.set(if k == "pro" then s"pro|$c" else "free")
                                yield ()
                            }
                        )
                    case _ => UI.span("end")
                },
                result.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.select(Selector.id("kind"), "pro")
                _ <- Browser.click(Selector.id("next"))
                _ <- Browser.fill(Selector.id("card"), "1234")
                _ <- Browser.click(Selector.id("done"))
                _ <- Browser.assertText(Selector.id("v"), "pro|1234")
            yield ()
        }
    }

    "disabled submit until all required fields filled" in {
        val app: UI < Async =
            for
                a       <- Signal.initRef("")
                b       <- Signal.initRef("")
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("a").value(a).onInput(v => a.set(v)),
                UI.input.id("b").value(b).onInput(v => b.set(v)),
                a.switchMap(av =>
                    b.map(bv =>
                        UI.button("Submit").id("sub")
                            .disabled(av.isEmpty || bv.isEmpty)
                            .onClick(counter.getAndUpdate(_ + 1).unit)
                    )
                ),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("sub"))
                _ <- Browser.fill(Selector.id("a"), "x")
                _ <- Browser.assertDisabled(Selector.id("sub"))
                _ <- Browser.fill(Selector.id("b"), "y")
                _ <- Browser.assertEnabled(Selector.id("sub"))
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

end AppIntegrationItTest
