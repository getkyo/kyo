package kyo

import kyo.Browser.*

/** Keyboard modifier and focus/key-event tests. */
class KeyboardTest extends UITest:

    // ---- KeyModifier ----

    private def keyEvalApp(using Frame): UI < Async =
        for ref <- Signal.initRef("")
        yield UI.div(
            UI.input.id("i").onKeyDown { ke =>
                ref.set(
                    s"key=${ke.key}|ctrl=${ke.modifiers.ctrl}|alt=${ke.modifiers.alt}|shift=${ke.modifiers.shift}|meta=${ke.modifiers.meta}"
                )
            },
            ref.map(v => UI.span(v).id("v"))
        )

    "Ctrl+a onKeyDown receives ctrl=true" in {
        keyEvalApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.press(Selector.id("i"), Key('a'), KeyModifiers(ctrl = true))
                    _ <- Browser.assertText(Selector.id("v"), "key=Char(a)|ctrl=true|alt=false|shift=false|meta=false")
                yield ()
            }
        }
    }

    "Ctrl+c onKeyDown receives ctrl=true" in {
        keyEvalApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.press(Selector.id("i"), Key('c'), KeyModifiers(ctrl = true))
                    _ <- Browser.assertText(Selector.id("v"), "key=Char(c)|ctrl=true|alt=false|shift=false|meta=false")
                yield ()
            }
        }
    }

    "Ctrl+Enter on button reports both ctrl and Enter" in {
        keyEvalApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.press(Selector.id("i"), Key.Enter, KeyModifiers(ctrl = true))
                    _ <- Browser.assertText(Selector.id("v"), "key=Enter|ctrl=true|alt=false|shift=false|meta=false")
                yield ()
            }
        }
    }

    "Shift+A onKeyDown receives shift=true" in {
        keyEvalApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.press(Selector.id("i"), Key('A'), KeyModifiers(shift = true))
                    _ <- Browser.assertText(Selector.id("v"), "key=Char(A)|ctrl=false|alt=false|shift=true|meta=false")
                yield ()
            }
        }
    }

    "Shift+Tab onKeyDown fires" in {
        keyEvalApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.click(Selector.id("i"))
                    _ <- Browser.press(Selector.id("i"), Key.Tab, KeyModifiers(shift = true))
                    _ <- Browser.assertText(Selector.id("v"), "key=Tab|ctrl=false|alt=false|shift=true|meta=false")
                yield ()
            }
        }
    }

    "Alt+x onKeyDown receives alt=true" in {
        keyEvalApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.press(Selector.id("i"), Key('x'), KeyModifiers(alt = true))
                    _ <- Browser.assertText(Selector.id("v"), "key=Char(x)|ctrl=false|alt=true|shift=false|meta=false")
                yield ()
            }
        }
    }

    "Meta+s onKeyDown receives meta=true" in {
        keyEvalApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.press(Selector.id("i"), Key('s'), KeyModifiers(meta = true))
                    _ <- Browser.assertText(Selector.id("v"), "key=Char(s)|ctrl=false|alt=false|shift=false|meta=true")
                yield ()
            }
        }
    }

    "Ctrl+Shift+Enter all three modifiers" in {
        keyEvalApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.press(Selector.id("i"), Key.Enter, KeyModifiers(ctrl = true, shift = true))
                    _ <- Browser.assertText(Selector.id("v"), "key=Enter|ctrl=true|alt=false|shift=true|meta=false")
                yield ()
            }
        }
    }

    "Ctrl+Alt+Delete all modifiers reported" in {
        keyEvalApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.press(Selector.id("i"), Key.Delete, KeyModifiers(ctrl = true, alt = true))
                    _ <- Browser.assertText(Selector.id("v"), "key=Delete|ctrl=true|alt=true|shift=false|meta=false")
                yield ()
            }
        }
    }

    "Ctrl+ArrowRight onKeyDown with ctrl=true and ArrowRight" in {
        keyEvalApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.press(Selector.id("i"), Key.ArrowRight, KeyModifiers(ctrl = true))
                    _ <- Browser.assertText(Selector.id("v"), "key=ArrowRight|ctrl=true|alt=false|shift=false|meta=false")
                yield ()
            }
        }
    }

    "Shift+ArrowDown onKeyDown with shift=true" in {
        keyEvalApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.press(Selector.id("i"), Key.ArrowDown, KeyModifiers(shift = true))
                    _ <- Browser.assertText(Selector.id("v"), "key=ArrowDown|ctrl=false|alt=false|shift=true|meta=false")
                yield ()
            }
        }
    }

    "PageUp fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.PageUp)
                _ <- Browser.assertText(Selector.id("v"), "PageUp")
            yield ()
        }
    }

    "PageDown fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.PageDown)
                _ <- Browser.assertText(Selector.id("v"), "PageDown")
            yield ()
        }
    }

    "Home fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.Home)
                _ <- Browser.assertText(Selector.id("v"), "Home")
            yield ()
        }
    }

    "End fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.End)
                _ <- Browser.assertText(Selector.id("v"), "End")
            yield ()
        }
    }

    // ---- KeyboardFocus ----

    "Enter on button fires onClick" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").onClick(ref.getAndUpdate(_ + 1).unit),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("b"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "Space on button fires onClick" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").onClick(ref.getAndUpdate(_ + 1).unit),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("b"), Key.Space)
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    "Escape fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.Escape)
                _ <- Browser.assertText(Selector.id("v"), "Escape")
            yield ()
        }
    }

    "Backspace fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.Backspace)
                _ <- Browser.assertText(Selector.id("v"), "Backspace")
            yield ()
        }
    }

    "ArrowDown fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.ArrowDown)
                _ <- Browser.assertText(Selector.id("v"), "ArrowDown")
            yield ()
        }
    }

    "ArrowUp fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.ArrowUp)
                _ <- Browser.assertText(Selector.id("v"), "ArrowUp")
            yield ()
        }
    }

    "ArrowLeft fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.ArrowLeft)
                _ <- Browser.assertText(Selector.id("v"), "ArrowLeft")
            yield ()
        }
    }

    "ArrowRight fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.ArrowRight)
                _ <- Browser.assertText(Selector.id("v"), "ArrowRight")
            yield ()
        }
    }

    "Char('1') digit fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key('1'))
                _ <- Browser.assertText(Selector.id("v"), "Char(1)")
            yield ()
        }
    }

    "Char('Z') uppercase fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key('Z'))
                _ <- Browser.assertText(Selector.id("v"), "Char(Z)")
            yield ()
        }
    }

    "Char('a') fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key('a'))
                _ <- Browser.assertText(Selector.id("v"), "Char(a)")
            yield ()
        }
    }

    "Delete fires onKeyDown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyDown(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.Delete)
                _ <- Browser.assertText(Selector.id("v"), "Delete")
            yield ()
        }
    }

    "onKeyUp fires (Enter)" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyUp(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "Enter")
            yield ()
        }
    }

    "onKeyUp fires (Char)" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onKeyUp(ke => ref.set(ke.key.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key('x'))
                _ <- Browser.assertText(Selector.id("v"), "Char(x)")
            yield ()
        }
    }

    "onKeyDown + onKeyUp both fire" in {
        val app: UI < Async =
            for
                downKey <- Signal.initRef("")
                upKey   <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i")
                    .onKeyDown(ke => downKey.set(ke.key.toString))
                    .onKeyUp(ke => upKey.set(ke.key.toString)),
                downKey.map(v => UI.span(s"down:$v").id("dv")),
                upKey.map(v => UI.span(s"up:$v").id("uv"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.Enter)
                _ <- Browser.assertText(Selector.id("dv"), "down:Enter")
                _ <- Browser.assertText(Selector.id("uv"), "up:Enter")
            yield ()
        }
    }

    "Focus A then Focus B fires onBlur(A)" in {
        val app: UI < Async =
            for blurred <- Signal.initRef(false)
            yield UI.div(
                UI.button("A").id("a").onBlur(blurred.set(true)),
                UI.button("B").id("b"),
                blurred.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "Focus A then Focus B fires onFocus(B)" in {
        val app: UI < Async =
            for focused <- Signal.initRef(false)
            yield UI.div(
                UI.button("A").id("a"),
                UI.button("B").id("b").onFocus(focused.set(true)),
                focused.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "Focus + blur + refocus same element" in {
        val app: UI < Async =
            for
                focusCount <- Signal.initRef(0)
                blurCount  <- Signal.initRef(0)
            yield UI.div(
                UI.button("A").id("a")
                    .onFocus(focusCount.getAndUpdate(_ + 1).unit)
                    .onBlur(blurCount.getAndUpdate(_ + 1).unit),
                UI.button("B").id("b"),
                focusCount.map(n => UI.span(s"f:$n").id("fv")),
                blurCount.map(n => UI.span(s"b:$n").id("bv"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("fv"), "f:1")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("bv"), "b:1")
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("fv"), "f:2")
            yield ()
        }
    }

    "Focus element that doesn't exist yet (reactive) retries" in {
        val app: UI < Async =
            for show <- Signal.initRef(false)
            yield UI.div(
                UI.button("Show").id("show").onClick(show.set(true)),
                UI.when(show)(UI.button("Target").id("target"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("show"))
                _ <- Browser.click(Selector.id("target"))
                _ <- Browser.assertVisible(Selector.id("target"))
            yield ()
        }
    }

    "tabIndex on non-focusable container (Div) makes it focusable" in {
        withUI(UI.div(UI.div.tabIndex(0).id("d")("content"))) {
            for
                _ <- Browser.click(Selector.id("d"))
                _ <- Browser.assertVisible(Selector.id("d"))
            yield ()
        }
    }

    "Type chars into input fires onInput per char" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "abc")
                _ <- Browser.assertText(Selector.id("v"), "abc")
            yield ()
        }
    }

    "Arrow in input fires onKeyDown only" in {
        val app: UI < Async =
            for
                keyRef   <- Signal.initRef("")
                inputRef <- Signal.initRef("none")
            yield UI.div(
                UI.input.id("i")
                    .onKeyDown(ke => keyRef.set(ke.key.toString))
                    .onInput(v => inputRef.set(v)),
                keyRef.map(v => UI.span(s"key:$v").id("kv")),
                inputRef.map(v => UI.span(s"inp:$v").id("iv"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.ArrowLeft)
                _ <- Browser.assertText(Selector.id("kv"), "key:ArrowLeft")
                _ <- Browser.assertText(Selector.id("iv"), "inp:none")
            yield ()
        }
    }

    "Enter in input inside form fires submit" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.form.id("f").onSubmit(ref.set(true))(UI.input.id("i")),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "Disabled button + Enter does not fire onClick" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").disabled(true).onClick(ref.getAndUpdate(_ + 1).unit),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield ()
        }
    }

    "Disabled input + Char does not fire onInput" in {
        val app: UI < Async =
            for ref <- Signal.initRef("none")
            yield UI.div(
                UI.input.id("i").disabled(true).onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("i"))
                _ <- Browser.assertText(Selector.id("v"), "none")
            yield ()
        }
    }

    "Disabled checkbox + Enter does not toggle" in {
        val app: UI < Async =
            for ref <- Signal.initRef("none")
            yield UI.div(
                UI.checkbox.id("c").disabled(true).onChange(b => ref.set(b.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("c"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "none")
            yield ()
        }
    }

    "Disabled select + key does not change" in {
        val app: UI < Async =
            for ref <- Signal.initRef("none")
            yield UI.div(
                UI.select(
                    UI.option("Alpha").value("a"),
                    UI.option("Beta").value("b")
                ).id("s").disabled(true).onChange(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("s"), Key.ArrowDown)
                _ <- Browser.assertText(Selector.id("v"), "none")
            yield ()
        }
    }

    "Enter on checkbox toggles" in {
        // TUI semantics: Enter toggles. Browser semantics: only Space toggles checkboxes; Enter
        // on a focused checkbox is a no-op. kyo-ui adds a TUI-style Enter shim in HtmlRenderer.
        val app: UI < Async =
            for ref <- Signal.initRef("none")
            yield UI.div(
                UI.checkbox.id("c").onChange(b => ref.set(b.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("c"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "Enter on radio checks" in {
        // TUI semantics: Enter checks. Browser semantics: Space (or arrows in a group) selects radios.
        // kyo-ui adds a TUI-style Enter shim in HtmlRenderer.
        val app: UI < Async =
            for ref <- Signal.initRef("none")
            yield UI.div(
                UI.radio.id("r").onChange(b => ref.set(b.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("r"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "Three elements - cycle all" in {
        withUI(UI.div(
            UI.button("A").id("a"),
            UI.button("B").id("b"),
            UI.button("C").id("c")
        )) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertFocused(Selector.id("a"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertFocused(Selector.id("b"))
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertFocused(Selector.id("c"))
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertFocused(Selector.id("a"))
            yield ()
        }
    }

    "assertFocused(B) + assertNotFocused(A)" in {
        withUI(UI.div(UI.button("A").id("a"), UI.button("B").id("b"))) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertFocused(Selector.id("b"))
                _ <- Browser.assertNotFocused(Selector.id("a"))
            yield ()
        }
    }

    "Space on checkbox toggles" in {
        val app: UI < Async =
            for ref <- Signal.initRef("none")
            yield UI.div(
                UI.checkbox.id("c").onChange(b => ref.set(b.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("c"), Key.Space)
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "pressKey Backspace removes last char" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "abc")
                _ <- Browser.assertText(Selector.id("v"), "val:abc")
                _ <- Browser.press(Selector.id("i"), Key.Backspace)
                _ <- Browser.assertText(Selector.id("v"), "val:ab")
            yield ()
        }
    }

    "pressKey multiple chars into input" in {
        // Browser.fill is the canonical way to type a string. Multi-press resets cursor each time
        // (kyo-browser limitation).
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"got:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hi!")
                _ <- Browser.assertText(Selector.id("v"), "got:hi!")
            yield ()
        }
    }

end KeyboardTest
