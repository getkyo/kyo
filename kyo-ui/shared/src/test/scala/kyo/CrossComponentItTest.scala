package kyo

import kyo.Browser.*
import kyo.UI.foreach

class CrossComponentItTest extends UITest:

    "input value preserved when focus moves away and back" in {
        val app: UI < Async =
            for
                a <- Signal.initRef("")
                b <- Signal.initRef("")
            yield UI.div(
                UI.input.id("a").value(a).onInput(v => a.set(v)),
                UI.input.id("b").value(b).onInput(v => b.set(v)),
                a.map(v => UI.span(s"a:$v").id("va")),
                b.map(v => UI.span(s"b:$v").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("a"), "first")
                _ <- Browser.fill(Selector.id("b"), "second")
                _ <- Browser.assertText(Selector.id("va"), "a:first")
                _ <- Browser.assertText(Selector.id("vb"), "b:second")
                _ <- Browser.assertAttribute(Selector.id("a"), "value", "first")
            yield ()
        }
    }

    "fill in two inputs independently preserves values" in {
        val app: UI < Async =
            for
                a <- Signal.initRef("")
                b <- Signal.initRef("")
            yield UI.div(
                UI.input.id("a").value(a).onInput(v => a.set(v)),
                UI.input.id("b").value(b).onInput(v => b.set(v)),
                a.map(v => UI.span(s"a:$v").id("va")),
                b.map(v => UI.span(s"b:$v").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("a"), "xz")
                _ <- Browser.fill(Selector.id("b"), "y")
                _ <- Browser.assertText(Selector.id("va"), "a:xz")
                _ <- Browser.assertText(Selector.id("vb"), "b:y")
            yield ()
        }
    }

    "checkbox enables input" in {
        val app: UI < Async =
            for
                enabled <- Signal.initRef(false)
                value   <- Signal.initRef("")
            yield UI.div(
                UI.checkbox.id("toggle").onChange(v => enabled.set(v)),
                enabled.map { e =>
                    UI.input.id("i").disabled(!e).onInput(v => value.set(v))
                },
                value.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("i"))
                _ <- Browser.click(Selector.id("toggle"))
                _ <- Browser.assertEnabled(Selector.id("i"))
                _ <- Browser.fill(Selector.id("i"), "now enabled")
                _ <- Browser.assertText(Selector.id("v"), "val:now enabled")
            yield ()
        }
    }

    "select option enables corresponding input" in {
        val app: UI < Async =
            for
                mode  <- Signal.initRef("none")
                value <- Signal.initRef("")
            yield UI.div(
                UI.select(
                    UI.option("None").value("none"),
                    UI.option("Custom").value("custom")
                ).id("mode").value(mode).onChange(v => mode.set(v)),
                mode.map {
                    case "custom" => UI.input.id("custom-val").onInput(v => value.set(v))
                    case _        => UI.span("Select custom to enter value").id("hint")
                },
                value.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertNotExists(Selector.id("custom-val"))
                _ <- Browser.select(Selector.id("mode"), "custom")
                _ <- Browser.fill(Selector.id("custom-val"), "my value")
                _ <- Browser.assertText(Selector.id("v"), "val:my value")
            yield ()
        }
    }

    "input updates multiple display elements" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"echo:$v").id("echo")),
                ref.map(v => UI.span(s"len:${v.length}").id("len")),
                ref.map(v => UI.span(s"upper:${v.toUpperCase}").id("upper"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hello")
                _ <- Browser.assertText(Selector.id("echo"), "echo:hello")
                _ <- Browser.assertText(Selector.id("len"), "len:5")
                _ <- Browser.assertText(Selector.id("upper"), "upper:HELLO")
            yield ()
        }
    }

    "input value drives button disabled state" in {
        val app: UI < Async =
            for
                text    <- Signal.initRef("")
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("i").onInput(v => text.set(v)),
                text.map { v =>
                    UI.button("Submit").id("sub").disabled(v.isEmpty).onClick(counter.getAndUpdate(_ + 1).unit)
                },
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("sub"))
                _ <- Browser.fill(Selector.id("i"), "x")
                _ <- Browser.assertEnabled(Selector.id("sub"))
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "form with input checkbox select radio all contribute to output" in {
        val app: UI < Async =
            for
                text     <- Signal.initRef("")
                checked  <- Signal.initRef(false)
                selected <- Signal.initRef("a")
                radio    <- Signal.initRef("")
                result   <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        t <- text.get
                        c <- checked.get
                        s <- selected.get
                        r <- radio.get
                        _ <- result.set(s"$t|$c|$s|$r")
                    yield ()
                }(
                    UI.input.id("txt").value(text).onInput(v => text.set(v)),
                    UI.checkbox.id("chk").onChange(v => checked.set(v)),
                    UI.select(UI.option("A").value("a"), UI.option("B").value("b")).id("sel")
                        .value(selected).onChange(v => selected.set(v)),
                    UI.radio.name("rg").id("r1").onChange(v => if v then radio.set("r1") else Kyo.lift(())),
                    UI.radio.name("rg").id("r2").onChange(v => if v then radio.set("r2") else Kyo.lift(())),
                    UI.button("Go").id("go")
                ),
                result.map(v => UI.span(v).id("out"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("txt"), "hello")
                _ <- Browser.click(Selector.id("chk"))
                _ <- Browser.select(Selector.id("sel"), "b")
                _ <- Browser.click(Selector.id("r2"))
                _ <- Browser.click(Selector.id("go"))
                _ <- Browser.assertText(Selector.id("out"), "hello|true|b|r2")
            yield ()
        }
    }

    "button click updates multiple signals" in {
        val app: UI < Async =
            for
                a <- Signal.initRef(0)
                b <- Signal.initRef(0)
                c <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("go").onClick {
                    a.getAndUpdate(_ + 1).andThen(b.getAndUpdate(_ + 10)).andThen(c.getAndUpdate(_ + 100)).unit
                },
                a.map(v => UI.span(s"a:$v").id("va")),
                b.map(v => UI.span(s"b:$v").id("vb")),
                c.map(v => UI.span(s"c:$v").id("vc"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("go"))
                _ <- Browser.assertText(Selector.id("va"), "a:1")
                _ <- Browser.assertText(Selector.id("vb"), "b:10")
                _ <- Browser.assertText(Selector.id("vc"), "c:100")
            yield ()
        }
    }

    "delete from middle of list then fill remaining input" in {
        val app: UI < Async =
            for
                items <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
                log   <- Signal.initRef("")
            yield UI.div(
                UI.button("Remove B").id("rm").onClick(items.getAndUpdate(_.filter(_ != "B")).unit),
                items.foreach { s =>
                    UI.input.id(s"inp-$s").onInput(v => log.set(s"$s:$v"))
                },
                log.map(v => UI.span(s"log:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.fill(Selector.id("inp-A"), "hello")
                _ <- Browser.assertText(Selector.id("v"), "log:A:hello")
                _ <- Browser.fill(Selector.id("inp-C"), "world")
                _ <- Browser.assertText(Selector.id("v"), "log:C:world")
            yield ()
        }
    }

    "label click focuses associated input" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.label("Name:").forId("i").id("lbl"),
                UI.input.id("i").onFocus(ref.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("lbl"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "two forms submit independently" in {
        val app: UI < Async =
            for
                r1 <- Signal.initRef("")
                r2 <- Signal.initRef("")
                i1 <- Signal.initRef("")
                i2 <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f1").onSubmit(i1.get.map(v => r1.set(s"f1:$v")))(
                    UI.input.id("inp1").value(i1).onInput(v => i1.set(v)),
                    UI.button("Sub1").id("sub1")
                ),
                UI.form.id("f2").onSubmit(i2.get.map(v => r2.set(s"f2:$v")))(
                    UI.input.id("inp2").value(i2).onInput(v => i2.set(v)),
                    UI.button("Sub2").id("sub2")
                ),
                r1.map(v => UI.span(v).id("v1")),
                r2.map(v => UI.span(v).id("v2"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp1"), "aaa")
                _ <- Browser.fill(Selector.id("inp2"), "bbb")
                _ <- Browser.click(Selector.id("sub1"))
                _ <- Browser.assertText(Selector.id("v1"), "f1:aaa")
                _ <- Browser.assertText(Selector.id("v2"), "")
                _ <- Browser.click(Selector.id("sub2"))
                _ <- Browser.assertText(Selector.id("v2"), "f2:bbb")
            yield ()
        }
    }

    "rapid fill across 5 inputs" in {
        val app: UI < Async =
            for refs <- Kyo.foreach(Chunk.from(0 until 5))(i => Signal.initRef("").map(r => (i, r)))
            yield
                val children: Seq[UI.Ast.HtmlChildVal] = refs.toSeq.flatMap { case (i, r) =>
                    Seq[UI.Ast.HtmlChildVal](
                        UI.input.id(s"i$i").value(r).onInput(v => r.set(v)),
                        r.map(v => UI.span(s"v$i:$v").id(s"v$i"))
                    )
                }
                UI.div(children*)
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i0"), "a")
                _ <- Browser.fill(Selector.id("i1"), "b")
                _ <- Browser.fill(Selector.id("i2"), "c")
                _ <- Browser.fill(Selector.id("i3"), "d")
                _ <- Browser.fill(Selector.id("i4"), "e")
                _ <- Browser.assertText(Selector.id("v0"), "v0:a")
                _ <- Browser.assertText(Selector.id("v1"), "v1:b")
                _ <- Browser.assertText(Selector.id("v2"), "v2:c")
                _ <- Browser.assertText(Selector.id("v3"), "v3:d")
                _ <- Browser.assertText(Selector.id("v4"), "v4:e")
            yield ()
        }
    }

    "select changes which component is rendered" in {
        val app: UI < Async =
            for
                mode   <- Signal.initRef("text")
                result <- Signal.initRef("")
            yield UI.div(
                UI.select(
                    UI.option("Text").value("text"),
                    UI.option("Number").value("number"),
                    UI.option("Checkbox").value("check")
                ).id("mode").value(mode).onChange(v => mode.set(v)),
                mode.map {
                    case "text"   => UI.input.id("field").onInput(v => result.set(s"text:$v"))
                    case "number" => UI.numberInput.id("field").onInput(v => result.set(s"num:$v"))
                    case "check"  => UI.checkbox.id("field").onChange(v => result.set(s"check:$v"))
                    case _        => UI.span("?")
                },
                result.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("field"), "hello")
                _ <- Browser.assertText(Selector.id("v"), "text:hello")
                _ <- Browser.select(Selector.id("mode"), "check")
                _ <- Browser.click(Selector.id("field"))
                _ <- Browser.assertText(Selector.id("v"), "check:true")
            yield ()
        }
    }

    "form submit reads all SignalRef values all current" in {
        val app: UI < Async =
            for
                a      <- Signal.initRef("")
                b      <- Signal.initRef("")
                result <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        va <- a.get
                        vb <- b.get
                        _  <- result.set(s"$va|$vb")
                    yield ()
                }(
                    UI.input.id("a").value(a),
                    UI.input.id("b").value(b),
                    UI.button("Go").id("go")
                ),
                result.map(v => UI.span(s"result:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("a"), "hello")
                _ <- Browser.fill(Selector.id("b"), "world")
                _ <- Browser.click(Selector.id("go"))
                _ <- Browser.assertText(Selector.id("v"), "result:hello|world")
            yield ()
        }
    }

    "fill form fields in reverse order all captured" in {
        val app: UI < Async =
            for
                a      <- Signal.initRef("")
                b      <- Signal.initRef("")
                c      <- Signal.initRef("")
                result <- Signal.initRef("")
            yield UI.div(
                UI.input.id("a").value(a),
                UI.input.id("b").value(b),
                UI.input.id("c").value(c),
                UI.button("Go").id("go").onClick {
                    for
                        va <- a.get
                        vb <- b.get
                        vc <- c.get
                        _  <- result.set(s"$va|$vb|$vc")
                    yield ()
                },
                result.map(v => UI.span(s"result:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("c"), "third")
                _ <- Browser.fill(Selector.id("b"), "second")
                _ <- Browser.fill(Selector.id("a"), "first")
                _ <- Browser.click(Selector.id("go"))
                _ <- Browser.assertText(Selector.id("v"), "result:first|second|third")
            yield ()
        }
    }

    "interact switch page come back values preserved" in {
        val app: UI < Async =
            for
                page <- Signal.initRef(1)
                valA <- Signal.initRef("")
                valB <- Signal.initRef("")
            yield UI.div(
                UI.button("Page 1").id("p1").onClick(page.set(1)),
                UI.button("Page 2").id("p2").onClick(page.set(2)),
                page.map {
                    case 1 => UI.input.id("a").value(valA)
                    case _ => UI.input.id("b").value(valB)
                },
                valA.map(v => UI.span(s"a:$v").id("va")),
                valB.map(v => UI.span(s"b:$v").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("a"), "hello")
                _ <- Browser.assertText(Selector.id("va"), "a:hello")
                _ <- Browser.click(Selector.id("p2"))
                _ <- Browser.fill(Selector.id("b"), "world")
                _ <- Browser.assertText(Selector.id("vb"), "b:world")
                _ <- Browser.click(Selector.id("p1"))
                _ <- Browser.assertText(Selector.id("va"), "a:hello")
            yield ()
        }
    }

    "checkbox enables input fill uncheck disabled signal preserved" in {
        val app: UI < Async =
            for
                enabled <- Signal.initRef(false)
                value   <- Signal.initRef("")
            yield UI.div(
                UI.checkbox.id("toggle").onChange(v => enabled.set(v)),
                enabled.map { e =>
                    if e then UI.input.id("field").value(value)
                    else UI.span("disabled").id("off")
                },
                value.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("off"), "disabled")
                _ <- Browser.click(Selector.id("toggle"))
                _ <- Browser.assertNotExists(Selector.id("off"))
                _ <- Browser.fill(Selector.id("field"), "data")
                _ <- Browser.assertText(Selector.id("v"), "val:data")
                _ <- Browser.click(Selector.id("toggle"))
                _ <- Browser.assertText(Selector.id("off"), "disabled")
                _ <- Browser.assertText(Selector.id("v"), "val:data")
            yield ()
        }
    }

end CrossComponentItTest
