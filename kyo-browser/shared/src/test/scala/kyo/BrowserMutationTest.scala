package kyo

import kyo.Browser.*
import kyo.BrowserElementNotActionableException.Reason
import kyo.internal.EditableInput
import kyo.kernel.ArrowEffect
import scala.language.implicitConversions

class BrowserMutationTest extends BrowserTest:

    override def timeout = 90.seconds

    // ---- click ----

    "click button triggers handler" in {
        withBrowser {
            onPage(
                "<button id='btn' onclick='document.getElementById(\"result\").textContent=\"clicked\"'>Click Me</button><div id='result'>not clicked</div>"
            ) {
                Browser.click(Browser.Selector.css("#btn")).andThen {
                    Browser.text(Browser.Selector.css("#result")).map { t =>
                        assert(t == "clicked", s"Expected 'clicked' but got '$t'")
                    }
                }
            }
        }
    }

    "single click dispatches exactly one DOM click event" in {
        withBrowser {
            onPage("""<!DOCTYPE html><html><body>
                <button id="btn">click me</button>
                <script>
                    window.__clicks = 0;
                    document.body.addEventListener('click', () => { window.__clicks++; });
                </script>
            </body></html>""") {
                for
                    _ <- Browser.click(Browser.Selector.css("#btn"))
                    _ <- evalAssert("window.__clicks", "1")
                yield ()
            }
        }
    }

    "click on a missing selector fails with BrowserElementNotActionableException(NotAttached)" in {
        withBrowser {
            onPage("<div>Nothing clickable</div>") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.css("#nonexistent"))
                    }.map { result =>
                        result match
                            case Result.Failure(BrowserElementNotActionableException(_, Reason.NotAttached)) =>
                                ()
                            case other =>
                                fail(s"Expected BrowserElementNotActionableException(NotAttached) for missing selector but got $other")
                    }
                }
            }
        }
    }

    "click element appearing after delay retries" in {
        val html = """<div id="container"></div><div id="result">waiting</div>""" +
            """<script>setTimeout(function(){var b=document.createElement('button');b.id='delayed';""" +
            """b.onclick=function(){document.getElementById('result').textContent='clicked'};""" +
            """b.textContent='Go';document.getElementById('container').appendChild(b)},200)</script>"""
        withBrowser {
            onPage(html) {
                Browser.click(Browser.Selector.css("#delayed")).andThen {
                    Browser.text(Browser.Selector.css("#result")).map { t =>
                        assert(t == "clicked", s"Expected 'clicked' but got '$t'")
                    }
                }
            }
        }
    }

    // ---- fill ----

    "fill empty input sets value" in {
        withBrowser {
            onPage("<input id='name' type='text' value='' />") {
                Browser.fill(Browser.Selector.css("#name"), "Alice").andThen {
                    Browser.eval("document.getElementById('name').value").map { v =>
                        assert(v == "Alice", s"Expected 'Alice' but got '$v'")
                    }
                }
            }
        }
    }

    "fill replaces existing text" in {
        withBrowser {
            onPage("<input id='name' type='text' value='Bob' />") {
                Browser.fill(Browser.Selector.css("#name"), "Alice").andThen {
                    Browser.eval("document.getElementById('name').value").map { v =>
                        assert(v == "Alice", s"Expected 'Alice' but got '$v'")
                    }
                }
            }
        }
    }

    "fill with unicode" in {
        withBrowser {
            onPage("<input id='name' type='text' value='' />") {
                Browser.fill(Browser.Selector.css("#name"), "caf\u00e9").andThen {
                    Browser.eval("document.getElementById('name').value").map { v =>
                        assert(v == "caf\u00e9", s"Expected 'caf\u00e9' but got '$v'")
                    }
                }
            }
        }
    }

    // ---- fill character preservation ----
    //
    // `fill` builds a JS string literal from the caller's text, so any character that alters the JS parse (backslash, quote, newline,
    // CR, tab) must be escaped. The escape contract covers all control characters and mixed/unicode/emoji content.
    // These tests round-trip the input through the live DOM to lock preservation.

    "fill preserves backslash" in {
        withBrowser {
            onPage("<input id='i' type='text' />") {
                Browser.fill(Browser.Selector.css("#i"), "a\\b").andThen {
                    Browser.eval("document.getElementById('i').value").map { v =>
                        assert(v == "a\\b", s"Expected 'a\\\\b' but got '$v'")
                    }
                }
            }
        }
    }

    "fill preserves single quote" in {
        withBrowser {
            onPage("<input id='i' type='text' />") {
                Browser.fill(Browser.Selector.css("#i"), "can't").andThen {
                    Browser.eval("document.getElementById('i').value").map { v =>
                        assert(v == "can't", s"Expected \"can't\" but got '$v'")
                    }
                }
            }
        }
    }

    "fill preserves double quote" in {
        withBrowser {
            onPage("<input id='i' type='text' />") {
                Browser.fill(Browser.Selector.css("#i"), "he said \"hi\"").andThen {
                    Browser.eval("document.getElementById('i').value").map { v =>
                        assert(v == "he said \"hi\"", s"Expected 'he said \"hi\"' but got '$v'")
                    }
                }
            }
        }
    }

    "fill with carriage return does not cause a JS syntax error" in {
        // The unified JS-fallback path drives the prototype `value` setter, which per HTML spec normalizes
        // `\r` to `\n` for `<textarea>` (the storage normalization happens at set-time, the getter then returns the
        // stored string). What we test here is that the fill does *not* crash with a JS SyntaxError and that the
        // readback honors the browser's normalization.
        withBrowser {
            onPage("<textarea id='t'></textarea>") {
                Browser.fill(Browser.Selector.css("#t"), "a\rb").andThen {
                    Browser.eval("document.getElementById('t').value").map { v =>
                        assert(
                            v == "a\rb" || v == "a\nb",
                            s"Expected 'a\\rb' or browser-normalized 'a\\nb' but got '$v'"
                        )
                    }
                }
            }
        }
    }

    "fill preserves tab character in the inserted text" in {
        withBrowser {
            onPage("<input id='i' type='text' />") {
                Browser.fill(Browser.Selector.css("#i"), "a\tb").andThen {
                    Browser.eval("document.getElementById('i').value").map { v =>
                        assert(v == "a\tb", s"Expected 'a\\tb' but got '$v'")
                    }
                }
            }
        }
    }

    "fill preserves a mix of all escape-sensitive chars" in {
        // The unified JS-fallback path drives the value setter on the element prototype, which per HTML spec normalizes
        // `\r\n` and lone `\r` to `\n` for `<textarea>` / `<input>`.
        // All other escape-sensitive chars (backslash, quotes, tab, embedded `\n`) round-trip exactly.
        withBrowser {
            val text     = "a\\b'c\"d\ne\rf\tg"
            val expected = "a\\b'c\"d\ne\nf\tg"
            onPage("<textarea id='t'></textarea>") {
                Browser.fill(Browser.Selector.css("#t"), text).andThen {
                    Browser.eval("document.getElementById('t').value").map { v =>
                        assert(v == expected, s"Expected mixed-escape roundtrip '$expected' but got '$v'")
                    }
                }
            }
        }
    }

    "fill preserves emoji (supplementary plane)" in {
        withBrowser {
            onPage("<input id='i' type='text' />") {
                Browser.fill(Browser.Selector.css("#i"), "hi \uD83D\uDE00 bye").andThen {
                    Browser.eval("document.getElementById('i').value").map { v =>
                        assert(v == "hi \uD83D\uDE00 bye", s"Expected emoji roundtrip but got '$v'")
                    }
                }
            }
        }
    }

    "fill preserves a JSON-shaped payload with quotes and escapes" in {
        withBrowser {
            val json = """{"name":"don't","value":"a\\b"}"""
            onPage("<input id='i' type='text' />") {
                Browser.fill(Browser.Selector.css("#i"), json).andThen {
                    Browser.eval("document.getElementById('i').value").map { v =>
                        assert(v == json, s"Expected JSON roundtrip but got '$v'")
                    }
                }
            }
        }
    }

    "fill on a textarea preserves multiline content" in {
        withBrowser {
            onPage("<textarea id='t'></textarea>") {
                Browser.fill(Browser.Selector.css("#t"), "first\nsecond\nthird").andThen {
                    Browser.eval("document.getElementById('t').value").map { v =>
                        assert(v == "first\nsecond\nthird", s"Expected multiline but got '$v'")
                    }
                }
            }
        }
    }

    // ── Framework-compatible fill ────────────────────────────────────────────
    //
    // Fill drives text injection through CDP `Input.insertText` + a real `Backspace` keystroke to clear. The tests below validate
    // the framework compatibility goal (React / Vue controlled inputs), the settlement composition (debounced validation), the gate
    // contract (NotFillable), the readback contract (FillDesync on mismatch), and the CDP transport contract (long strings, IME events).

    "fill on a vanilla input sets value" in {
        withBrowser {
            onPage("<input id='i' type='text' />") {
                Browser.fill(Browser.Selector.css("#i"), "hello world").andThen {
                    Browser.eval("document.getElementById('i').value").map { v =>
                        assert(v == "hello world", s"Expected 'hello world' but got '$v'")
                    }
                }
            }
        }
    }

    "fill on vanilla input fires input and change events" in {
        // Install listeners that record each `input` / `change` event. `fill` clears then types, so we expect >=1 of each; the exact
        // count is implementation-dependent (Backspace deletion fires `input`; each `Input.insertText` batch fires at least one `input`
        // and a `change` on blur).
        withBrowser {
            onPage(
                """<input id='i' type='text' />
              |<script>
              |  window.__events = [];
              |  const el = document.getElementById('i');
              |  el.addEventListener('input', () => window.__events.push('input'));
              |  el.addEventListener('change', () => window.__events.push('change'));
              |</script>""".stripMargin
            ) {
                Browser.fill(Browser.Selector.css("#i"), "hi").andThen {
                    Browser.eval("JSON.stringify(window.__events)").map { json =>
                        assert(json.contains("input"), s"Expected 'input' event in $json")
                        assert(json.contains("change"), s"Expected 'change' event in $json")
                    }
                }
            }
        }
    }

    "fill on a React-like controlled input updates via framework state" in {
        // Simulate a React-style controlled input: on every `input` event we commit the DOM value into `window.__reactState.value`; an
        // outer tick re-assigns the DOM value FROM state so the input is truly controlled by state rather than the DOM. For the fill to
        // be seen by the framework, the `input` event must fire, which `Input.insertText` guarantees.
        withBrowser {
            onPage(
                """<input id='i' type='text' value='' />
              |<script>
              |  window.__reactState = { value: '' };
              |  const el = document.getElementById('i');
              |  el.addEventListener('input', () => {
              |    window.__reactState.value = el.value;
              |  });
              |  setInterval(() => { el.value = window.__reactState.value; }, 10);
              |</script>""".stripMargin
            ) {
                Browser.fill(Browser.Selector.css("#i"), "hello").andThen {
                    Browser.eval("window.__reactState.value").map { v =>
                        assert(v == "hello", s"Expected React state 'hello' but got '$v'")
                    }
                }
            }
        }
    }

    "fill on a Vue-like v-model input updates via framework state" in {
        // Vue's `v-model` syncs on `input`. Same shape as the React fixture but we only commit to state on `input`; no re-assignment
        // loop. The DOM value IS the canonical state, but the framework's observable is the state object, so we read that back.
        withBrowser {
            onPage(
                """<input id='i' type='text' value='' />
              |<script>
              |  window.__vueState = { value: '' };
              |  const el = document.getElementById('i');
              |  el.addEventListener('input', () => {
              |    window.__vueState.value = el.value;
              |  });
              |</script>""".stripMargin
            ) {
                Browser.fill(Browser.Selector.css("#i"), "world").andThen {
                    Browser.eval("window.__vueState.value").map { v =>
                        assert(v == "world", s"Expected Vue state 'world' but got '$v'")
                    }
                }
            }
        }
    }

    "fill clears the existing value before typing" in {
        withBrowser {
            onPage("<input id='i' type='text' value='old-value-here' />") {
                Browser.fill(Browser.Selector.css("#i"), "new").andThen {
                    Browser.eval("document.getElementById('i').value").map { v =>
                        assert(v == "new", s"Expected 'new' (old value fully cleared) but got '$v'")
                    }
                }
            }
        }
    }

    "fill triggers a debounced validation that awaits mutation settlement" in {
        // On `input`, schedule a setTimeout that writes to #validation 50ms later. MutationSettlement should have awaited the
        // quiescence window past the setTimeout, so reading #validation immediately after fill returns sees the "valid" text.
        withBrowser {
            onPage(
                """<input id='i' type='text' /><div id='validation'>pending</div>
              |<script>
              |  document.getElementById('i').addEventListener('input', () => {
              |    setTimeout(() => {
              |      document.getElementById('validation').textContent = 'valid';
              |    }, 50);
              |  });
              |</script>""".stripMargin
            ) {
                Browser.fill(Browser.Selector.css("#i"), "x").andThen {
                    Browser.text(Browser.Selector.css("#validation")).map { t =>
                        assert(t == "valid", s"Expected debounced validation 'valid' after settlement but got '$t'")
                    }
                }
            }
        }
    }

    "fill on a non-INPUT target fails with NotActionable(NotFillable)" in {
        val p = page("<div id='d'>not an input</div>")
        withBrowser {
            Browser.goto(p).andThen {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(2))) {
                    Abort.run[BrowserElementException] {
                        Browser.fill(Browser.Selector.css("#d"), "x")
                    }.map { result =>
                        result match
                            case Result.Failure(ex: BrowserElementNotActionableException) =>
                                ex.reason match
                                    case r @ Reason.NotFillable("div") =>
                                        assert(r == Reason.NotFillable("div"), s"expected NotFillable(div) but got $r")
                                    case other => fail(s"Expected NotFillable(\"div\") but got $other")
                            case other =>
                                fail(s"Expected BrowserElementNotActionableException(NotFillable) but got $other")
                        end match
                    }
                }
            }
        }
    }

    "fill with a very long string types all characters (no truncation)" in {
        // 5000 chars exercises the single-shot Input.insertText path well beyond typical form field lengths.
        val long = "abcde" * 1000
        val p    = page("<input id='i' type='text' />")
        withBrowser {
            Browser.goto(p).andThen {
                Browser.fill(Browser.Selector.css("#i"), long).andThen {
                    Browser.eval("document.getElementById('i').value.length").map { lenStr =>
                        assert(lenStr.toInt == long.length, s"Expected length ${long.length} but got $lenStr")
                    }.andThen {
                        Browser.eval("document.getElementById('i').value").map { v =>
                            assert(
                                v == long,
                                s"Expected exact round-trip of long string but got a mismatch (lengths ${v.length} vs ${long.length})"
                            )
                        }
                    }
                }
            }
        }
    }

    "fill value readback fails fast when framework rejects the value" in {
        // Fixture: on every `input` event the framework resets `el.value = ''`. The readback sees the empty string (not `text`), so
        // `FillDesync` fires, the Retry loop exhausts the schedule, and the final abort surfaces as a NotActionable(FillDesync).
        withBrowser {
            onPage(
                """<input id='i' type='text' />
              |<script>
              |  const el = document.getElementById('i');
              |  el.addEventListener('input', () => { el.value = ''; });
              |</script>""".stripMargin
            ) {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(2))) {
                    Abort.run[BrowserElementException] {
                        Browser.fill(Browser.Selector.css("#i"), "zzz")
                    }.map {
                        result =>
                            result match
                                case Result.Failure(ex: BrowserElementNotActionableException) =>
                                    assert(
                                        ex.reason == Reason.FillDesync,
                                        s"Expected reason FillDesync but got ${ex.reason}"
                                    )
                                case other =>
                                    fail(s"Expected BrowserElementNotActionableException(FillDesync) but got $other")
                            end match
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // FillDesync exception message embeds the attempted-vs-observed values (A6)
    //
    // Use case: page-side rewriting (input handler strips characters, framework normalizes, source-pipeline
    // mangled the literal). Without the values in the message, the user can't tell whether the input got there
    // and was rejected, or never got there at all. Embedding "tried '...' but got '...'" makes the diff visible.
    // -------------------------------------------------------------------------

    "FillDesync exception message embeds the attempted and observed values" in {
        withBrowser {
            onPage(
                """<input id='i' type='text' />
              |<script>
              |  const el = document.getElementById('i');
              |  // Framework strips digits; readback will show only the letters.
              |  el.addEventListener('input', () => { el.value = el.value.replace(/[0-9]/g, ''); });
              |</script>""".stripMargin
            ) {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(2))) {
                    Abort.run[BrowserElementException] {
                        Browser.fill(Browser.Selector.css("#i"), "abc123")
                    }.map {
                        case Result.Failure(ex: BrowserElementNotActionableException) =>
                            val msg = ex.getMessage
                            // The FillDesync message includes the attempted value and the readback value.
                            assert(
                                msg.contains("abc123") && msg.contains("abc") && (msg.contains("tried") || msg.contains("attempted")),
                                s"expected the FillDesync message to embed attempted vs observed values for source-side-rewriting diagnosis, got: $msg"
                            )
                        case other => fail(s"Expected BrowserElementNotActionableException(FillDesync) but got $other")
                    }
                }
            }
        }
    }

    "fill round-trips CJK text via the JS-fallback path" in {
        // The unified JS-fallback path does NOT fire `compositionstart` / `compositionend` events for plain text fills.
        // IME-aware frameworks must subscribe to `input` events instead. This test pins the value-preservation contract
        // for non-ASCII input AND verifies that composition events do NOT fire (preventing accidental re-introduction
        // of the synthesized composition pair).
        withBrowser {
            onPage(
                """<input id='i' type='text' />
              |<script>
              |  window.__compStart = 0;
              |  window.__compEnd = 0;
              |  const el = document.getElementById('i');
              |  el.addEventListener('compositionstart', () => window.__compStart++);
              |  el.addEventListener('compositionend', () => window.__compEnd++);
              |</script>""".stripMargin
            ) {
                Browser.fill(Browser.Selector.css("#i"), "\u65e5\u672c\u8a9e").andThen {
                    Browser.eval("document.getElementById('i').value").map { v =>
                        assert(v == "\u65e5\u672c\u8a9e", s"Expected '\u65e5\u672c\u8a9e' but got '$v'")
                    }.andThen {
                        Browser.eval("String(window.__compStart) + '/' + String(window.__compEnd)").map { counts =>
                            assert(
                                counts == "0/0",
                                s"Expected no composition events under the JS-fallback fill path but got $counts"
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Unified JS-fallback fill ─────────────────────────────────────────────
    //
    // The fill implementation uses a single Runtime.evaluate JS call that applies the prototype-`value`-setter trick
    // (React-safe), dispatches exactly one `input` and one `change` event, and leaves the input focused with caret at
    // end. The tests below cover input-type coverage (date/time/number/range/color/textarea) and the event-count +
    // caret contract.

    "fill on input type=date sets ISO date value" in {
        // Input.insertText rejects non-printable characters that the date editor synthesizes; fill must use
        // a keyboard-event path that the date editor accepts.
        withBrowser {
            onPage("<input id='d' type='date' />") {
                Browser.fill(Browser.Selector.id("d"), "2025-12-31").andThen {
                    Browser.eval("document.getElementById('d').value").map { v =>
                        assert(v == "2025-12-31", s"Expected '2025-12-31' but got '$v'")
                    }
                }
            }
        }
    }

    "fill on input type=time sets HH:MM value" in {
        withBrowser {
            onPage("<input id='t' type='time' />") {
                Browser.fill(Browser.Selector.id("t"), "13:45").andThen {
                    Browser.eval("document.getElementById('t').value").map { v =>
                        assert(v == "13:45", s"Expected '13:45' but got '$v'")
                    }
                }
            }
        }
    }

    "fill on input type=datetime-local sets full timestamp" in {
        withBrowser {
            onPage("<input id='dt' type='datetime-local' />") {
                Browser.fill(Browser.Selector.id("dt"), "2025-12-31T13:45").andThen {
                    Browser.eval("document.getElementById('dt').value").map { v =>
                        assert(v == "2025-12-31T13:45", s"Expected '2025-12-31T13:45' but got '$v'")
                    }
                }
            }
        }
    }

    "fill on input type=month sets YYYY-MM" in {
        withBrowser {
            onPage("<input id='m' type='month' />") {
                Browser.fill(Browser.Selector.id("m"), "2025-12").andThen {
                    Browser.eval("document.getElementById('m').value").map { v =>
                        assert(v == "2025-12", s"Expected '2025-12' but got '$v'")
                    }
                }
            }
        }
    }

    "fill on input type=week sets YYYY-Www" in {
        withBrowser {
            onPage("<input id='w' type='week' />") {
                Browser.fill(Browser.Selector.id("w"), "2025-W52").andThen {
                    Browser.eval("document.getElementById('w').value").map { v =>
                        assert(v == "2025-W52", s"Expected '2025-W52' but got '$v'")
                    }
                }
            }
        }
    }

    "fill on input type=number sets numeric string" in {
        withBrowser {
            onPage("<input id='n' type='number' />") {
                Browser.fill(Browser.Selector.id("n"), "42").andThen {
                    Browser.eval("document.getElementById('n').value").map { v =>
                        assert(v == "42", s"Expected '42' but got '$v'")
                    }.andThen {
                        Browser.eval("document.getElementById('n').valueAsNumber").map { v =>
                            assert(v == "42", s"Expected valueAsNumber 42 but got '$v'")
                        }
                    }
                }
            }
        }
    }

    "fill on input type=number with non-numeric text aborts FillDesync after retry timeout" in {
        // Per HTML spec the value setter stores '' for non-numeric input. The unified shim sets via the prototype
        // setter, then verifyFilledValue reads el.value === '' which doesn't match the requested 'abc' and raises
        // BrowserElementNotActionableException(_, FillDesync). The withRetry envelope retries until the schedule
        // exhausts. Asserts the final reason is FillDesync (not BrowserConnectionException, which would mean the
        // prototype-setter raised in the page rather than silently storing empty).
        withBrowser {
            onPage("<input id='n' type='number' />") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(500.millis))) {
                    Abort.run[BrowserElementException] {
                        Browser.fill(Browser.Selector.id("n"), "abc")
                    }.map { result =>
                        result match
                            case Result.Failure(ex: BrowserElementNotActionableException) =>
                                assert(
                                    ex.reason == Reason.FillDesync,
                                    s"Expected reason FillDesync but got ${ex.reason}"
                                )
                            case other =>
                                fail(s"Expected BrowserElementNotActionableException(FillDesync) but got $other")
                        end match
                    }
                }
            }
        }
    }

    "fill on input type=range sets the slider value and dispatches input event" in {
        // Contract: exactly one input event per fill.
        withBrowser {
            onPage(
                """<input id='r' type='range' min='0' max='100' value='50'
                  | oninput='window.__inputs=(window.__inputs||0)+1' />""".stripMargin
            ) {
                Browser.fill(Browser.Selector.id("r"), "75").andThen {
                    Browser.eval("document.getElementById('r').value").map { v =>
                        assert(v == "75", s"Expected '75' but got '$v'")
                    }.andThen {
                        Browser.eval("(window.__inputs||0).toString()").map { v =>
                            assert(v == "1", s"Expected exactly one input event but got '$v'")
                        }
                    }
                }
            }
        }
    }

    "fill on input type=color sets hex color" in {
        withBrowser {
            onPage("<input id='c' type='color' />") {
                Browser.fill(Browser.Selector.id("c"), "#ff0000").andThen {
                    Browser.eval("document.getElementById('c').value").map { v =>
                        assert(v == "#ff0000", s"Expected '#ff0000' but got '$v'")
                    }
                }
            }
        }
    }

    "fill on textarea preserves embedded newlines" in {
        // Input.insertText rejects newlines; fill must use a separate key-dispatch path to deliver them.
        withBrowser {
            onPage("<textarea id='t'></textarea>") {
                Browser.fill(Browser.Selector.id("t"), "line1\nline2\nline3").andThen {
                    Browser.eval("document.getElementById('t').value").map { v =>
                        assert(v == "line1\nline2\nline3", s"Expected 'line1\\nline2\\nline3' but got '$v'")
                    }
                }
            }
        }
    }

    "fill on input type=text preserves leading whitespace" in {
        // Input.insertText trims leading whitespace; fill must preserve it via a different input path.
        withBrowser {
            onPage("<input id='t' type='text' />") {
                Browser.fill(Browser.Selector.id("t"), "  hello").andThen {
                    Browser.eval("document.getElementById('t').value").map { v =>
                        assert(v == "  hello", s"Expected '  hello' but got '$v'")
                    }
                }
            }
        }
    }

    "fill on input type=text preserves trailing whitespace" in {
        withBrowser {
            onPage("<input id='t' type='text' />") {
                Browser.fill(Browser.Selector.id("t"), "hello  ").andThen {
                    Browser.eval("document.getElementById('t').value").map { v =>
                        assert(v == "hello  ", s"Expected 'hello  ' but got '$v'")
                    }
                }
            }
        }
    }

    "fill on empty input fires exactly one input and one change event" in {
        // Contract: exactly one `input` and one `change` event per fill, regardless of prior content.
        withBrowser {
            onPage(
                """<input id='t' type='text' />
                  |<script>
                  |  window.__inputs = 0;
                  |  window.__changes = 0;
                  |  const el = document.getElementById('t');
                  |  el.addEventListener('input', () => window.__inputs++);
                  |  el.addEventListener('change', () => window.__changes++);
                  |</script>""".stripMargin
            ) {
                Browser.fill(Browser.Selector.id("t"), "ab").andThen {
                    Browser.eval("window.__inputs.toString()").map { v =>
                        assert(v == "1", s"Expected exactly 1 input event but got '$v'")
                    }.andThen {
                        Browser.eval("window.__changes.toString()").map { v =>
                            assert(v == "1", s"Expected exactly 1 change event but got '$v'")
                        }
                    }
                }
            }
        }
    }

    "fill on non-empty input fires exactly one input and one change event" in {
        // There is no clear-then-type intermediate `input` event; the unified path replaces
        // the value in a single setter call.
        withBrowser {
            onPage(
                """<input id='t' type='text' value='initial' />
                  |<script>
                  |  window.__inputs = 0;
                  |  window.__changes = 0;
                  |  const el = document.getElementById('t');
                  |  el.addEventListener('input', () => window.__inputs++);
                  |  el.addEventListener('change', () => window.__changes++);
                  |</script>""".stripMargin
            ) {
                Browser.fill(Browser.Selector.id("t"), "replaced").andThen {
                    Browser.eval("window.__inputs.toString()").map { v =>
                        assert(v == "1", s"Expected exactly 1 input event but got '$v'")
                    }.andThen {
                        Browser.eval("window.__changes.toString()").map { v =>
                            assert(v == "1", s"Expected exactly 1 change event but got '$v'")
                        }
                    }
                }
            }
        }
    }

    "fill('') fires exactly one input event with empty value" in {
        // Even an empty fill MUST produce a single observable `input` event so frameworks see the cleared state.
        withBrowser {
            onPage(
                """<input id='t' type='text' value='initial' />
                  |<script>
                  |  window.__values = [];
                  |  document.getElementById('t').addEventListener('input', e => window.__values.push(e.target.value));
                  |</script>""".stripMargin
            ) {
                Browser.fill(Browser.Selector.id("t"), "").andThen {
                    Browser.eval("window.__values.join('|')").map { v =>
                        assert(v == "", s"Expected exactly one entry, the empty string, but got '$v'")
                    }.andThen {
                        Browser.eval("window.__values.length.toString()").map { v =>
                            assert(v == "1", s"Expected exactly 1 input event but got '$v'")
                        }.andThen {
                            Browser.eval("document.getElementById('t').value").map { v =>
                                assert(v == "", s"Expected element value '' but got '$v'")
                            }
                        }
                    }
                }
            }
        }
    }

    "sequential fill('a') + fill('b') fires exactly 2 input events total" in {
        // Each fill is its own observable transition (onInput called for each fill, not batched).
        withBrowser {
            onPage(
                """<input id='t' type='text' />
                  |<script>
                  |  window.__inputs = 0;
                  |  document.getElementById('t').addEventListener('input', () => window.__inputs++);
                  |</script>""".stripMargin
            ) {
                Browser.fill(Browser.Selector.id("t"), "a").andThen {
                    Browser.fill(Browser.Selector.id("t"), "b").andThen {
                        Browser.eval("window.__inputs.toString()").map { v =>
                            assert(v == "2", s"Expected exactly 2 input events for two fills but got '$v'")
                        }.andThen {
                            Browser.eval("document.getElementById('t').value").map { v =>
                                assert(v == "b", s"Expected element value 'b' (second fill replaces first) but got '$v'")
                            }
                        }
                    }
                }
            }
        }
    }

    "fill leaves the input focused with caret at end" in {
        // Prerequisite for press-after-fill correctness: fill leaves the input focused with caret at end.
        withBrowser {
            onPage("<input id='t' type='text' />") {
                Browser.fill(Browser.Selector.id("t"), "abc").andThen {
                    Browser.eval("document.activeElement.id").map { v =>
                        assert(v == "t", s"Expected document.activeElement.id 't' but got '$v'")
                    }.andThen {
                        Browser.eval("document.getElementById('t').selectionStart.toString()").map { v =>
                            assert(v == "3", s"Expected selectionStart 3 but got '$v'")
                        }.andThen {
                            Browser.eval("document.getElementById('t').selectionEnd.toString()").map { v =>
                                assert(v == "3", s"Expected selectionEnd 3 but got '$v'")
                            }
                        }
                    }
                }
            }
        }
    }

    "fill on a select element delegates to Browser.select" in {
        // SELECT elements short-circuit to Browser.select before the JS-fallback path runs.
        withBrowser {
            onPage(
                """<select id='s'><option value='a'>A</option><option value='b'>B</option></select>"""
            ) {
                Browser.fill(Browser.Selector.id("s"), "b").andThen {
                    Browser.eval("document.getElementById('s').value").map { v =>
                        assert(v == "b", s"Expected select value 'b' but got '$v'")
                    }
                }
            }
        }
    }

    "fill on <input> silently strips embedded newlines per HTML value-sanitization" in {
        // HTML spec: the value-sanitization algorithm for `<input type=text>` (and most other input types) strips
        // any `\n` from the assigned value. `<textarea>` keeps newlines (covered by H.10). The unified JS-fallback
        // calls the prototype `value` setter, which goes through the sanitization algorithm, so the readback in
        // `verifyFilledValue` must mirror that behaviour for `<input>` to avoid a spurious FillDesync.
        withBrowser {
            onPage("<input id='inp' type='text' />") {
                Browser.fill(Browser.Selector.id("inp"), "a\nb").andThen {
                    Browser.eval("document.getElementById('inp').value").map { v =>
                        assert(v == "ab", s"expected 'ab' (newlines stripped per HTML spec) but got '$v'")
                    }
                }
            }
        }
    }

    "focus event delivery: click(a) then click(b) emits focus:a, blur:a, focus:b on the page" in {
        // Regression coverage for the click → click focus event-delivery contract. A page-side listener records every focus/blur
        // event; if mutation settlement closed before the second click's focus event ran, the recorded sequence would be missing
        // the trailing "focus:b" entry.
        withBrowser {
            onPage("""<button id='a'>A</button><button id='b'>B</button>""") {
                Browser.eval("""
                    window.__events = [];
                    for (const el of document.querySelectorAll('button')) {
                        el.addEventListener('focus', e => window.__events.push('focus:' + e.target.id));
                        el.addEventListener('blur',  e => window.__events.push('blur:'  + e.target.id));
                    }
                """).andThen {
                    Browser.click(Browser.Selector.id("a"))
                        .andThen(Browser.click(Browser.Selector.id("b")))
                        .andThen {
                            Browser.eval("window.__events.join(',')").map { events =>
                                assert(
                                    events == "focus:a,blur:a,focus:b",
                                    s"focus event delivery from kyo-browser: expected 'focus:a,blur:a,focus:b' but got '$events'"
                                )
                            }
                        }
                }
            }
        }
    }

    // ---- check / uncheck ----

    "check sets checkbox to checked" in {
        withBrowser {
            onPage("<input id='cb' type='checkbox' role='checkbox' />") {
                Browser.check(Browser.Selector.css("#cb")).andThen {
                    Browser.eval("document.getElementById('cb').checked").map { v =>
                        assert(v == "true", s"Expected 'true' but got '$v'")
                    }
                }
            }
        }
    }

    "check is idempotent" in {
        withBrowser {
            onPage("<input id='cb' type='checkbox' role='checkbox' checked />") {
                Browser.check(Browser.Selector.css("#cb")).andThen {
                    Browser.eval("document.getElementById('cb').checked").map { v =>
                        assert(v == "true", s"Expected 'true' but got '$v'")
                    }
                }
            }
        }
    }

    "uncheck clears checkbox" in {
        withBrowser {
            onPage("<input id='cb' type='checkbox' role='checkbox' checked />") {
                Browser.uncheck(Browser.Selector.css("#cb")).andThen {
                    Browser.eval("document.getElementById('cb').checked").map { v =>
                        assert(v == "false", s"Expected 'false' but got '$v'")
                    }
                }
            }
        }
    }

    "uncheck is idempotent" in {
        withBrowser {
            onPage("<input id='cb' type='checkbox' role='checkbox' />") {
                Browser.uncheck(Browser.Selector.css("#cb")).andThen {
                    Browser.eval("document.getElementById('cb').checked").map { v =>
                        assert(v == "false", s"Expected 'false' but got '$v'")
                    }
                }
            }
        }
    }

    // ---- select ----

    "select valid option changes value" in {
        withBrowser {
            onPage("<select id='color'><option value='red'>Red</option><option value='blue'>Blue</option></select>") {
                Browser.select(Browser.Selector.css("#color"), "blue").andThen {
                    Browser.eval("document.getElementById('color').value").map { v =>
                        assert(v == "blue", s"Expected 'blue' but got '$v'")
                    }
                }
            }
        }
    }

    // ---- contenteditable fill ----

    private val editingPage = """<div id='editor' contenteditable='true' style='width:300px;height:100px'><p>Draft</p></div>
        <input id='other' value='untouched'><script>
        window.editEvents = []; window.deleteKeys = 0;
        const editor = document.getElementById('editor');
        editor.addEventListener('beforeinput', e => editEvents.push([e.type, e.inputType, e.isTrusted]));
        editor.addEventListener('input', e => editEvents.push([e.type, e.inputType, e.isTrusted]));
        editor.addEventListener('change', () => editEvents.push(['change']));
        editor.addEventListener('keydown', e => { if (e.key === 'Delete') { deleteKeys++; other.focus(); other.select(); } });
        </script>"""

    private val editingGuardTracker = """<script>
        window.editorGuards = new Set();
        const add = window.addEventListener;
        const remove = window.removeEventListener;
        const tracked = type => ['beforeinput', 'blur', 'textInput'].includes(type);
        window.addEventListener = function(type, listener, capture) {
            if (tracked(type) && capture === true) editorGuards.add(listener);
            return add.call(this, type, listener, capture);
        };
        window.removeEventListener = function(type, listener, capture) {
            if (tracked(type) && capture === true) editorGuards.delete(listener);
            return remove.call(this, type, listener, capture);
        };
        </script>"""

    private def pauseFirstWait[A, S](entered: Latch, resume: Latch)(body: => A < (Async & S))(using Frame): A < (Async & S) =
        ArrowEffect.handleFirst(Tag[Async.Join], body)(
            [C] =>
                (input, continue) =>
                    entered.release.andThen(resume.await).andThen {
                        ArrowEffect.suspendWith(Tag[Async.Join], input)(result => continue(result))
                    },
            identity
        )

    "contenteditable fill replaces text through one trusted input transaction" in {
        withBrowser {
            onPage(editingPage) {
                Browser.fill(Browser.Selector.id("editor"), "Please find the notes").andThen {
                    Browser.eval("JSON.stringify([editor.textContent, editEvents, document.activeElement.id])").map { state =>
                        assert(
                            state ==
                                """["Please find the notes",[["beforeinput","insertText",true],["input","insertText",true]],"editor"]""",
                            state
                        )
                    }
                }
            }
        }
    }

    "contenteditable fill retains line boundaries and repeated spaces" in {
        withBrowser {
            onPage(editingPage) {
                Browser.fill(Browser.Selector.id("editor"), "Hi  there\nBye ").andThen {
                    Browser.eval("JSON.stringify(Array.from(editor.children, p => p.textContent.replace(/\\u00a0/g, ' ')))").map {
                        paragraphs =>
                            assert(paragraphs == """["Hi  there","Bye "]""", paragraphs)
                    }
                }
            }
        }
    }

    "contenteditable fill clears without Delete handlers touching another field" in {
        withBrowser {
            onPage(editingPage) {
                Browser.fill(Browser.Selector.id("editor"), "").andThen {
                    Browser.eval(
                        "JSON.stringify([editor.textContent, other.value, deleteKeys, editEvents.filter(e => e[0] === 'input').map(e => e[2])])"
                    )
                        .map(state => assert(state == """["","untouched",0,[true]]""", state))
                }
            }
        }
    }

    "contenteditable fill rejects a formatter that erases line boundaries" in {
        withBrowser {
            onPage(editingPage + "<script>editor.addEventListener('input', () => { editor.textContent = editor.textContent; });</script>") {
                for
                    result <- Browser.withConfig(_.retrySchedule(Schedule.done)) {
                        Abort.run[BrowserElementNotActionableException](Browser.fill(Browser.Selector.id("editor"), "a\nb"))
                    }
                    state <- Browser.eval("JSON.stringify([editor.textContent, editor.children.length])")
                yield
                    assert(state == """["ab",0]""", state)
                    assert(result.failure.map(_.reason) == Present(Reason.FillDesync), s"result=$result, state=$state")
            }
        }
    }

    for text <- Chunk("new", "") do
        s"contenteditable fill refuses redirected focus for '$text' without editing either field" in {
            withBrowser {
                onPage(editingPage + "<script>editor.addEventListener('focus', () => other.focus());</script>") {
                    for
                        result <- Browser.withConfig(_.retrySchedule(Schedule.done)) {
                            Abort.run[BrowserElementNotActionableException](Browser.fill(Browser.Selector.id("editor"), text))
                        }
                        state <- Browser.eval("JSON.stringify([editor.textContent, other.value, editEvents, document.activeElement.id])")
                    yield
                        assert(state == """["Draft","untouched",[],"other"]""", s"state=$state, result=$result")
                        assert(result.failure.map(_.reason) == Present(Reason.NotFocused))
                }
            }
        }
    end for

    for (name, contents, mode) <- Chunk(
            ("paragraphs", "<p>Draft</p>", "true"),
            ("inline text", "Draft", "true"),
            ("empty host", "", "true"),
            ("plaintext host", "Draft", "plaintext-only")
        )
    do
        s"contenteditable fill preserves blank lines and Unicode in $name" in {
            val html =
                s"""<div id='editor' contenteditable='$mode' onbeforeinput="window.nativeText=event.data"
                    style='white-space:pre-wrap;width:300px;min-height:100px'>$contents</div>"""
            withBrowser {
                onPage(html) {
                    Kyo.foreachDiscard(Chunk("\na\n\nb\n", "  \u00a0🧠cafe\u0301\t ", "\r\nline\rend", "\n", "")) { text =>
                        for
                            result <- Browser.withConfig(_.retrySchedule(Schedule.done)) {
                                Abort.run[BrowserElementNotActionableException](Browser.fill(Browser.Selector.id("editor"), text))
                            }
                            html       <- Browser.eval("editor.innerHTML")
                            chars      <- Browser.eval("editor.textContent.replace(/[\\r\\n]/g, '').replace(/\\u00a0/g, ' ')")
                            nativeText <- Browser.eval("JSON.stringify(window.nativeText)")
                        yield
                            assert(result.isSuccess, s"text=${Json.encode(text)}, html=$html, result=$result")
                            assert(chars == text.replace("\r", "").replace("\n", "").replace('\u00a0', ' '), chars)
                            if text.nonEmpty then assert(nativeText == Json.encode(text), nativeText)
                    }
                }
            }
        }
    end for

    "contenteditable fill changes only the named nested editor contents" in {
        withBrowser {
            onPage("""<div id='host' contenteditable='true'><span id='editor'>Draft</span><span id='sibling'> untouched</span></div>""") {
                Browser.fill(Browser.Selector.id("editor"), "new").andThen {
                    Browser.eval("JSON.stringify([editor.textContent, sibling.textContent, document.activeElement.id])")
                        .map(state => assert(state == """["new"," untouched","host"]""", state))
                }
            }
        }
    }

    "contenteditable fill honors a cancelled beforeinput event" in {
        withBrowser {
            onPage(editingPage + "<script>editor.addEventListener('beforeinput', e => e.preventDefault());</script>") {
                for
                    result <- Browser.withConfig(_.retrySchedule(Schedule.done)) {
                        Abort.run[BrowserElementNotActionableException](Browser.fill(Browser.Selector.id("editor"), "new"))
                    }
                    state <- Browser.eval("JSON.stringify([editor.textContent, editEvents])")
                yield
                    assert(result.failure.map(_.reason) == Present(Reason.FillDesync))
                    assert(state == """["Draft",[["beforeinput","insertText",true]]]""", state)
            }
        }
    }

    for event <- Seq("beforeinput", "textInput"); text <- Seq("new", "") do
        s"contenteditable fill never writes a field focused by $event with '$text'" in {
            withBrowser {
                onPage(editingPage +
                    s"<script>editor.addEventListener('$event', e => { e.stopPropagation(); other.focus(); other.select(); });</script>") {
                    for
                        result <- Browser.withConfig(_.retrySchedule(Schedule.done)) {
                            Abort.run[BrowserElementNotActionableException](Browser.fill(Browser.Selector.id("editor"), text))
                        }
                        state <- Browser.eval("JSON.stringify([editor.textContent, other.value])")
                    yield
                        assert(result.failure.map(_.reason) == Present(Reason.NotFocused))
                        assert(state == """["Draft","untouched"]""", s"state=$state, result=$result")
                }
            }
        }
    end for

    for rejected <- Chunk(false, true) do
        s"contenteditable fill releases native input guards with rejected=$rejected" in {
            withBrowser {
                val redirect = if rejected then
                    "<script>editor.addEventListener('beforeinput', () => other.focus());</script>"
                else ""
                onPage(editingPage + editingGuardTracker + redirect) {
                    for
                        result <- Browser.withConfig(_.retrySchedule(Schedule.done)) {
                            Abort.run[BrowserElementNotActionableException](Browser.fill(Browser.Selector.id("editor"), "new"))
                        }
                        guards <- Browser.eval("editorGuards.size.toString()")
                        _      <- Browser.eval("other.focus(); other.select(); 'selected'")
                        _      <- Browser.typeText("later")
                        value  <- Browser.eval("other.value")
                    yield
                        assert(result.isFailure == rejected)
                        assert(guards == "0", guards)
                        assert(value == "later", value)
                }
            }
        }
    end for

    "contenteditable fill releases an editor acquired after its caller was interrupted" in {
        withBrowser {
            onPage(editingPage + editingGuardTracker) {
                Browser.use { tab =>
                    for
                        entered <- Latch.init(1)
                        resume  <- Latch.init(1)
                        done    <- Latch.init(1)
                        fiber   <- Fiber.initUnscoped {
                            Sync.ensure(done.release) {
                                Abort.run[BrowserReadException] {
                                    Browser.runOn(tab) {
                                        // Hold the acquisition's first reply join, while its detached producer can install the guard.
                                        pauseFirstWait(entered, resume)(EditableInput.fill(Browser.Selector.id("editor"), "new"))
                                    }
                                }
                            }
                        }
                        _     <- Scope.ensure(fiber.interrupt.andThen(done.await))
                        _     <- entered.await
                        _     <- assertEventually(Browser.eval("editorGuards.size.toString()").map(_ == "3"))
                        _     <- fiber.interrupt
                        _     <- done.await
                        _     <- assertEventually(Browser.eval("editorGuards.size.toString()").map(_ == "0"))
                        state <- Browser.eval("JSON.stringify([editor.textContent, editEvents])")
                    yield assert(state == """["Draft",[]]""", state)
                }
            }
        }
    }

    "contenteditable fill cleanup never removes listeners from a replacement document" in {
        withBrowser {
            onPage(editingPage + editingGuardTracker) {
                Browser.use { tab =>
                    for
                        entered <- Latch.init(1)
                        resume  <- Latch.init(1)
                        done    <- Latch.init(1)
                        fiber   <- Fiber.initUnscoped {
                            Sync.ensure(done.release) {
                                Abort.run[BrowserReadException] {
                                    Browser.runOn(tab) {
                                        pauseFirstWait(entered, resume)(EditableInput.fill(Browser.Selector.id("editor"), "new"))
                                    }
                                }
                            }
                        }
                        _ <- Scope.ensure(fiber.interrupt.andThen(done.await))
                        _ <- entered.await
                        _ <- assertEventually(Browser.eval("editorGuards.size.toString()").map(_ == "3"))
                        _ <- onPage(editingPage + editingGuardTracker +
                            "<script>window.addEventListener('beforeinput', () => {}, true);</script>") {
                            for
                                _     <- fiber.interrupt
                                _     <- done.await
                                state <- Browser.eval("JSON.stringify([editor.textContent, editorGuards.size])")
                            yield assert(state == """["Draft",1]""", state)
                        }
                    yield ()
                }
            }
        }
    }

    private def labelSelectPage(options: String, attributes: String) =
        s"""<select id='size' style='min-width:150px' $attributes>$options</select><script>
            window.selectChanges = []; window.selectInputs = 0;
            const control = document.getElementById('size');
            document.body.addEventListener('change', e => {
                if (e.target === control) selectChanges.push([control.selectedIndex, control.value]);
            });
            document.body.addEventListener('input', e => { if (e.target === control) selectInputs++; });
        </script>"""

    private def labelSelectState(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Browser.eval("""(() => {
            const el = document.getElementById('size');
            return JSON.stringify([el.selectedIndex, el.value, selectChanges, selectInputs]);
        })()""")

    "selectLabel native control distinguishes label attributes from displayed-text fallbacks" in {
        withBrowser {
            onPage(labelSelectPage(
                """<option value='a'>  Alpha
                    label  </option><option value='b' label='  Tiny  '>ignored</option>
                    <option value='c' label=''>Visible fallback</option><option value='d'></option>""",
                ""
            )) {
                Browser.eval("JSON.stringify(Array.from(document.getElementById('size').options, o => [o.label, o.text]))").map { labels =>
                    assert(labels == """[["Alpha label","Alpha label"],["  Tiny  ","ignored"],["","Visible fallback"],["",""]]""", labels)
                }
            }
        }
    }

    "selectLabel native control preserves value-selection event semantics" in {
        withBrowser {
            onPage(labelSelectPage("<option value='one'>One</option><option value='two'>Two</option>", "")) {
                Browser.select(Selector.id("size"), "two").andThen(labelSelectState).map { state =>
                    assert(state == """[1,"two",[[1,"two"]],0]""", state)
                }
            }
        }
    }

    "selectLabel native control permits programmatic selection of a disabled option" in {
        withBrowser {
            onPage(labelSelectPage("<option value='one' disabled>One</option><option value='two' selected>Two</option>", "")) {
                Browser.select(Selector.id("size"), "one").andThen(labelSelectState).map { state =>
                    assert(state == """[0,"one",[[0,"one"]],0]""", state)
                }
            }
        }
    }

    for (name, options, label, index, value) <- Chunk(
            ("text", "<option value='one'>One</option><option value='two' selected>Two</option>", "One", 0, "one"),
            ("attribute", "<option value='one' label='Tiny'>Small</option><option value='two' selected>Two</option>", "Tiny", 0, "one"),
            (
                "text whitespace",
                "<option value='one'>  Small\n  size </option><option value='two' selected>Two</option>",
                "Small size",
                0,
                "one"
            ),
            (
                "empty attribute fallback",
                "<option value='one' label=''>Visible</option><option value='two' selected>Two</option>",
                "Visible",
                0,
                "one"
            ),
            ("empty displayed label", "<option value='one' label=''>Visible</option><option value='blank'></option>", "", 1, "blank"),
            (
                "duplicate values",
                "<option value='same'>First</option><option value='same'>Second</option><option value='other' selected>Other</option>",
                "Second",
                1,
                "same"
            ),
            ("duplicate labels", "<option value='one'>Same</option><option value='two' selected>Same</option>", "Same", 0, "one"),
            ("already selected", "<option value='one' selected>One</option><option value='two'>Two</option>", "One", 0, "one"),
            (
                "quoted text",
                "<option value='one'>O'Reilly &amp; &quot;Tiny&quot;</option><option value='two' selected>Two</option>",
                "O'Reilly & \"Tiny\"",
                0,
                "one"
            ),
            (
                "optgroup",
                "<optgroup label='Group'><option value='one'>One</option><option value='two'>Two</option></optgroup>",
                "Two",
                1,
                "two"
            ),
            ("disabled option", "<option value='one' disabled>One</option><option value='two' selected>Two</option>", "One", 0, "one"),
            ("empty value", "<option value='one'>One</option><option value=''>None</option>", "None", 1, "")
        )
    do
        s"selectLabel chooses the requested option by $name and emits one change" in {
            withBrowser {
                onPage(labelSelectPage(options, "")) {
                    Browser.selectLabel(Selector.id("size"), label).andThen(labelSelectState).map { state =>
                        val expected = s"""[$index,${Json.encode(value)},[[$index,${Json.encode(value)}]],0]"""
                        assert(state == expected, state)
                    }
                }
            }
        }
    end for

    for (name, options, requested, index, value) <- Chunk(
            ("missing label", "<option value='one'>One</option><option value='two' selected>Two</option>", "Absent", 1, "two"),
            ("value differs from label", "<option value='one'>One</option><option value='two' selected>Two</option>", "one", 1, "two"),
            ("empty select", "", "Absent", -1, "")
        )
    do
        s"selectLabel reports $name without mutating selection or events" in {
            withBrowser {
                onPage(labelSelectPage(options, "")) {
                    for
                        result <- Browser.withConfig(_.retrySchedule(Schedule.done)) {
                            Abort.run[BrowserElementException](Browser.selectLabel(Selector.id("size"), requested))
                        }
                        state <- labelSelectState
                    yield
                        result match
                            case Result.Failure(error: BrowserOptionLabelNotFoundException) =>
                                assert(error.selector == "id(\"size\")")
                                assert(error.label == requested)
                            case other => fail(s"expected missing label, got $other")
                        end match
                        assert(state == s"""[$index,${Json.encode(value)},[],0]""", state)
                }
            }
        }
    end for

    for (attributes, reason) <- Chunk(
            ("disabled", Reason.Disabled(Reason.DisabledKind.Attribute)),
            ("aria-disabled='true'", Reason.Disabled(Reason.DisabledKind.AriaDisabled))
        )
    do
        s"selectLabel rejects a $attributes select without events" in {
            withBrowser {
                onPage(labelSelectPage("<option value='one'>One</option><option value='two'>Two</option>", attributes)) {
                    for
                        result <- Browser.withConfig(_.retrySchedule(Schedule.done)) {
                            Abort.run[BrowserElementException](Browser.selectLabel(Selector.id("size"), "Two"))
                        }
                        state <- labelSelectState
                    yield
                        result match
                            case Result.Failure(error: BrowserElementNotActionableException) => assert(error.reason == reason)
                            case other => fail(s"expected disabled select, got $other")
                        assert(state == """[0,"one",[],0]""", state)
                }
            }
        }
    end for

    "selectLabel replaces multiple selections with the requested option" in {
        withBrowser {
            onPage(labelSelectPage(
                "<option value='one' selected>One</option><option value='two'>Two</option><option value='three' selected>Three</option>",
                "multiple"
            )) {
                for
                    _        <- Browser.selectLabel(Selector.id("size"), "Two")
                    state    <- labelSelectState
                    selected <- Browser.eval("JSON.stringify(Array.from(document.getElementById('size').selectedOptions, o => o.value))")
                yield
                    assert(state == """[1,"two",[[1,"two"]],0]""", state)
                    assert(selected == """["two"]""", selected)
            }
        }
    }

    "selectLabel retries a label that becomes available after a failed attempt" in {
        withBrowser {
            onPage(labelSelectPage("<option id='pending' value='one'>Waiting</option>", "") + """<script>
                window.labelReads = 0;
                const pending = document.getElementById('pending');
                Object.defineProperty(pending, 'label', { get() {
                    labelReads++;
                    if (labelReads === 1) { pending.setAttribute('label', 'Ready'); return 'Waiting'; }
                    return pending.getAttribute('label');
                }});
                </script>""") {
                for
                    _ <- Browser.withConfig(_.retrySchedule(Schedule.immediate.take(1))) {
                        Browser.selectLabel(Selector.id("size"), "Ready")
                    }
                    state <- labelSelectState
                    reads <- Browser.eval("labelReads.toString()")
                yield
                    assert(state == """[0,"one",[[0,"one"]],0]""", state)
                    assert(reads == "2", reads)
            }
        }
    }

    "selectLabel rejects a non-select without writing its value" in {
        withBrowser {
            onPage("<input id='size' value='kept'>") {
                for
                    result <- Browser.withConfig(_.retrySchedule(Schedule.done)) {
                        Abort.run[BrowserElementException](Browser.selectLabel(Selector.id("size"), "Wanted"))
                    }
                    value <- Browser.value(Selector.id("size"))
                yield
                    result match
                        case Result.Failure(error: BrowserOptionLabelNotFoundException) => assert(error.label == "Wanted")
                        case other                                                      => fail(s"expected no matching option, got $other")
                    assert(value == "kept", value)
            }
        }
    }

    "selectLabel does not write an option moved to another select during label access" in {
        withBrowser {
            onPage(labelSelectPage("<option value='one'>One</option><option id='moving' value='two'>Two</option>", "") + """
                <select id='other'><option value='untouched'>Other</option></select><script>
                const moving = document.getElementById('moving');
                Object.defineProperty(moving, 'label', { get() {
                    document.getElementById('other').append(moving);
                    return 'Two';
                }});
                </script>""") {
                for
                    result <- Browser.withConfig(_.retrySchedule(Schedule.done)) {
                        Abort.run[BrowserElementException](Browser.selectLabel(Selector.id("size"), "Two"))
                    }
                    state <- labelSelectState
                    other <- Browser.value(Selector.id("other"))
                yield
                    result match
                        case Result.Failure(error: BrowserOptionLabelNotFoundException) => assert(error.label == "Two")
                        case other => fail(s"expected moved option to be rejected, got $other")
                    assert(state == """[0,"one",[],0]""", state)
                    assert(other == "untouched", other)
            }
        }
    }

    "selectLabel does not write a select detached during label access" in {
        withBrowser {
            onPage(labelSelectPage("<option value='one'>One</option><option id='removing' value='two'>Two</option>", "") + """<script>
                const removing = document.getElementById('removing');
                Object.defineProperty(removing, 'label', { get() {
                    window.removedSelect = document.getElementById('size');
                    removedSelect.remove();
                    return 'Two';
                }});
                </script>""") {
                for
                    result <- Browser.withConfig(_.retrySchedule(Schedule.done)) {
                        Abort.run[BrowserElementException](Browser.selectLabel(Selector.id("size"), "Two"))
                    }
                    state <- Browser.eval("JSON.stringify([removedSelect.selectedIndex, removedSelect.value, selectChanges, selectInputs])")
                yield
                    result match
                        case Result.Failure(error: BrowserElementNotFoundException) => assert(error.selector == "id(\"size\")")
                        case other                                                  => fail(s"expected detached select, got $other")
                    assert(state == """[0,"one",[],0]""", state)
            }
        }
    }

    "selectLabel follows an option's current index after label access reorders it" in {
        withBrowser {
            onPage(labelSelectPage("<option value='one'>One</option><option id='reordering' value='two'>Two</option>", "") + """<script>
                const reordering = document.getElementById('reordering');
                Object.defineProperty(reordering, 'label', { get() {
                    document.getElementById('size').prepend(reordering);
                    return 'Two';
                }});
                </script>""") {
                Browser.selectLabel(Selector.id("size"), "Two").andThen(labelSelectState).map { state =>
                    assert(state == """[0,"two",[[0,"two"]],0]""", state)
                }
            }
        }
    }

    "selectLabel rejects a select in a disabled fieldset" in {
        withBrowser {
            onPage("<fieldset disabled>" + labelSelectPage("<option value='one'>One</option><option value='two'>Two</option>", "") +
                "</fieldset>") {
                for
                    result <- Browser.withConfig(_.retrySchedule(Schedule.done)) {
                        Abort.run[BrowserElementException](Browser.selectLabel(Selector.id("size"), "Two"))
                    }
                    state <- labelSelectState
                yield
                    result match
                        case Result.Failure(error: BrowserElementNotActionableException) =>
                            assert(error.reason == Reason.Disabled(Reason.DisabledKind.FieldsetDisabled))
                        case other => fail(s"expected disabled fieldset, got $other")
                    end match
                    assert(state == """[0,"one",[],0]""", state)
            }
        }
    }

    "selectLabel composes with expectNavigation when the change handler navigates" in {
        val firstBytes = Span.fromUnsafe(labelSelectPage(
            "<option value='/'>Stay</option><option value='/next'>Next</option>",
            "onchange=\"location.href=this.value\""
        ).getBytes("UTF-8"))
        val nextBytes = Span.fromUnsafe("<html><body><h1>navigated</h1></body></html>".getBytes("UTF-8"))
        val first     = HttpRoute.getRaw("/").response(_.bodyBinary).handler(_ =>
            HttpResponse.ok(firstBytes).addHeader("Content-Type", "text/html; charset=utf-8")
        )
        val next = HttpRoute.getRaw("/next").response(_.bodyBinary).handler(_ =>
            HttpResponse.ok(nextBytes).addHeader("Content-Type", "text/html; charset=utf-8")
        )
        withLocalhostServer(first, next) { (host, port) =>
            withBrowser {
                for
                    _ <- Browser.goto(s"http://$host:$port/", Browser.Settle.Load)
                    _ <- Browser.expectNavigation(settle = Browser.Settle.Load) {
                        Browser.selectLabel(Selector.id("size"), "Next")
                    }
                    url  <- Browser.url
                    body <- Browser.text(Selector.css("h1"))
                yield
                    assert(url == s"http://$host:$port/next", url)
                    assert(body == "navigated", body)
            }
        }
    }

    // ---- typeText ----

    "typeText characters appear in focused input" in {
        withBrowser {
            onPage("<input id='input' type='text' value='' />") {
                Browser.focus(Browser.Selector.css("#input")).andThen {
                    Browser.typeText("hello").andThen {
                        Browser.eval("document.getElementById('input').value").map { v =>
                            assert(v == "hello", s"Expected 'hello' but got '$v'")
                        }
                    }
                }
            }
        }
    }

    // typeText must enter every character in order. A mixed-case string with digits exercises the
    // substring-per-index path; the assertion verifies both length and ordering, i.e. no characters
    // dropped, duplicated, or reordered.
    "typeText preserves order across mixed-case + digits" in {
        withBrowser {
            onPage("<input id='input' type='text' value='' />") {
                Browser.focus(Browser.Selector.css("#input")).andThen {
                    Browser.typeText("Hello123World").andThen {
                        Browser.eval("document.getElementById('input').value").map { v =>
                            assert(v == "Hello123World", s"Expected 'Hello123World' but got '$v'")
                        }
                    }
                }
            }
        }
    }

    // ---- doubleClick ----

    "doubleClick fires both ondblclick and click events on the target" in {
        withBrowser {
            onPage(
                """<button id='btn'
                  |  onclick='window.__clicks=(window.__clicks||0)+1'
                  |  ondblclick='window.__dblclicks=(window.__dblclicks||0)+1'
                  |  style='width:80px;height:30px'>dbl</button>""".stripMargin
            ) {
                Browser.doubleClick(Browser.Selector.id("btn")).andThen {
                    Browser.eval("(window.__clicks||0) + '|' + (window.__dblclicks||0)").map { v =>
                        v.split('|').toList match
                            case List(clicks, dblclicks) =>
                                val c = clicks.toIntOption.getOrElse(-1)
                                val d = dblclicks.toIntOption.getOrElse(-1)
                                assert(c == 2, s"Expected 2 click events from doubleClick but got $c")
                                assert(d == 1, s"Expected 1 dblclick event from doubleClick but got $d")
                            case other =>
                                fail(s"Unexpected eval result format: '$v'")
                    }
                }
            }
        }
    }

    "doubleClick on a hidden element raises BrowserElementNotActionableException" in {
        withBrowser {
            onPage("<button id='hidden-btn' style='display:none;width:80px;height:30px'>x</button>") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.doubleClick(Browser.Selector.id("hidden-btn"))
                    }.map {
                        case Result.Failure(ex: BrowserElementNotActionableException) =>
                            assert(ex.reason.isInstanceOf[BrowserElementNotActionableException.Reason.NotVisible])
                        case other =>
                            fail(s"Expected BrowserElementNotActionableException for hidden element but got $other")
                    }
                }
            }
        }
    }

    // ---- scrollToTop / scrollToBottom ----

    "scrollToBottom changes scrollY" in {
        withBrowser {
            onPage("<div style='height:5000px'>Tall page</div>") {
                Browser.scrollToBottom.andThen {
                    Browser.eval("window.scrollY").map { v =>
                        val scrollY = Result.catching(v.toDouble).getOrElse(0.0)
                        assert(scrollY > 0, s"Expected scrollY > 0 but got $scrollY")
                    }
                }
            }
        }
    }

    "scrollToTop resets scrollY" in {
        withBrowser {
            onPage("<div style='height:5000px'>Tall page</div>") {
                Browser.scrollToBottom.andThen {
                    Browser.scrollToTop.andThen {
                        Browser.eval("window.scrollY").map { v =>
                            val scrollY = Result.catching(v.toDouble).getOrElse(1.0)
                            assert(scrollY == 0.0, s"Expected scrollY == 0 but got $scrollY")
                        }
                    }
                }
            }
        }
    }

    // ---- fill / press contract coverage ----

    "sequential fill('a') + fill('b') emits exactly 2 input events total (no batching)" in {
        // Each fill emits exactly one input event; sequential fills MUST NOT merge into a single event.
        withBrowser {
            onPage("<input id='inp' />") {
                Browser.eval("window.__inp = 0; document.getElementById('inp').addEventListener('input', () => window.__inp++)")
                    .andThen(Browser.fill(Browser.Selector.id("inp"), "a"))
                    .andThen(Browser.fill(Browser.Selector.id("inp"), "ab"))
                    .andThen {
                        Browser.eval("String(window.__inp)").map { n =>
                            assert(n == "2", s"expected exactly 2 input events for two fills but got $n")
                        }
                    }
            }
        }
    }

    "fill('') on a non-empty input clears it and emits exactly one input event" in {
        // `fill("")` clears the input AND fires exactly one input event with the empty value: always exactly one input event
        // with the final value, regardless of the prior content.
        withBrowser {
            onPage("<input id='inp' value='initial' />") {
                Browser.eval("window.__inp = 0; document.getElementById('inp').addEventListener('input', () => window.__inp++)")
                    .andThen(Browser.fill(Browser.Selector.id("inp"), ""))
                    .andThen {
                        Browser.eval("document.getElementById('inp').value + ':' + window.__inp").map { combined =>
                            assert(combined == ":1", s"expected value=''/inputs=1 but got '$combined'")
                        }
                    }
            }
        }
    }

    "Browser.fill leaves the input as document.activeElement (no implicit blur)" in {
        // After `fill`, focus stays on the input: a follow-up `press` on the same element sees focus already set.
        withBrowser {
            onPage("<input id='inp' /><input id='other' />") {
                Browser.fill(Browser.Selector.id("inp"), "x").andThen {
                    Browser.eval("document.activeElement && document.activeElement.id || ''").map { active =>
                        assert(active == "inp", s"expected activeElement='inp' but got '$active'")
                    }
                }
            }
        }
    }

    "fill leaves textarea focused with caret at end of value" in {
        // Symmetric to the `<input>` contract: textareas preserve newlines per HTML spec, so caret position is the text length
        // even with embedded `\n`.
        withBrowser {
            onPage("<textarea id='ta'></textarea>") {
                Browser.fill(Browser.Selector.id("ta"), "hello\nworld").andThen {
                    Browser.eval("""(() => {
                        const el = document.getElementById('ta');
                        return (document.activeElement === el ? '1' : '0') + ',' + el.selectionStart + ',' + el.selectionEnd;
                    })()""").map { stats =>
                        val expected = s"1,${"hello\nworld".length},${"hello\nworld".length}"
                        assert(stats == expected, s"expected '$expected' but got '$stats'")
                    }
                }
            }
        }
    }

    // ---- setFiles ----

    // Navigate to a real http page (file input attaches reliably and CDP
    // setFileInputFiles is accepted), inject a `<input type="file" multiple>` into the DOM, write two
    // small temp files, call `setFiles`, then assert via a single multi-field `Browser.eval` that
    // `input.files.length == paths.length` and the basenames round-trip.
    "setFiles attaches the requested files to a multi-file input" in {
        withBrowserOnLocalhost {
            // Replace document body with a multi-file input. The page is on a real http origin
            // so CDP's setFileInputFiles is accepted (data: URLs reject it).
            Browser.eval(
                "(() => { document.body.innerHTML = '<input type=\"file\" id=\"fileInput\" multiple>'; return 'ok'; })()"
            ).andThen {
                Scope.run {
                    for
                        tmp1 <- Path.run(Path.temp("kyo-setFiles-a-", ".txt"))
                        tmp2 <- Path.run(Path.temp("kyo-setFiles-b-", ".txt"))
                        _    <- Path.run(tmp1.write("alpha"))
                        _    <- Path.run(tmp2.write("beta"))
                        paths = Chunk(tmp1, tmp2)
                        _ <- Browser.setFiles(Browser.Selector.css("#fileInput"), paths)
                        // Single multi-field eval: one CDP round-trip returning length and both basenames.
                        v <- Browser.eval(
                            """(() => {
                                const el = document.getElementById('fileInput');
                                const f = el.files;
                                return f.length + '|' + (f[0] ? f[0].name : '') + '|' + (f[1] ? f[1].name : '');
                            })()"""
                        )
                        baseA = tmp1.toString.split("/").last
                        baseB = tmp2.toString.split("/").last
                    yield
                        val parts = v.split('|')
                        assert(parts.length == 3, s"expected 3 fields from eval but got '$v'")
                        assert(parts(0) == "2", s"expected files.length == 2 but got '${parts(0)}' (full eval result: '$v')")
                        assert(parts(1) == baseA, s"expected files[0].name == '$baseA' but got '${parts(1)}'")
                        assert(parts(2) == baseB, s"expected files[1].name == '$baseB' but got '${parts(2)}'")
                    end for
                }
            }
        }
    }

    "setFiles rejects a non-absolute Path with BrowserInvalidArgumentException" in {
        // Pre-validation: setFiles validates every path is absolute (mirrors setDownloadBehavior) BEFORE any CDP call.
        // A Path constructed from a relative segment fails the validation; reported as BrowserInvalidArgumentException.
        withBrowserOnLocalhost {
            Browser.eval(
                "(() => { document.body.innerHTML = '<input type=\"file\" id=\"fileInput\" multiple>'; return 'ok'; })()"
            ).andThen {
                Abort.run[BrowserReadException] {
                    Browser.setFiles(
                        Browser.Selector.css("#fileInput"),
                        Seq(Path("relative", "path.txt"))
                    )
                }.map {
                    case Result.Failure(ex: BrowserInvalidArgumentException) =>
                        assert(ex.method == "setFiles", s"expected method=setFiles but got: ${ex.method}")
                    case other =>
                        fail(s"expected Result.Failure(BrowserInvalidArgumentException) but got $other")
                }
            }
        }
    }

    "setFiles accepts a Seq[Path] of paths" in {
        // Verifies the public parameter type is Seq[Path]: passing a non-Chunk Seq (List) compiles
        // and round-trips through CDP setFileInputFiles correctly.
        withBrowserOnLocalhost {
            Browser.eval(
                "(() => { document.body.innerHTML = '<input type=\"file\" id=\"fileInput\" multiple>'; return 'ok'; })()"
            ).andThen {
                Scope.run {
                    for
                        tmp1 <- Path.run(Path.temp("kyo-setFiles-seq-a-", ".txt"))
                        tmp2 <- Path.run(Path.temp("kyo-setFiles-seq-b-", ".txt"))
                        _    <- Path.run(tmp1.write("alpha"))
                        _    <- Path.run(tmp2.write("beta"))
                        // Use an explicit List[Path] to exercise the widened Seq parameter type.
                        paths: Seq[Path] = List(tmp1, tmp2)
                        _ <- Browser.setFiles(Browser.Selector.css("#fileInput"), paths)
                        v <- Browser.eval(
                            """(() => {
                                const el = document.getElementById('fileInput');
                                const f = el.files;
                                return f.length + '|' + (f[0] ? f[0].name : '') + '|' + (f[1] ? f[1].name : '');
                            })()"""
                        )
                        baseA = tmp1.toString.split("/").last
                        baseB = tmp2.toString.split("/").last
                    yield
                        val parts = v.split('|')
                        assert(parts.length == 3, s"expected 3 fields from eval but got '$v'")
                        assert(parts(0) == "2", s"expected files.length == 2 but got '${parts(0)}' (full eval: '$v')")
                        assert(parts(1) == baseA, s"expected files[0].name == '$baseA' but got '${parts(1)}'")
                        assert(parts(2) == baseB, s"expected files[1].name == '$baseB' but got '${parts(2)}'")
                    end for
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // fill: recovery from transient element detach
    //
    // Models an SPA rehydration cycle: the fixture removes `#q` at page load and reattaches a fresh input after 500 ms. The retry loop
    // must re-resolve the selector each tick and complete fill within the active retry budget once the input reappears.
    // -------------------------------------------------------------------------

    "fill recovers when the input is transiently detached then reattached" in {
        withBrowser {
            onPage(
                """<input id='q'>
                  |<script>
                  |  const old = document.getElementById('q');
                  |  old.remove();
                  |  setTimeout(() => {
                  |    const fresh = document.createElement('input');
                  |    fresh.id = 'q';
                  |    document.body.appendChild(fresh);
                  |  }, 500);
                  |</script>""".stripMargin
            ) {
                Browser.fill(Browser.Selector.css("#q"), "hello").andThen {
                    Browser.eval("document.getElementById('q').value").map { v =>
                        assert(v == "hello", s"expected fill to land 'hello' on the reattached input but got '$v'")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // fill must dispatch focus DOM events (Emulation.setFocusEmulationEnabled)
    //
    // Programmatic `el.focus()` inside `ProbesJs.fillViaJs` updates `document.activeElement` but in headless / background tabs Chrome
    // suppresses the focus event by default. `BrowserTabSetup.enableDomains` issues `Emulation.setFocusEmulationEnabled(true)` to defeat
    // that suppression, so framework listeners (kyo-ui, React onFocus, etc.) observe a real focus event after a fill.
    // -------------------------------------------------------------------------

    "fill fires a focus DOM event on the filled input" in {
        withBrowser {
            onPage(
                """<input id='a' />
                  |<script>
                  |  window.__focusCount = 0;
                  |  document.getElementById('a').addEventListener('focus', () => { window.__focusCount++; });
                  |</script>""".stripMargin
            ) {
                Browser.fill(Browser.Selector.id("a"), "hello").andThen {
                    Browser.eval("String(window.__focusCount)").map { count =>
                        assert(
                            count == "1",
                            s"expected exactly 1 focus event after fill but got $count; Emulation.setFocusEmulationEnabled likely not engaged"
                        )
                    }
                }
            }
        }
    }

    "fill fires a focus event observable by a capture-phase listener on document.body" in {
        withBrowser {
            onPage(
                """<input id='a' />
                  |<script>
                  |  window.__bodyFocusCount = 0;
                  |  document.body.addEventListener('focus', () => { window.__bodyFocusCount++; }, true);
                  |</script>""".stripMargin
            ) {
                Browser.fill(Browser.Selector.id("a"), "x").andThen {
                    Browser.eval("String(window.__bodyFocusCount)").map { count =>
                        assert(count == "1", s"expected document.body capture-phase focus listener to fire once after fill but got $count")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // fill → press(Key) survives DOM churn that destroys the original element (B0)
    //
    // Models Wikipedia-style typeahead behavior: typing into an input fires a JS handler that destroys the input and
    // re-renders the search container. Any selector captured at fill time is now stale, but the user's intent is "type
    // then submit". The no-selector `Browser.press(Key.Enter)` dispatches to `document.activeElement`, which fill
    // leaves on the (possibly-recreated) input, making this the canonical "type-then-submit" shape.
    // -------------------------------------------------------------------------

    "fill then no-selector press(Key.Enter) survives a DOM-churn re-render of the input" in {
        withBrowser {
            // The page wraps the input in a container that gets re-rendered on the first input event:
            // the old #q is removed, and a NEW input with no id is appended. The form's submit handler
            // (preventDefault + record __submitted=true) catches the eventual Enter press from whatever
            // input is currently focused.
            onPage(
                """<form id='f' onsubmit='event.preventDefault(); window.__submitted = true;'>
                  |  <div id='holder'><input id='q' autofocus></div>
                  |</form>
                  |<script>
                  |  document.getElementById('q').addEventListener('input', function () {
                  |    // 50ms debounce: matches the timing of real reactive frameworks like Wikipedia's typeahead.
                  |    // Fill's CDP round-trips complete inside that window; the re-render fires AFTER fill returns
                  |    // but BEFORE the user's follow-up Browser.press(Key.Enter) runs (the test sleeps 150ms in
                  |    // between to make this deterministic).
                  |    setTimeout(function () {
                  |      const holder = document.getElementById('holder');
                  |      const replacement = document.createElement('input');
                  |      replacement.type = 'text';
                  |      replacement.value = 'hello';
                  |      holder.innerHTML = '';
                  |      holder.appendChild(replacement);
                  |      replacement.focus();
                  |      // Deterministic guard for the test: signals that the replacement input is mounted+focused.
                  |      window.__replaced_input_ready = true;
                  |    }, 50);
                  |  });
                  |</script>""".stripMargin
            ) {
                for
                    _ <- Browser.fill(Browser.Selector.id("q"), "hello")
                    // Deterministic guard: the inline script sets window.__replaced_input_ready after the 50ms re-render
                    // calls replacement.focus(); waiting on it is the real barrier, so no settle sleep is needed.
                    _ <- Browser.waitFor("window.__replaced_input_ready === true")
                    // Original #q no longer exists, but the new input is focused. The no-selector press dispatches there.
                    _         <- Browser.press(Browser.Key.Enter)
                    submitted <- Browser.eval("String(window.__submitted)")
                yield assert(
                    submitted == "true",
                    s"expected the form to submit despite the input being recreated, got window.__submitted=$submitted"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Conversion[String, Selector] ergonomic shortcut: Browser.click("#go") works
    // without wrapping in Browser.Selector.css(...).
    // -------------------------------------------------------------------------

    "Browser.click accepts a raw CSS-selector String (auto-converts to Selector.css)" in {
        withBrowser {
            onPage(
                """<button id='go' onclick="document.getElementById('out').textContent='clicked'">Go</button>
                  |<span id='out'>idle</span>""".stripMargin
            ) {
                // String -> Selector conversion is auto-applied by the given Conversion[String, Selector]
                // in the Selector companion. Equivalent to Browser.click(Selector.css("#go")).
                Browser.click("#go").andThen {
                    Browser.text("#out").map { t =>
                        assert(t == "clicked", s"expected 'clicked' but got '$t'")
                    }
                }
            }
        }
    }

end BrowserMutationTest
