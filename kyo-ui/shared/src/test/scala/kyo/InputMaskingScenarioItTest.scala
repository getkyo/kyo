package kyo

import kyo.Browser.*

/** Declarative client-local input filtering (`inputFilter`) and masking (`inputMask`) in a real browser.
  *
  * Drives the server-push transport (withUI -> runHandlers -> HtmlRenderer.clientJs) in real Chrome. Each field
  * reflects its constrained value into a span through `onInput` or a bound `SignalRef`, so the assertions read what
  * the SERVER received, which is the point of the feature. The DomBackend SPA mirror has no browser harness; the
  * character-level logic it shares is covered by `kyo.internal.InputMaskingTest`, and the JavaScript mirror driven
  * here is held to the same case table by `InputMaskingJsParityTest`.
  *
  * What only a browser can show, and is therefore covered here rather than in the unit suites: paste and drop,
  * typing over a selection, caret placement, mask capacity, the compositionend correction, and a `type=email` field,
  * whose caret cannot be moved at all.
  */
class InputMaskingScenarioItTest extends UITest:

    "inputFilter emits data-kyo-filter" in {
        withUI(UI.div(UI.input.id("i").inputFilter(UI.InputFilter.Digits))) {
            Browser.assertAttribute(Selector.id("i"), "data-kyo-filter", "digits").unit
        }
    }

    "inputMask emits data-kyo-mask" in {
        withUI(UI.div(UI.input.id("i").inputMask("(999) 999-9999"))) {
            Browser.assertAttribute(Selector.id("i"), "data-kyo-mask", "(999) 999-9999").unit
        }
    }

    "filter=digits drops non-digits as they are typed" in {
        val app: UI < Async =
            for got <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").inputFilter(UI.InputFilter.Digits).onInput(v => got.set(v)),
                got.map(v => UI.span(v).id("out"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key('a'))
                _ <- Browser.press(Selector.id("i"), Key('1'))
                _ <- Browser.press(Selector.id("i"), Key('b'))
                _ <- Browser.press(Selector.id("i"), Key('2'))
                _ <- Browser.assertText(Selector.id("out"), "12")
            yield ()
        }
    }

    "mask auto-inserts literals as digits are typed" in {
        val app: UI < Async =
            for got <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").inputMask("(999) 999").onInput(v => got.set(v)),
                got.map(v => UI.span(v).id("out"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key('1'))
                _ <- Browser.press(Selector.id("i"), Key('2'))
                _ <- Browser.press(Selector.id("i"), Key('3'))
                _ <- Browser.press(Selector.id("i"), Key('4'))
                _ <- Browser.press(Selector.id("i"), Key('5'))
                _ <- Browser.press(Selector.id("i"), Key('6'))
                _ <- Browser.assertText(Selector.id("out"), "(123) 456")
            yield ()
        }
    }

    "mask backspace deletes the digit before the literal and collapses trailing literals" in {
        val app: UI < Async =
            for got <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").inputMask("(999) 999").onInput(v => got.set(v)),
                got.map(v => UI.span(v).id("out"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key('1'))
                _ <- Browser.press(Selector.id("i"), Key('2'))
                _ <- Browser.press(Selector.id("i"), Key('3'))
                _ <- Browser.press(Selector.id("i"), Key('4'))
                _ <- Browser.assertText(Selector.id("out"), "(123) 4")
                _ <- Browser.press(Selector.id("i"), Key.Backspace)
                _ <- Browser.assertText(Selector.id("out"), "(123")
            yield ()
        }
    }

    "a value the server sets reaches the field masked" in {
        // The gap a client-side beforeinput listener cannot close: nobody typed, so no event fires and the field
        // would otherwise show the raw value. The mirror span is asserted first because it retries, which
        // synchronizes on the update having arrived before the value is read.
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").inputMask("(999) 999").value(ref),
                UI.button("set").id("b").onClick(ref.set("1234567890")),
                ref.map(v => UI.span(s"sig:$v").id("out"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("out"), "sig:1234567890")
                v <- Browser.value(Selector.id("i"))
            yield assert(v == "(123) 456")
        }
    }

    // A composition (IME, dead key, mobile autocorrect) is let through rather than cancelled, because
    // preventDefault on insertCompositionText aborts the composition instead of filtering it. The finished text is
    // corrected on compositionend. CDP cannot drive a real IME, so these set the composed text and fire the event
    // directly; what they pin is the handler's contract, that the field and the server end up constrained.
    private def composeInto(id: String, text: String)(using Frame) =
        Browser.evalDiscard(
            s"""(function(){var t=document.getElementById("$id");t.value="$text";
               |t.dispatchEvent(new CompositionEvent("compositionend",{bubbles:true}));})()""".stripMargin
        )

    "a finished composition is filtered" in {
        val app: UI < Async =
            for got <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").inputFilter(UI.InputFilter.Digits).onInput(v => got.set(v)),
                got.map(v => UI.span(s"sig:$v").id("out"))
            )
        withUI(app) {
            for
                _ <- composeInto("i", "a1b2")
                _ <- Browser.assertText(Selector.id("out"), "sig:12")
                v <- Browser.value(Selector.id("i"))
            yield assert(v == "12")
        }
    }

    "a finished composition is formatted against the mask" in {
        val app: UI < Async =
            for got <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").inputMask("(999) 999").onInput(v => got.set(v)),
                got.map(v => UI.span(s"sig:$v").id("out"))
            )
        withUI(app) {
            for
                _ <- composeInto("i", "123456")
                _ <- Browser.assertText(Selector.id("out"), "sig:(123) 456")
                v <- Browser.value(Selector.id("i"))
            yield assert(v == "(123) 456")
        }
    }

    // Paste, drop and typing over a selection reach the handler as beforeinput events CDP cannot synthesize from
    // key presses, so they are dispatched directly. The value and caret they leave behind are what is asserted.
    private def pasteInto(id: String, text: String)(using Frame) =
        Browser.evalDiscard(
            s"""(function(){var t=document.getElementById("$id");t.focus();
               |var dt=new DataTransfer();dt.setData("text","$text");
               |t.dispatchEvent(new InputEvent("beforeinput",
               |  {bubbles:true,cancelable:true,inputType:"insertFromPaste",dataTransfer:dt}));})()""".stripMargin
        )

    private def typeOverSelection(id: String, current: String, start: Int, end: Int, text: String)(using Frame) =
        Browser.evalDiscard(
            s"""(function(){var t=document.getElementById("$id");t.focus();
               |t.value="$current";t.setSelectionRange($start,$end);
               |t.dispatchEvent(new InputEvent("beforeinput",
               |  {bubbles:true,cancelable:true,inputType:"insertText",data:"$text"}));})()""".stripMargin
        )

    private def caretAt(id: String)(using Frame) =
        Browser.evalInt(s"""document.getElementById("$id").selectionStart""")

    "pasted text is filtered and the caret lands after the insertion" in {
        val app: UI < Async =
            for got <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").inputFilter(UI.InputFilter.Digits).onInput(v => got.set(v)),
                got.map(v => UI.span(s"sig:$v").id("out"))
            )
        withUI(app) {
            for
                _ <- pasteInto("i", "a1b2c3")
                _ <- Browser.assertText(Selector.id("out"), "sig:123")
                v <- Browser.value(Selector.id("i"))
                c <- caretAt("i")
            yield
                assert(v == "123")
                assert(c == 3)
        }
    }

    "typing over a selection replaces it with the filtered text and places the caret after it" in {
        val app: UI < Async =
            for got <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").inputFilter(UI.InputFilter.Digits).onInput(v => got.set(v)),
                got.map(v => UI.span(s"sig:$v").id("out"))
            )
        withUI(app) {
            for
                // "12345" with "23" selected, typing "a9": the letter is dropped and the digit replaces the selection.
                _ <- typeOverSelection("i", "12345", 1, 3, "a9")
                _ <- Browser.assertText(Selector.id("out"), "sig:1945")
                v <- Browser.value(Selector.id("i"))
                c <- caretAt("i")
            yield
                assert(v == "1945")
                assert(c == 2)
        }
    }

    "a keystroke past the mask capacity is dropped" in {
        val app: UI < Async =
            for got <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").inputMask("(999) 999").onInput(v => got.set(v)),
                got.map(v => UI.span(v).id("out"))
            )
        withUI(app) {
            for
                _ <- Kyo.foreachDiscard("123456".toList)(c => Browser.press(Selector.id("i"), Key(c)))
                _ <- Browser.assertText(Selector.id("out"), "(123) 456")
                _ <- Browser.press(Selector.id("i"), Key('7'))
                _ <- Browser.assertText(Selector.id("out"), "(123) 456")
                v <- Browser.value(Selector.id("i"))
            yield assert(v == "(123) 456")
        }
    }

    "a filter drives a two-way binding, so the ref only ever sees filtered text" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").inputFilter(UI.InputFilter.Decimal).value(ref),
                ref.map(v => UI.span(s"sig:$v").id("out"))
            )
        withUI(app) {
            for
                _ <- Kyo.foreachDiscard("1a.5b.2".toList)(c => Browser.press(Selector.id("i"), Key(c)))
                _ <- Browser.assertText(Selector.id("out"), "sig:1.52")
                v <- Browser.value(Selector.id("i"))
            yield assert(v == "1.52")
        }
    }

    "a filter works on an email input, whose caret cannot be moved" in {
        // type=email has no text selection: reading selectionStart yields null and setSelectionRange throws
        // InvalidStateError. Both are tolerated deliberately, and the second keystroke proves the client script
        // survived the first, which is what an unhandled throw out of a beforeinput listener would have ended.
        val app: UI < Async =
            for got <- Signal.initRef("")
            yield UI.div(
                UI.emailInput.id("i").inputFilter(UI.InputFilter.Digits).onInput(v => got.set(v)),
                got.map(v => UI.span(s"sig:$v").id("out"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key('a'))
                _ <- Browser.press(Selector.id("i"), Key('1'))
                _ <- Browser.assertText(Selector.id("out"), "sig:1")
                _ <- Browser.press(Selector.id("i"), Key('2'))
                _ <- Browser.assertText(Selector.id("out"), "sig:12")
                v <- Browser.value(Selector.id("i"))
            yield assert(v == "12")
        }
    }

end InputMaskingScenarioItTest
