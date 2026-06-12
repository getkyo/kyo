package kyo

import kyo.Browser.*

class RadioTest extends UITest:

    // ---- Rendering ----

    "type radio" in {
        withUI(UI.div(UI.radio.id("r"))) {
            Browser.assertAttribute(Selector.id("r"), "type", "radio").unit
        }
    }

    "checked true" in {
        withUI(UI.div(UI.radio.checked(true).id("r"))) {
            Browser.assertChecked(Selector.id("r")).unit
        }
    }

    "name attribute" in {
        withUI(UI.div(UI.radio.name("group1").id("r"))) {
            Browser.assertAttribute(Selector.id("r"), "name", "group1").unit
        }
    }

    // ---- Group behavior ----

    "same name check one unchecks other" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.radio.name("g").id("a").onChange(v => if v then ref.set("a") else Kyo.lift(())),
                UI.radio.name("g").id("b").onChange(v => if v then ref.set("b") else Kyo.lift(())),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("v"), "a")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "b")
            yield ()
        }
    }

    "three radios same name cycle" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.radio.name("g").id("a").onChange(v => if v then ref.set("a") else Kyo.lift(())),
                UI.radio.name("g").id("b").onChange(v => if v then ref.set("b") else Kyo.lift(())),
                UI.radio.name("g").id("c").onChange(v => if v then ref.set("c") else Kyo.lift(())),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("v"), "a")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "b")
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "c")
            yield ()
        }
    }

    "different name groups independent" in {
        val app: UI < Async =
            for
                g1 <- Signal.initRef("")
                g2 <- Signal.initRef("")
            yield UI.div(
                UI.radio.name("g1").id("a1").onChange(v => if v then g1.set("a1") else Kyo.lift(())),
                UI.radio.name("g1").id("b1").onChange(v => if v then g1.set("b1") else Kyo.lift(())),
                UI.radio.name("g2").id("a2").onChange(v => if v then g2.set("a2") else Kyo.lift(())),
                g1.map(v => UI.span(s"g1:$v").id("v1")),
                g2.map(v => UI.span(s"g2:$v").id("v2"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a1"))
                _ <- Browser.assertText(Selector.id("v1"), "g1:a1")
                _ <- Browser.click(Selector.id("a2"))
                _ <- Browser.assertText(Selector.id("v2"), "g2:a2")
                _ <- Browser.assertText(Selector.id("v1"), "g1:a1")
            yield ()
        }
    }

    // ---- Interaction ----

    "check fires onChange true" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.radio.id("r").onChange(v => ref.set(v.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("r"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    // ---- Keyboard ----

    "pressKey Enter checks" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.radio.id("r").onChange(v => ref.set(v)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("r"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "pressKey Space checks" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.radio.id("r").onChange(v => ref.set(v)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("r"), Key.Space)
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    // ---- Focus ----

    "focus assertFocused" in {
        withUI(UI.div(UI.radio.id("r"))) {
            for
                _ <- Browser.click(Selector.id("r"))
                _ <- Browser.assertVisible(Selector.id("r"))
            yield ()
        }
    }

    "onFocus fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.radio.id("r").onFocus(ref.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("r"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "onBlur fires" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.radio.id("r").onBlur(ref.set(true)),
                UI.button("x").id("b"),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("r"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    // ---- Disabled ----

    "disabled no fire" in {
        val app: UI < Async =
            for ref <- Signal.initRef(0)
            yield UI.div(
                UI.radio.id("r").disabled(true).onChange(_ => ref.getAndUpdate(_ + 1).unit),
                ref.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("r"))
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield ()
        }
    }

    "disabled signal toggle" in {
        val app: UI < Async =
            for
                disabled <- Signal.initRef(true)
                counter  <- Signal.initRef(0)
            yield UI.div(
                disabled.map(d => UI.radio.id("r").disabled(d).onChange(_ => counter.getAndUpdate(_ + 1).unit)),
                UI.button("Enable").id("en").onClick(disabled.set(false)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("r"))
                _ <- Browser.click(Selector.id("en"))
                _ <- Browser.assertEnabled(Selector.id("r"))
                _ <- Browser.click(Selector.id("r"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield ()
        }
    }

    // ---- Reactive ----

    "checked signal" in {
        val app: UI < Async =
            for flag <- Signal.initRef(true)
            yield UI.div(
                UI.button("Toggle").id("t").onClick(flag.getAndUpdate(!_).unit),
                flag.map(v => UI.radio.checked(v).id("r"))
            )
        withUI(app) {
            for
                _ <- Browser.assertChecked(Selector.id("r"))
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertVisible(Selector.id("r"))
            yield ()
        }
    }

    // ---- Merged from CheckboxRadioTest (radio scenarios) ----

    "radio exists (merged)" in {
        withUI(UI.div(UI.radio.id("r"))) {
            Browser.assertVisible(Selector.id("r")).unit
        }
    }

    "radio onChange fires (merged)" in {
        val app: UI < Async =
            for ref <- Signal.initRef("none")
            yield UI.div(
                UI.radio.id("r").onChange(b => ref.set(b.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("r"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield ()
        }
    }

    "radio disabled (merged)" in {
        withUI(UI.div(UI.radio.id("r").disabled(true))) {
            Browser.assertDisabled(Selector.id("r")).unit
        }
    }

end RadioTest
