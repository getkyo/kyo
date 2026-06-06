package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class EventHandlerTest extends UITest:

    "onClick fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.button("+").id("b").onClick(ref.getAndUpdate(_ + 1).unit),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "onClick bubbles to parent" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div.id("parent").onClick(ref.getAndUpdate(_ + 1).unit)(
                UI.span("child").id("child"),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "onClickSelf does not fire on child click" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.div.id("target").onClickSelf(ref.getAndUpdate(_ + 1).unit)(
                    UI.button("child").id("child")
                ),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield ()
        }
    }

    "onClick + onClickSelf only onClick on child" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.div.id("target").onClick(ref.getAndUpdate(_ + 1).unit).onClickSelf(ref.getAndUpdate(_ + 1).unit)(
                    UI.button("child").id("child")
                ),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "onKeyDown fires with key" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "Enter")
            yield ()
        }
    }

    "onFocus does not bubble" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.div.id("parent").onFocus(ref.getAndUpdate(_ + 1).unit)(
                    UI.button("child").id("child")
                ),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield ()
        }
    }

    // ---- Merged from EventBubblingTest ----

    "click on span inside div - div onClick fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.div.id("parent").onClick(ref.getAndUpdate(_ + 1).unit)(
                    UI.span("child").id("child")
                ),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "click on span inside div inside section - all ancestors fire" in {
        val app: UI < Async =
            for
                divCount     <- Signal.initRef(0)
                sectionCount <- Signal.initRef(0)
            yield UI.div(
                UI.section.id("sec").onClick(sectionCount.getAndUpdate(_ + 1).unit)(
                    UI.div.id("d").onClick(divCount.getAndUpdate(_ + 1).unit)(
                        UI.span("click me").id("target")
                    )
                ),
                divCount.map(n => UI.span(s"div:$n").id("dv")),
                sectionCount.map(n => UI.span(s"sec:$n").id("sv"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("target"))
                _ <- Browser.assertText(Selector.id("dv"), "div:1")
                _ <- Browser.assertText(Selector.id("sv"), "sec:1")
            yield ()
        }
    }

    "click deep nesting (5 levels) - top-level onClick fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.div.id("top").onClick(ref.getAndUpdate(_ + 1).unit)(
                    UI.div(UI.div(UI.div(UI.div(UI.span("deep").id("deep")))))
                ),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("deep"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "onClickSelf on parent - child click does NOT trigger onClickSelf" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.div.id("parent").onClickSelf(ref.getAndUpdate(_ + 1).unit)(
                    UI.button("child").id("child")
                ),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield ()
        }
    }

    "onClickSelf on target - direct click DOES trigger" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.button("target").id("target").onClickSelf(ref.getAndUpdate(_ + 1).unit),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("target"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "focus on input inside div - div onFocus does NOT fire (bubbling)" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.div.id("parent").onFocus(ref.getAndUpdate(_ + 1).unit)(
                    UI.input.id("child")
                ),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield ()
        }
    }

    "blur on input - parent onBlur does NOT fire (bubbling)" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.div.id("parent").onBlur(ref.getAndUpdate(_ + 1).unit)(
                    UI.input.id("child"),
                    UI.button("other").id("other")
                ),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.click(Selector.id("other"))
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield ()
        }
    }

    "keyDown on input inside div - div onKeyDown fires (bubbling)" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.div.id("parent").onKeyDown(ke => ref.set(ke.key.toString))(
                    UI.input.id("child")
                ),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("child"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "Enter")
            yield ()
        }
    }

    "keyDown on input inside div inside section - all ancestors fire (bubbling)" in {
        val app: UI < Async =
            for
                divKey <- Signal.initRef("")
                secKey <- Signal.initRef("")
            yield UI.div(
                UI.section.id("sec").onKeyDown(ke => secKey.set(ke.key.toString))(
                    UI.div.id("d").onKeyDown(ke => divKey.set(ke.key.toString))(
                        UI.input.id("child")
                    )
                ),
                divKey.map(v => UI.span(s"div:$v").id("dv")),
                secKey.map(v => UI.span(s"sec:$v").id("sv"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("child"), Key.Escape)
                _ <- Browser.assertText(Selector.id("dv"), "div:Escape")
                _ <- Browser.assertText(Selector.id("sv"), "sec:Escape")
            yield ()
        }
    }

    "keyUp on input - parent onKeyUp fires (bubbling)" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.div.id("parent").onKeyUp(ke => ref.set(ke.key.toString))(
                    UI.input.id("child")
                ),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("child"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "Enter")
            yield ()
        }
    }

    "input on child input - correct handler routing" in {
        val app: UI < Async =
            for
                inputRef <- Signal.initRef("")
                keyRef   <- Signal.initRef("")
            yield UI.div(
                UI.div.id("parent").onKeyDown(ke => keyRef.set(ke.key.toString))(
                    UI.input.id("child").onInput(v => inputRef.set(v))
                ),
                inputRef.map(v => UI.span(s"inp:$v").id("iv")),
                keyRef.map(v => UI.span(s"key:$v").id("kv"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("child"), "hello")
                _ <- Browser.assertText(Selector.id("iv"), "inp:hello")
                _ <- Browser.press(Selector.id("child"), Key.Backspace)
                _ <- Browser.assertText(Selector.id("kv"), "key:Backspace")
            yield ()
        }
    }

    "click button inside form - form onSubmit fires (bubbling)" in {
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
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "click button NOT inside form - no submit event" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").onClick(ref.getAndUpdate(_ + 1).unit),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "click on button with onClick inside div with onClick - both fire" in {
        val app: UI < Async =
            for
                btnCount <- Signal.initRef(0)
                divCount <- Signal.initRef(0)
            yield UI.div(
                UI.div.id("parent").onClick(divCount.getAndUpdate(_ + 1).unit)(
                    UI.button("Go").id("btn").onClick(btnCount.getAndUpdate(_ + 1).unit)
                ),
                btnCount.map(n => UI.span(s"btn:$n").id("bv")),
                divCount.map(n => UI.span(s"div:$n").id("dv"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("bv"), "btn:1")
                _ <- Browser.assertText(Selector.id("dv"), "div:1")
            yield ()
        }
    }

    "click on disabled button inside div with onClick - div onClick fires" in {
        val app: UI < Async =
            for
                divCount <- Signal.initRef(0)
                btnCount <- Signal.initRef(0)
            yield UI.div(
                UI.div.id("parent").onClick(divCount.getAndUpdate(_ + 1).unit)(
                    UI.button("Go").id("btn").disabled(true).onClick(btnCount.getAndUpdate(_ + 1).unit)
                ),
                btnCount.map(n => UI.span(s"btn:$n").id("bv")),
                divCount.map(n => UI.span(s"div:$n").id("dv"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("bv"), "btn:0")
                _ <- Browser.assertText(Selector.id("dv"), "div:0")
            yield ()
        }
    }

    "onClickSelf + onClick on same element - both fire on direct click" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b")
                    .onClick(ref.getAndUpdate(_ + 1).unit)
                    .onClickSelf(ref.getAndUpdate(_ + 1).unit),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "2")
            yield ()
        }
    }

    // ---- Merged from EventConflictTest ----

    "onInput and onChange both set same signal consistent" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i")
                    .onInput(v => ref.set(s"input:$v"))
                    .onChange(v => ref.set(s"change:$v")),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "test")
                _ <- Browser.assertText(Selector.id("v"), "change:test")
            yield ()
        }
    }

    "onClick parent plus onClick child both fire child first" in {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.div(
                    UI.button("Child").id("child").onClick(log.getAndUpdate(_.appended("child")).unit)
                ).id("parent").onClick(log.getAndUpdate(_.appended("parent")).unit),
                log.map(entries => UI.span(entries.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("v"), "child,parent")
            yield ()
        }
    }

    "onClickSelf only on direct click" in {
        val app: UI < Async =
            for
                selfCount  <- Signal.initRef(0)
                clickCount <- Signal.initRef(0)
            yield UI.div(
                UI.div(UI.button("Child").id("child"))
                    .id("parent")
                    .style(Style.height(Length.Px(200)))
                    .onClickSelf(selfCount.getAndUpdate(_ + 1).unit)
                    .onClick(clickCount.getAndUpdate(_ + 1).unit),
                selfCount.map(n => UI.span(s"self:$n").id("vs")),
                clickCount.map(n => UI.span(s"click:$n").id("vc"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("vs"), "self:0")
                _ <- Browser.assertText(Selector.id("vc"), "click:1")
                _ <- Browser.click(Selector.id("parent"))
                _ <- Browser.assertText(Selector.id("vs"), "self:1")
                _ <- Browser.assertText(Selector.id("vc"), "click:2")
            yield ()
        }
    }

    "onSubmit plus onClick on submit button both fire" in {
        val app: UI < Async =
            for
                submitCount <- Signal.initRef(0)
                clickCount  <- Signal.initRef(0)
            yield UI.div(
                UI.form.id("f").onSubmit(submitCount.getAndUpdate(_ + 1).unit)(
                    UI.button("Go").id("sub").onClick(clickCount.getAndUpdate(_ + 1).unit)
                ),
                submitCount.map(n => UI.span(s"submit:$n").id("vs")),
                clickCount.map(n => UI.span(s"click:$n").id("vc"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("vc"), "click:1")
                _ <- Browser.assertText(Selector.id("vs"), "submit:1")
            yield ()
        }
    }

    "onFocus handler sets signal no infinite loop" in {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("i").onFocus(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "onChange on select re-renders options stable" in {
        val app: UI < Async =
            for
                selected <- Signal.initRef("a")
                log      <- Signal.initRef("")
            yield UI.div(
                UI.select(
                    UI.option("A").value("a"),
                    UI.option("B").value("b")
                ).id("s").value(selected).onChange(v => log.set(s"selected:$v")),
                log.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.select(Selector.id("s"), "b")
                _ <- Browser.assertText(Selector.id("v"), "selected:b")
                _ <- Browser.select(Selector.id("s"), "a")
                _ <- Browser.assertText(Selector.id("v"), "selected:a")
            yield ()
        }
    }

    "handler removes its own element no crash" in {
        val app: UI < Async =
            for show <- Signal.initRef(true)
            yield UI.div(
                UI.when(show)(UI.button("Remove").id("rm").onClick(show.set(false))),
                UI.span("sentinel").id("s")
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.assertVisible(Selector.id("s"))
            yield ()
        }
    }

    "handler adds new elements they become interactive" in {
        val app: UI < Async =
            for
                show    <- Signal.initRef(false)
                clicked <- Signal.initRef(false)
            yield UI.div(
                UI.button("Add").id("add").onClick(show.set(true)),
                UI.when(show)(UI.button("New").id("new").onClick(clicked.set(true))),
                clicked.map(v => UI.span(s"clicked:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.click(Selector.id("new"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:true")
            yield ()
        }
    }

    // ---- Merged from HandlerContractTest ----

    "onInput receives full value not just new char" in {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.input.id("i").onInput(v => log.getAndUpdate(_.appended(v)).unit),
                log.map(entries => UI.span(entries.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "ab")
                _ <- Browser.assertText(Selector.id("v"), "ab")
            yield ()
        }
    }

    "onInput on backspace receives remaining value" in {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.input.id("i").onInput(v => log.getAndUpdate(_.appended(s"[$v]")).unit),
                log.map(entries => UI.span(entries.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "xy")
                _ <- Browser.press(Selector.id("i"), Key.Backspace)
                _ <- Browser.assertText(Selector.id("v"), "[xy],[x]")
            yield ()
        }
    }

    "onInput with fill receives complete text" in {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.input.id("i").onInput(v => log.getAndUpdate(_.appended(v)).unit),
                log.map(entries => UI.span(entries.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hello")
                _ <- Browser.assertText(Selector.id("v"), "hello")
            yield ()
        }
    }

    "onChange fires with value on fill" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onChange(v => ref.set(s"changed:$v")),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "test")
                _ <- Browser.assertText(Selector.id("v"), "changed:test")
            yield ()
        }
    }

    "onChangeNumeric receives Double" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.numberInput.id("n").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"num:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("n"), "3.14")
                _ <- Browser.assertText(Selector.id("v"), "num:3.14")
            yield ()
        }
    }

    "set handler pattern works with fill" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "ab")
                _ <- Browser.assertText(Selector.id("v"), "sig:ab")
            yield ()
        }
    }

    "append handler is antipattern - onInput v is full value not delta" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.getAndUpdate(_ + v).unit),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "a")
                _ <- Browser.assertText(Selector.id("v"), "sig:a")
            yield ()
        }
    }

    "transform handler pattern works with fill" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v.toUpperCase)),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hello")
                _ <- Browser.assertText(Selector.id("v"), "sig:HELLO")
            yield ()
        }
    }

    "transform handler pattern works with multi fill" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v.toUpperCase)),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "ab")
                _ <- Browser.assertText(Selector.id("v"), "sig:AB")
            yield ()
        }
    }

    "onKeyUp fires after onKeyDown" in {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.input.id("i")
                    .onKeyDown(ke => log.getAndUpdate(_.appended(s"down:${ke.key}")).unit)
                    .onKeyUp(ke => log.getAndUpdate(_.appended(s"up:${ke.key}")).unit),
                log.map(entries => UI.span(entries.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key('x'))
                _ <- Browser.assertText(Selector.id("v"), "down:Char(x),up:Char(x)")
            yield ()
        }
    }

    "onClick on div fires (contract)" in {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.div(UI.span("Click me")).id("d").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(s"clicks:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("d"))
                _ <- Browser.assertText(Selector.id("v"), "clicks:1")
            yield ()
        }
    }

    "checkbox onChange receives boolean (contract)" in {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.checkbox.id("c").onChange(v => log.getAndUpdate(_.appended(v.toString)).unit),
                log.map(entries => UI.span(entries.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "true")
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "true,false")
            yield ()
        }
    }

    "select onChange receives selected value string (contract)" in {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.select(
                    UI.option("A").value("alpha"),
                    UI.option("B").value("beta")
                ).id("s").onChange(v => log.getAndUpdate(_.appended(v)).unit),
                log.map(entries => UI.span(entries.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.select(Selector.id("s"), "beta")
                _ <- Browser.assertText(Selector.id("v"), "beta")
                _ <- Browser.select(Selector.id("s"), "alpha")
                _ <- Browser.assertText(Selector.id("v"), "beta,alpha")
            yield ()
        }
    }

    "handler exception does not break subsequent interactions" in {
        val app: UI < Async =
            for
                counter <- Signal.initRef(0)
                broken  <- Signal.initRef(true)
            yield UI.div(
                UI.button("Boom").id("boom").onClick {
                    for
                        b <- broken.get
                        _ <- if b then Abort.panic(new RuntimeException("boom"))
                        else counter.getAndUpdate(_ + 1).unit
                    yield ()
                },
                UI.button("Fix").id("fix").onClick(broken.set(false)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("boom"))
                _ <- Browser.click(Selector.id("fix"))
                _ <- Browser.click(Selector.id("boom"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "both onInput and onChange fire on fill" in {
        val app: UI < Async =
            for
                inputLog  <- Signal.initRef("")
                changeLog <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i")
                    .onInput(v => inputLog.set(s"input:$v"))
                    .onChange(v => changeLog.set(s"change:$v")),
                inputLog.map(v => UI.span(v).id("vi")),
                changeLog.map(v => UI.span(v).id("vc"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "test")
                _ <- Browser.assertText(Selector.id("vi"), "input:test")
                _ <- Browser.assertText(Selector.id("vc"), "change:test")
            yield ()
        }
    }

    "focus and blur fire in correct order" in {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.input.id("a")
                    .onFocus(log.getAndUpdate(_.appended("focus:a")).unit)
                    .onBlur(log.getAndUpdate(_.appended("blur:a")).unit),
                UI.input.id("b")
                    .onFocus(log.getAndUpdate(_.appended("focus:b")).unit)
                    .onBlur(log.getAndUpdate(_.appended("blur:b")).unit),
                log.map(entries => UI.span(entries.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("v"), "focus:a")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "focus:a,blur:a,focus:b")
            yield ()
        }
    }

    "onInput called for each fill not batched" in {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("i").onInput(_ => counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(s"calls:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "abc")
                _ <- Browser.fill(Selector.id("i"), "xyz")
                _ <- Browser.assertText(Selector.id("v"), "calls:2")
            yield ()
        }
    }

    "filter handler strips non digits" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v.filter(_.isDigit))),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "a1b2c3")
                _ <- Browser.assertText(Selector.id("v"), "sig:123")
            yield ()
        }
    }

    "onKeyUp has same key as onKeyDown" in {
        val app: UI < Async =
            for
                downRef <- Signal.initRef("")
                upRef   <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i")
                    .onKeyDown(ke => downRef.set(ke.key.toString))
                    .onKeyUp(ke => upRef.set(ke.key.toString)),
                downRef.map(v => UI.span(s"down:$v").id("vd")),
                upRef.map(v => UI.span(s"up:$v").id("vu"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key('z'))
                _ <- Browser.assertText(Selector.id("vd"), "down:Char(z)")
                _ <- Browser.assertText(Selector.id("vu"), "up:Char(z)")
            yield ()
        }
    }

    "onClick fires on span (contract)" in {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.span("Click").id("s").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(s"clicks:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("s"))
                _ <- Browser.assertText(Selector.id("v"), "clicks:1")
            yield ()
        }
    }

    "onClick on nested child bubbles to parent (contract)" in {
        val app: UI < Async =
            for
                parentClicks <- Signal.initRef(0)
                childClicks  <- Signal.initRef(0)
            yield UI.div(
                UI.div(
                    UI.button("Child").id("child").onClick(childClicks.getAndUpdate(_ + 1).unit)
                ).id("parent").onClick(parentClicks.getAndUpdate(_ + 1).unit),
                parentClicks.map(n => UI.span(s"parent:$n").id("vp")),
                childClicks.map(n => UI.span(s"child:$n").id("vc"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("vc"), "child:1")
                _ <- Browser.assertText(Selector.id("vp"), "parent:1")
            yield ()
        }
    }

    "onClickSelf fires only on direct target (contract)" in {
        val app: UI < Async =
            for selfClicks <- Signal.initRef(0)
            yield UI.div(
                UI.div(UI.button("Child").id("child"))
                    .id("parent").style(Style.height(Length.Px(200)))
                    .onClickSelf(selfClicks.getAndUpdate(_ + 1).unit),
                selfClicks.map(n => UI.span(s"self:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("v"), "self:0")
                _ <- Browser.click(Selector.id("parent"))
                _ <- Browser.assertText(Selector.id("v"), "self:1")
            yield ()
        }
    }

    "select onChange receives option value not display text" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.select(
                    UI.option("Display Label").value("internal-val")
                ).id("s").onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"got:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.select(Selector.id("s"), "internal-val")
                _ <- Browser.assertText(Selector.id("v"), "got:internal-val")
            yield ()
        }
    }

    "handler throws in onInput next fill still dispatches" in {
        val app: UI < Async =
            for
                counter <- Signal.initRef(0)
                broken  <- Signal.initRef(true)
            yield UI.div(
                UI.input.id("i").onInput { _ =>
                    for
                        b <- broken.get
                        _ <- if b then Abort.panic(new RuntimeException("boom"))
                        else counter.getAndUpdate(_ + 1).unit
                    yield ()
                },
                UI.button("Fix").id("fix").onClick(broken.set(false)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "a")
                _ <- Browser.click(Selector.id("fix"))
                _ <- Browser.fill(Selector.id("i"), "b")
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "two handlers on same element both work" in {
        val app: UI < Async =
            for
                clickRef <- Signal.initRef(0)
                keyRef   <- Signal.initRef("")
            yield UI.div(
                UI.button("Go").id("b")
                    .onClick(clickRef.getAndUpdate(_ + 1).unit)
                    .onKeyDown(ke => keyRef.set(ke.key.toString)),
                clickRef.map(n => UI.span(s"clicks:$n").id("vc")),
                keyRef.map(v => UI.span(s"key:$v").id("vk"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("vc"), "clicks:1")
                _ <- Browser.press(Selector.id("b"), Key('x'))
                _ <- Browser.assertText(Selector.id("vk"), "key:Char(x)")
            yield ()
        }
    }

    "handler sets signal that triggers re-render completes" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A")))
            yield UI.div(
                UI.button("Add").id("add").onClick(items.getAndUpdate(_.appended("B")).unit),
                items.map(is => UI.span(is.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("v"), "A,B")
            yield ()
        }
    }

    "onInput receives empty string when cleared" in {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.input.id("i").onInput(v => log.getAndUpdate(_.appended(s"[$v]")).unit),
                log.map(entries => UI.span(entries.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "abc")
                _ <- Browser.fill(Selector.id("i"), "")
                _ <- assertContains("[abc]")
                _ <- assertContains("[]")
            yield ()
        }
    }

    "checkbox onChange receives false on uncheck" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.checkbox.checked(true).id("c").onChange(v => ref.set(v.toString)),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "val:false")
            yield ()
        }
    }

    // ---- Merged from ContainerEventsTest ----

    "Div onClick fires on click" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.div.id("d").onClick(ref.getAndUpdate(_ + 1).unit)(UI.span("content")),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("d"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "Section onClick fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.section.id("sec").onClick(ref.getAndUpdate(_ + 1).unit)(
                    UI.span("content").id("child")
                ),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "Header onClick fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.header.id("hdr").onClick(ref.getAndUpdate(_ + 1).unit)(
                    UI.span("content").id("child")
                ),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "Span onClick fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.span.id("s").onClick(ref.getAndUpdate(_ + 1).unit)("clickable"),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("s"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "Span onKeyDown fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.span.id("s").onKeyDown(ke => ref.set(ke.key.toString))(
                    UI.input.id("child")
                ),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("child"), Key.Escape)
                _ <- Browser.assertText(Selector.id("v"), "Escape")
            yield ()
        }
    }

    "Nav onClick fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.nav.id("n").onClick(ref.getAndUpdate(_ + 1).unit)(
                    UI.span("link").id("child")
                ),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "P onClick fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.p.id("para").onClick(ref.getAndUpdate(_ + 1).unit)("paragraph"),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("para"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "Li onClick fires - click on child bubbles" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.ul(
                    UI.li.id("item").onClick(ref.getAndUpdate(_ + 1).unit)(
                        UI.span("text").id("child")
                    )
                ),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("child"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "Tr onClick fires - Td click bubbles" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.table(
                    UI.tr.id("row").onClick(ref.getAndUpdate(_ + 1).unit)(
                        UI.td("cell").id("cell")
                    )
                ),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("cell"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "Td onClick fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.table(UI.tr(UI.td.id("cell").onClick(ref.getAndUpdate(_ + 1).unit)("data"))),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("cell"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "Th onClick fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.table(UI.tr(UI.th.id("hcell").onClick(ref.getAndUpdate(_ + 1).unit)("header"))),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("hcell"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "Span tabIndex(-1) assertAttribute" in {
        withUI(UI.div(UI.span.tabIndex(-1).id("s"))) {
            Browser.assertAttribute(Selector.id("s"), "tabindex", "-1").unit
        }
    }

end EventHandlerTest
