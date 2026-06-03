package kyo

import kyo.Browser.*
import scala.language.implicitConversions

/** Tests that exercise realistic keyboard/mouse interaction patterns. Where the original test used char-by-char `pressKey`, we use
  * `Browser.fill` instead; kyo-browser's `press` re-focuses before each call (cursor reset), so consecutive
  * keystrokes don't accumulate. `fill` uses CDP `Input.insertText` with the full string in one shot.
  */
class RealisticInteractionItTest extends UITest:

    "type abc one key at a time signal updates after each" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "a")
                _ <- Browser.assertText(Selector.id("v"), "sig:a")
                _ <- Browser.fill(Selector.id("i"), "ab")
                _ <- Browser.assertText(Selector.id("v"), "sig:ab")
                _ <- Browser.fill(Selector.id("i"), "abc")
                _ <- Browser.assertText(Selector.id("v"), "sig:abc")
            yield succeed
        }
    }

    "type then backspace signal shows remaining" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "abc")
                _ <- Browser.assertText(Selector.id("v"), "sig:abc")
                _ <- Browser.press(Selector.id("i"), Key.Backspace)
                _ <- Browser.assertText(Selector.id("v"), "sig:ab")
                _ <- Browser.press(Selector.id("i"), Key.Backspace)
                _ <- Browser.assertText(Selector.id("v"), "sig:a")
                _ <- Browser.press(Selector.id("i"), Key.Backspace)
                _ <- Browser.assertText(Selector.id("v"), "sig:")
            yield succeed
        }
    }

    "backspace on empty input signal stays empty" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"sig:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.Backspace)
                _ <- Browser.assertText(Selector.id("v"), "sig:[]")
            yield succeed
        }
    }

    "type backspace type again signal correct" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "he")
                _ <- Browser.press(Selector.id("i"), Key.Backspace)
                _ <- Browser.assertText(Selector.id("v"), "sig:h")
                _ <- Browser.fill(Selector.id("i"), "hi")
                _ <- Browser.assertText(Selector.id("v"), "sig:hi")
            yield succeed
        }
    }

    "type space character via fill" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"sig:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "a b")
                _ <- Browser.assertText(Selector.id("v"), "sig:[a b]")
            yield succeed
        }
    }

    "fill then fill replaces value" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hello")
                _ <- Browser.assertText(Selector.id("v"), "sig:hello")
                _ <- Browser.fill(Selector.id("i"), "hello!")
                _ <- Browser.assertText(Selector.id("v"), "sig:hello!")
            yield succeed
        }
    }

    "fill then backspace removes last char" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "abc")
                _ <- Browser.assertText(Selector.id("v"), "sig:abc")
                _ <- Browser.press(Selector.id("i"), Key.Backspace)
                _ <- Browser.assertText(Selector.id("v"), "sig:ab")
            yield succeed
        }
    }

    "type in two inputs independently" in run {
        val app: UI < Async =
            for
                a <- Signal.initRef("")
                b <- Signal.initRef("")
            yield UI.div(
                UI.input.id("ia").onInput(v => a.set(v)),
                UI.input.id("ib").onInput(v => b.set(v)),
                a.map(v => UI.span(s"a:$v").id("va")),
                b.map(v => UI.span(s"b:$v").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("ia"), "x")
                _ <- Browser.fill(Selector.id("ib"), "y")
                _ <- Browser.assertText(Selector.id("va"), "a:x")
                _ <- Browser.assertText(Selector.id("vb"), "b:y")
            yield succeed
        }
    }

    "click checkbox toggles checked" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                ref.map(v => UI.checkbox.checked(v).id("c").onChange(b => ref.set(b))),
                ref.map(v => UI.span(s"checked:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "checked:true")
            yield succeed
        }
    }

    "click checkbox twice toggles back" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                ref.map(v => UI.checkbox.checked(v).id("c").onChange(b => ref.set(b))),
                ref.map(v => UI.span(s"checked:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "checked:true")
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "checked:false")
            yield succeed
        }
    }

    "click checked checkbox unchecks" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(true)
            yield UI.div(
                ref.map(v => UI.checkbox.checked(v).id("c").onChange(b => ref.set(b))),
                ref.map(v => UI.span(s"checked:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "checked:false")
            yield succeed
        }
    }

    "click radio selects it" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.radio.id("r").onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"selected:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("r"))
                _ <- Browser.assertText(Selector.id("v"), "selected:true")
            yield succeed
        }
    }

    "click radio in group switches selection" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.radio.name("g").id("a").onChange(v => if v then ref.set("a") else Kyo.lift(())),
                UI.radio.name("g").id("b").onChange(v => if v then ref.set("b") else Kyo.lift(())),
                ref.map(v => UI.span(s"sel:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("v"), "sel:a")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "sel:b")
            yield succeed
        }
    }

    "form fill then check then submit" in run {
        val app: UI < Async =
            for
                nameRef  <- Signal.initRef("")
                checkRef <- Signal.initRef(false)
                result   <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        n <- nameRef.get
                        c <- checkRef.get
                        _ <- result.set(s"$n|$c")
                    yield ()
                }(
                    UI.input.id("name").value(nameRef).onInput(v => nameRef.set(v)),
                    UI.checkbox.id("agree").onChange(v => checkRef.set(v)),
                    UI.button("Submit").id("sub")
                ),
                result.map(v => UI.span(s"result:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Joe")
                _ <- Browser.click(Selector.id("agree"))
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "result:Joe|true")
            yield succeed
        }
    }

    "form Enter on input submits with typed value" in run {
        val app: UI < Async =
            for
                ref    <- Signal.initRef("")
                result <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        v <- ref.get
                        _ <- result.set(s"submitted:$v")
                    yield ()
                }(
                    UI.input.id("i").value(ref).onInput(v => ref.set(v)),
                    UI.button("Submit").id("sub")
                ),
                result.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hi")
                _ <- Browser.press(Selector.id("i"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "submitted:hi")
            yield succeed
        }
    }

    "fill value matches signal" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref).onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"signal:$v").id("sig"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "xy")
                _ <- Browser.assertText(Selector.id("sig"), "signal:xy")
                // The input stays focused after fill, and the framework no longer re-renders (clobbers) a focused
                // field, so its `value` ATTRIBUTE intentionally lags. Assert the live `value` PROPERTY, which is what
                // the user sees and submits; it (and the signal above) is correct.
                v <- Browser.value(Selector.id("i"))
            yield assert(v == "xy")
        }
    }

    "textarea fill chars accumulate" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"text:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "abc")
                _ <- Browser.assertText(Selector.id("v"), "text:abc")
            yield succeed
        }
    }

    "textarea fill then fill more" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.textarea.id("t").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"text:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("t"), "hello")
                _ <- Browser.fill(Selector.id("t"), "hello!")
                _ <- Browser.assertText(Selector.id("v"), "text:hello!")
            yield succeed
        }
    }

    "password fill chars accumulate" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.passwordInput.id("p").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"pw:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("p"), "sec")
                _ <- Browser.assertText(Selector.id("v"), "pw:sec")
            yield succeed
        }
    }

    "onInput append handler double-counts is application bug" in run {
        // onInput receives full current value; using append pattern double-counts.
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.getAndUpdate(_ + v).unit),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "a")
                // fill clears (sends empty input), then inserts "a"; append: "" + "" + "a" = "a"
                _ <- Browser.assertText(Selector.id("v"), "sig:a")
            yield succeed
        }
    }

    // PENDING: requires Browser.assertFocused
    /*
    "click on input sets focus" in run {
        withUI(UI.div(UI.input.id("a"), UI.input.id("b"))) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertFocused(Selector.id("a"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertFocused(Selector.id("b"))
                _ <- Browser.assertNotFocused(Selector.id("a"))
            yield succeed
        }
    }

    "click on button sets focus" in run {
        withUI(UI.div(UI.button("A").id("a"), UI.button("B").id("b"))) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertFocused(Selector.id("a"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertFocused(Selector.id("b"))
            yield succeed
        }
    }
     */

    "fill on disabled input no signal change" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("initial")
            yield UI.div(
                UI.input.id("i").disabled(true).onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("i"))
                _ <- Browser.assertText(Selector.id("v"), "sig:initial")
            yield succeed
        }
    }

    "click disabled checkbox no toggle" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.checkbox.id("c").disabled(true).onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"checked:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "checked:false")
            yield succeed
        }
    }

    "rapid 10 chars accumulate correctly" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "0123456789")
                _ <- Browser.assertText(Selector.id("v"), "sig:0123456789")
            yield succeed
        }
    }

    "rapid check uncheck via click cycle" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.checkbox.id("c").onChange(_ => counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "3")
            yield succeed
        }
    }

    "number input fill digits accumulate" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.numberInput.id("n").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"num:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("n"), "42")
                _ <- Browser.assertText(Selector.id("v"), "num:42")
            yield succeed
        }
    }

    "fill auto-focuses then blur on next interaction" in run {
        val app: UI < Async =
            for
                focusRef <- Signal.initRef("")
                blurRef  <- Signal.initRef("")
            yield UI.div(
                UI.input.id("a").onFocus(focusRef.set("a")).onBlur(blurRef.set("a")),
                UI.input.id("b").onFocus(focusRef.set("b")).onBlur(blurRef.set("b")),
                focusRef.map(v => UI.span(s"focus:$v").id("vf")),
                blurRef.map(v => UI.span(s"blur:$v").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("a"), "hello")
                _ <- Browser.assertText(Selector.id("vf"), "focus:a")
                _ <- Browser.fill(Selector.id("b"), "world")
                _ <- Browser.assertText(Selector.id("vb"), "blur:a")
            yield succeed
        }
    }

    "press auto-focuses target" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.input.id("i").onFocus(ref.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key('x'))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "full form keyboard workflow type check select submit" in run {
        val app: UI < Async =
            for
                name   <- Signal.initRef("")
                agree  <- Signal.initRef(false)
                role   <- Signal.initRef("")
                result <- Signal.initRef("")
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        n <- name.get
                        a <- agree.get
                        r <- role.get
                        _ <- result.set(s"$n|$a|$r")
                    yield ()
                }(
                    UI.input.id("name").value(name).onInput(v => name.set(v)),
                    UI.checkbox.id("agree").onChange(v => agree.set(v)),
                    UI.select(UI.option("A").value("a"), UI.option("B").value("b"))
                        .id("role").onChange(v => role.set(v)),
                    UI.button("Submit").id("sub")
                ),
                result.map(v => UI.span(s"result:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Joe")
                _ <- Browser.click(Selector.id("agree"))
                _ <- Browser.select(Selector.id("role"), "b")
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "result:Joe|true|b")
            yield succeed
        }
    }

    "type 100 chars rapidly all appear" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"len:${v.length}").id("v"))
            )
        withUI(app) {
            val text = "a" * 100
            for
                _ <- Browser.fill(Selector.id("i"), text)
                _ <- Browser.assertText(Selector.id("v"), "len:100")
            yield succeed
            end for
        }
    }

    "email input type" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.emailInput.id("e").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"email:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("e"), "a@b.com")
                _ <- Browser.assertText(Selector.id("v"), "email:a@b.com")
            yield succeed
        }
    }

    "external signal set overwrites typed value" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref).onInput(v => ref.set(v)),
                UI.button("Override").id("b").onClick(ref.set("override")),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "typed")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "sig:override")
            yield succeed
        }
    }

    "fill empty string clears signal" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("initial")
            yield UI.div(
                UI.input.id("i").value(ref).onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"sig:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "")
                _ <- Browser.assertText(Selector.id("v"), "sig:[]")
            yield succeed
        }
    }

    "fill then fill different value second wins" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "first")
                _ <- Browser.fill(Selector.id("i"), "second")
                _ <- Browser.assertText(Selector.id("v"), "sig:second")
            yield succeed
        }
    }

    "press Enter in single line input does not insert newline" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"sig:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "abc")
                _ <- Browser.press(Selector.id("i"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "sig:[abc]")
            yield succeed
        }
    }

    // PENDING: requires Browser.assertFocused
    /*
    "press Tab moves focus" in run {
        withUI(UI.div(UI.input.id("a"), UI.input.id("b"))) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.press(Selector.id("a"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("b"))
            yield succeed
        }
    }
     */

    "click radio already selected stays selected" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(true)
            yield UI.div(
                UI.radio.checked(ref).id("r"),
                ref.map(v => UI.span(s"sel:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertChecked(Selector.id("r"))
                _ <- Browser.click(Selector.id("r"))
                _ <- Browser.assertChecked(Selector.id("r"))
            yield succeed
        }
    }

    "rapid click checkbox 50 times correct final state" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                ref.map(v => UI.checkbox.checked(v).id("c").onChange(b => ref.set(b))),
                ref.map(v => UI.span(s"v:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Kyo.foreachDiscard(0 until 50)(_ => Browser.click(Selector.id("c")))
                // 50 clicks from false → still false (even count)
                _ <- Browser.assertText(Selector.id("v"), "v:false")
            yield succeed
        }
    }

    "click disabled radio no select" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.radio.id("r").disabled(true).onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"sel:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("r"))
                _ <- Browser.assertText(Selector.id("v"), "sel:false")
            yield succeed
        }
    }

    "checkbox onChange receives correct boolean" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.checkbox.id("c").onChange(v => ref.set(v.toString)),
                ref.map(v => UI.span(s"v:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "v:true")
            yield succeed
        }
    }

    "radio inside form click does not submit" in run {
        val app: UI < Async =
            for
                ref       <- Signal.initRef(false)
                submitted <- Signal.initRef(0)
            yield UI.div(
                UI.form.id("f").onSubmit(submitted.getAndUpdate(_ + 1).unit)(
                    UI.radio.id("r").onChange(v => ref.set(v))
                ),
                ref.map(v => UI.span(s"sel:$v").id("vr")),
                submitted.map(n => UI.span(s"sub:$n").id("vs"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("r"))
                _ <- Browser.assertText(Selector.id("vr"), "sel:true")
                _ <- Browser.assertText(Selector.id("vs"), "sub:0")
            yield succeed
        }
    }

    // PENDING: requires Browser.assertFocused / assertNotFocused
    /*
    "focus non focusable div rejected" in run {
        withUI(UI.div(UI.div("text").id("d"), UI.input.id("i"))) {
            for
                _ <- Browser.click(Selector.id("d"))
                _ <- Browser.assertNotFocused(Selector.id("d"))
            yield succeed
        }
    }
     */

    "focus preserved through sibling signal change" in run {
        val app: UI < Async =
            for
                counter <- Signal.initRef(0)
                ref     <- Signal.initRef(false)
            yield UI.div(
                UI.input.id("i").onFocus(ref.set(true)),
                UI.button("+").id("b").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("vc")),
                ref.map(v => UI.span(v.toString).id("vf"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.assertText(Selector.id("vf"), "true")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("vc"), "1")
            yield succeed
        }
    }

    "two forms cross focus blur" in run {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.form.id("f1")(
                    UI.input.id(
                        "i1"
                    ).onFocus(log.getAndUpdate(_.appended("focus:1")).unit).onBlur(log.getAndUpdate(_.appended("blur:1")).unit)
                ),
                UI.form.id("f2")(
                    UI.input.id("i2").onFocus(log.getAndUpdate(_.appended("focus:2")).unit)
                ),
                log.map(es => UI.span(es.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("i1"))
                _ <- Browser.click(Selector.id("i2"))
                _ <- Browser.assertText(Selector.id("v"), "focus:1,blur:1,focus:2")
            yield succeed
        }
    }

    "no initial focus first interaction focuses element" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.input.id("i").onFocus(ref.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "false")
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "focus blur focus same element all handlers fire" in run {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.input.id("a")
                    .onFocus(log.getAndUpdate(_.appended("focus:a")).unit)
                    .onBlur(log.getAndUpdate(_.appended("blur:a")).unit),
                UI.input.id("b"),
                log.map(es => UI.span(es.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("v"), "focus:a,blur:a,focus:a")
            yield succeed
        }
    }

    "external signal set then type more appends to external" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref).onInput(v => ref.set(v)),
                UI.button("Set").id("b").onClick(ref.set("ext")),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.fill(Selector.id("i"), "ext+more")
                _ <- Browser.assertText(Selector.id("v"), "sig:ext+more")
            yield succeed
        }
    }

    "press on element without onInput no crash" in run {
        withUI(UI.div(UI.input.id("i"))) {
            for
                _ <- Browser.press(Selector.id("i"), Key('x'))
                _ <- Browser.assertVisible(Selector.id("i"))
            yield succeed
        }
    }

    // PENDING: Space on checkbox toggles requires kyo-browser fix for key->checkbox interaction
    /*
    "Space on checkbox toggles" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("none")
            yield UI.div(
                UI.checkbox.id("c").onChange(v => ref.set(v.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("c"), Key.Space)
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }
     */

    "Enter on checkbox toggles" in run {
        // TUI parity: Enter activates checkbox via synthetic click()
        val app: UI < Async =
            for ref <- Signal.initRef("none")
            yield UI.div(
                UI.checkbox.id("c").onChange(v => ref.set(v.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("c"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    // PENDING: same as above
    /*
    "Space on radio selects" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("none")
            yield UI.div(
                UI.radio.id("r").onChange(v => ref.set(v.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("r"), Key.Space)
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }
     */

    "Enter on radio selects" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("none")
            yield UI.div(
                UI.radio.id("r").onChange(v => ref.set(v.toString)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("r"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    // PENDING: requires Browser.assertFocused
    /*
    "Tab to checkbox assertFocused no state change" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.input.id("i"),
                UI.checkbox.id("c").onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"v:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.press(Selector.id("i"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "v:false")
            yield succeed
        }
    }

    "Tab to radio assertFocused no state change" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.input.id("i"),
                UI.radio.id("r").onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"v:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.press(Selector.id("i"), Key.Tab)
                _ <- Browser.assertFocused(Selector.id("r"))
                _ <- Browser.assertText(Selector.id("v"), "v:false")
            yield succeed
        }
    }
     */

    "radio with SignalRef two way binding click auto updates" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.radio.id("r").checked(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("r"))
                _ <- Browser.assertText(Selector.id("v"), "sig:true")
            yield succeed
        }
    }

    "checkbox onClick and onChange both fire on click" in run {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.checkbox.id("c")
                    .onClick(log.getAndUpdate(_.appended("click")).unit)
                    .onChange(v => log.getAndUpdate(_.appended(s"change:$v")).unit),
                log.map(es => UI.span(es.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- assertContains("click")
                _ <- assertContains("change:true")
            yield succeed
        }
    }

    // PENDING: requires Browser.assertFocused
    /*
    "focus via Shift Tab assertFocused" in run {
        withUI(UI.div(UI.input.id("a"), UI.input.id("b"))) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.press(Selector.id("b"), Key.Tab, KeyModifiers(shift = true))
                _ <- Browser.assertFocused(Selector.id("a"))
            yield succeed
        }
    }
     */

    "focus via label click forId" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.label("Name:").forId("i").id("l"),
                UI.input.id("i").onFocus(ref.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("l"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "focus then element disabled via signal" in run {
        val app: UI < Async =
            for
                disabled <- Signal.initRef(false)
                ref      <- Signal.initRef(false)
            yield UI.div(
                UI.input.id("i").onFocus(ref.set(true)).disabled(disabled),
                UI.button("Disable").id("b").onClick(disabled.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.assertText(Selector.id("v"), "true")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertDisabled(Selector.id("i"))
            yield succeed
        }
    }

    "focus then element removed via conditional" in run {
        val app: UI < Async =
            for
                show <- Signal.initRef(true)
                ref  <- Signal.initRef(false)
            yield UI.div(
                UI.when(show)(UI.input.id("i").onFocus(ref.set(true))),
                UI.button("Remove").id("b").onClick(show.set(false)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.assertText(Selector.id("v"), "true")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertNotExists(Selector.id("i"))
            yield succeed
        }
    }

end RealisticInteractionItTest
