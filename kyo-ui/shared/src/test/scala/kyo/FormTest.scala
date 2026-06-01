package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class FormTest extends UITest:

    "form renders with children" in run {
        withUI(UI.div(UI.form(UI.input.id("i"), UI.button("Go").id("go")).id("f"))) {
            for
                _ <- Browser.assertVisible(Selector.id("f"))
                _ <- Browser.assertVisible(Selector.id("i"))
                _ <- Browser.assertText(Selector.id("go"), "Go")
            yield succeed
        }
    }

    "form onSubmit fires via button click" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.form.id("f").onSubmit(ref.set(true))(
                    UI.input.id("i"),
                    UI.button("Submit").id("sub")
                ),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "false")
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "form onSubmit counter" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.form.id("f").onSubmit(counter.getAndUpdate(_ + 1).unit)(
                    UI.button("Submit").id("sub")
                ),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "1")
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "2")
            yield succeed
        }
    }

    "onSubmit reads SignalRef values" in run {
        val app: UI < Async =
            for
                inputRef <- Signal.initRef("")
                result   <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        v <- inputRef.get
                        _ <- result.set(s"submitted:$v")
                    yield ()
                }(
                    UI.input.id("i").value(inputRef).onInput(v => inputRef.set(v)),
                    UI.button("Submit").id("sub")
                ),
                result.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hello")
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "submitted:hello")
            yield succeed
        }
    }

    "pressKey Enter on input triggers submit" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.form.id("f").onSubmit(counter.getAndUpdate(_ + 1).unit)(
                    UI.input.id("i"),
                    UI.button("Submit").id("sub")
                ),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "disabled submit button not clickable" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.form.id("f").onSubmit(counter.getAndUpdate(_ + 1).unit)(
                    UI.button("Submit").id("sub").disabled(true)
                ),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield succeed
        }
    }

    "text checkbox select form fill all submit" in run {
        val app: UI < Async =
            for
                textRef  <- Signal.initRef("")
                checkRef <- Signal.initRef(false)
                selRef   <- Signal.initRef("")
                result   <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        t <- textRef.get
                        c <- checkRef.get
                        s <- selRef.get
                        _ <- result.set(s"$t|$c|$s")
                    yield ()
                }(
                    UI.input.id("i").value(textRef).onInput(v => textRef.set(v)),
                    UI.checkbox.id("c").onChange(v => checkRef.set(v)),
                    UI.select(UI.option("A").value("a"), UI.option("B").value("b")).id("s")
                        .value(selRef).onChange(v => selRef.set(v)),
                    UI.button("Go").id("sub")
                ),
                result.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hello")
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "hello|true|")
            yield succeed
        }
    }

    "form inside div still works" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.div(
                    UI.form.id("f").onSubmit(counter.getAndUpdate(_ + 1).unit)(
                        UI.button("Submit").id("sub")
                    )
                ),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "multiple forms submit one other unaffected" in run {
        val app: UI < Async =
            for
                a <- Signal.initRef(0)
                b <- Signal.initRef(0)
            yield UI.div(
                UI.form.id("f1").onSubmit(a.getAndUpdate(_ + 1).unit)(
                    UI.button("Sub1").id("sub1")
                ),
                UI.form.id("f2").onSubmit(b.getAndUpdate(_ + 1).unit)(
                    UI.button("Sub2").id("sub2")
                ),
                a.map(n => UI.span(s"a:$n").id("va")),
                b.map(n => UI.span(s"b:$n").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("sub1"))
                _ <- Browser.assertText(Selector.id("va"), "a:1")
                _ <- Browser.assertText(Selector.id("vb"), "b:0")
            yield succeed
        }
    }

    "form with conditional fields" in run {
        val app: UI < Async =
            for
                showExtra <- Signal.initRef(false)
                result    <- Signal.initRef("")
                extraRef  <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        e <- extraRef.get
                        _ <- result.set(s"extra:$e")
                    yield ()
                }(
                    UI.button("Toggle").id("tog").onClick(showExtra.getAndUpdate(!_).unit),
                    UI.when(showExtra)(UI.input.id("extra").value(extraRef).onInput(v => extraRef.set(v))),
                    UI.button("Submit").id("sub")
                ),
                result.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertVisible(Selector.id("extra"))
                _ <- Browser.fill(Selector.id("extra"), "data")
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "extra:data")
            yield succeed
        }
    }

    "form with nested div containing inputs" in run {
        val app: UI < Async =
            for
                ref    <- Signal.initRef("")
                result <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        v <- ref.get
                        _ <- result.set(s"got:$v")
                    yield ()
                }(
                    UI.div(UI.input.id("i").value(ref).onInput(v => ref.set(v))),
                    UI.button("Submit").id("sub")
                ),
                result.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "nested")
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "got:nested")
            yield succeed
        }
    }

    "signal driven error message" in run {
        val app: UI < Async =
            for
                error <- Signal.initRef("")
                ref   <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        v <- ref.get
                        _ <- if v.isEmpty then error.set("Required") else error.set("")
                    yield ()
                }(
                    UI.input.id("i").value(ref).onInput(v => ref.set(v)),
                    UI.button("Submit").id("sub")
                ),
                error.map(v => UI.span(v).id("err"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("err"), "Required")
                _ <- Browser.fill(Selector.id("i"), "data")
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("err"), "")
            yield succeed
        }
    }

    "signal driven submit enable" in run {
        val app: UI < Async =
            for
                ref <- Signal.initRef("")
                valid = ref.map(_.nonEmpty)
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("i").value(ref).onInput(v => ref.set(v)),
                valid.map { v =>
                    UI.form.id("f").onSubmit(counter.getAndUpdate(_ + 1).unit)(
                        UI.button("Submit").id("sub").disabled(!v)
                    )
                },
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("sub"))
                _ <- Browser.fill(Selector.id("i"), "data")
                _ <- Browser.assertEnabled(Selector.id("sub"))
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "focus submit button" in run {
        withUI(UI.div(UI.form(UI.button("Submit").id("sub")).id("f"))) {
            for
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertVisible(Selector.id("sub"))
            yield succeed
        }
    }

    "form with no onSubmit no crash" in run {
        withUI(UI.div(UI.form(UI.button("Go").id("sub")).id("f"))) {
            for
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertVisible(Selector.id("sub"))
            yield succeed
        }
    }

end FormTest
