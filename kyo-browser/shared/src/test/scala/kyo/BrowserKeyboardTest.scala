package kyo

import kyo.Browser.*
import kyo.BrowserElementNotActionableException.Reason

class BrowserKeyboardTest extends BrowserTest:

    // 90-second envelope: BrowserKeyboardTest's modifier-bit verification scenarios can exceed 30s
    // under full-suite load when Chrome IPC is contending with preceding tests' I/O.
    override def timeout = 90.seconds

    // ---- press(selector, key, modifiers); modifier-bit propagation on keyDown AND keyUp ----

    // Each test installs `keydown`/`keyup` listeners that record `${type}:${shiftKey}:${ctrlKey}:${altKey}:${metaKey}` into
    // `window.__events`, then asserts the joined log after the press. The keyUp event must carry the same modifier bits as keyDown.
    private val pressModifierListenerHtml: String =
        """<input id='t' autofocus/>
          |<script>
          |  window.__events = [];
          |  var rec = function(type, e) {
          |    window.__events.push(type + ':' + e.shiftKey + ':' + e.ctrlKey + ':' + e.altKey + ':' + e.metaKey);
          |  };
          |  document.addEventListener('keydown', function(e) { rec('keydown', e); });
          |  document.addEventListener('keyup',   function(e) { rec('keyup',   e); });
          |</script>""".stripMargin

    "press(sel, Key('a'), shift = true) emits shiftKey on keydown AND keyup" in run {
        withBrowser {
            onPage(pressModifierListenerHtml) {
                Browser.press(Browser.Selector.id("t"), Browser.Key('a'), Browser.KeyModifiers(shift = true)).andThen {
                    Browser.eval("window.__events.join('|')").map { v =>
                        assert(
                            v == "keydown:true:false:false:false|keyup:true:false:false:false",
                            s"Expected shift bit on both keydown AND keyup but got '$v'"
                        )
                    }
                }
            }
        }
    }

    "press(sel, Key('a'), ctrl = true) emits ctrlKey on keydown AND keyup" in run {
        withBrowser {
            onPage(pressModifierListenerHtml) {
                Browser.press(Browser.Selector.id("t"), Browser.Key('a'), Browser.KeyModifiers(ctrl = true)).andThen {
                    Browser.eval("window.__events.join('|')").map { v =>
                        assert(
                            v == "keydown:false:true:false:false|keyup:false:true:false:false",
                            s"Expected ctrl bit on both keydown AND keyup but got '$v'"
                        )
                    }
                }
            }
        }
    }

    "press(sel, Key('a'), alt = true) emits altKey on keydown AND keyup" in run {
        withBrowser {
            onPage(pressModifierListenerHtml) {
                Browser.press(Browser.Selector.id("t"), Browser.Key('a'), Browser.KeyModifiers(alt = true)).andThen {
                    Browser.eval("window.__events.join('|')").map { v =>
                        assert(
                            v == "keydown:false:false:true:false|keyup:false:false:true:false",
                            s"Expected alt bit on both keydown AND keyup but got '$v'"
                        )
                    }
                }
            }
        }
    }

    "press(sel, Key('a'), meta = true) emits metaKey on keydown AND keyup" in run {
        withBrowser {
            onPage(pressModifierListenerHtml) {
                Browser.press(Browser.Selector.id("t"), Browser.Key('a'), Browser.KeyModifiers(meta = true)).andThen {
                    Browser.eval("window.__events.join('|')").map { v =>
                        assert(
                            v == "keydown:false:false:false:true|keyup:false:false:false:true",
                            s"Expected meta bit on both keydown AND keyup but got '$v'"
                        )
                    }
                }
            }
        }
    }

    "press(sel, Key('a'), KeyModifiers(ctrl = true, shift = true)) emits both modifier bits on both events" in run {
        withBrowser {
            onPage(pressModifierListenerHtml) {
                Browser.press(Browser.Selector.id("t"), Browser.Key('a'), Browser.KeyModifiers(shift = true, ctrl = true)).andThen {
                    Browser.eval("window.__events.join('|')").map { v =>
                        assert(
                            v == "keydown:true:true:false:false|keyup:true:true:false:false",
                            s"Expected ctrl+shift on both events but got '$v'"
                        )
                    }
                }
            }
        }
    }

    // KeyModifiers shift+alt: exercises the new bundle type on the selector-press path. Asserts BOTH bits
    // land on keydown and keyup; ctrl + meta remain false. KeyModifiers.of(...) variant is also exercised
    // to confirm both factories yield identical wire shape.
    "press(sel, Key('a'), KeyModifiers(shift = true, alt = true)) emits shift AND alt on both events" in run {
        withBrowser {
            onPage(pressModifierListenerHtml) {
                Browser.press(
                    Browser.Selector.id("t"),
                    Browser.Key('a'),
                    Browser.KeyModifiers(shift = true, alt = true)
                ).andThen {
                    Browser.eval("window.__events.join('|')").map { v =>
                        assert(
                            v == "keydown:true:false:true:false|keyup:true:false:true:false",
                            s"Expected shift+alt on both events but got '$v'"
                        )
                    }
                }
            }
        }
    }

    "KeyModifiers.of factory yields the same bundle as the case-class apply" in run {
        // Compile-and-equality check: KeyModifiers.of(shift = true, ctrl = true) == KeyModifiers(shift = true, ctrl = true).
        val viaApply   = Browser.KeyModifiers(shift = true, ctrl = true)
        val viaFactory = Browser.KeyModifiers.of(shift = true, ctrl = true)
        assert(viaApply == viaFactory, s"expected KeyModifiers.of to equal case-class apply but got $viaFactory vs $viaApply")
        assert(
            Browser.KeyModifiers.none == Browser.KeyModifiers(),
            s"expected KeyModifiers.none == KeyModifiers() but got ${Browser.KeyModifiers.none}"
        )
        succeed
    }

    "press(sel, Key('a')) without modifier flags leaves all modifier bits false on both events" in run {
        withBrowser {
            onPage(pressModifierListenerHtml) {
                Browser.press(Browser.Selector.id("t"), Browser.Key('a')).andThen {
                    Browser.eval("window.__events.join('|')").map { v =>
                        assert(
                            v == "keydown:false:false:false:false|keyup:false:false:false:false",
                            s"Expected no modifier bits but got '$v'"
                        )
                    }
                }
            }
        }
    }

    "press(sel, Key.Shift) standalone still carries the shift modifier bit on both events" in run {
        // Regression: pressing Key.Shift without explicit shift = true must still send modifiers = 8 because
        // mapKey(Key.Shift).modifierBit = 8 (totalMods is the OR of info.modifierBit and callerMods, NOT a replacement).
        withBrowser {
            onPage(pressModifierListenerHtml) {
                Browser.press(Browser.Selector.id("t"), Browser.Key.Shift).andThen {
                    Browser.eval("window.__events.join('|')").map { v =>
                        assert(
                            v == "keydown:true:false:false:false|keyup:true:false:false:false",
                            s"Expected shiftKey=true on both events for Key.Shift but got '$v'"
                        )
                    }
                }
            }
        }
    }

    // ---- press(key); global, no selector ----

    "press(Enter) without a selector dispatches a keydown event observable on document" in run {
        withBrowser {
            onPage(
                """<div id='log'>none</div>
                  |<script>
                  |  document.addEventListener('keydown', function(e) {
                  |    document.getElementById('log').textContent = e.key;
                  |  });
                  |</script>""".stripMargin
            ) {
                Browser.press(Key.Enter).andThen {
                    Browser.text(Browser.Selector.css("#log")).map { t =>
                        assert(t == "Enter", s"Expected 'Enter' but got '$t'")
                    }
                }
            }
        }
    }

    "press(Escape) without a selector triggers a global keydown handler that mutates the DOM" in run {
        withBrowser {
            onPage(
                """<div id='modal' style='display:block'>open</div>
                  |<script>
                  |  document.addEventListener('keydown', function(e) {
                  |    if (e.key === 'Escape') document.getElementById('modal').style.display = 'none';
                  |  });
                  |</script>""".stripMargin
            ) {
                Browser.press(Key.Escape).andThen {
                    Browser.assertNotVisible(Browser.Selector.css("#modal")).map(_ => succeed)
                }
            }
        }
    }

    "press(key) settles after the handler triggers a delayed DOM mutation" in run {
        // Settlement should wait for the setTimeout-driven mutation to land before press returns.
        withBrowser {
            onPage(
                """<div id='out'>pending</div>
                  |<script>
                  |  document.addEventListener('keydown', function() {
                  |    setTimeout(function() {
                  |      document.getElementById('out').textContent = 'done';
                  |    }, 50);
                  |  });
                  |</script>""".stripMargin
            ) {
                Browser.press(Key.Enter).andThen {
                    Browser.text(Browser.Selector.css("#out")).map { t =>
                        assert(t == "done", s"Expected settled 'done' after press but got '$t'")
                    }
                }
            }
        }
    }

    // ---- Tab key actually advances focus (focus-advance JS shim) ----

    // Chromium's CDP `Input.dispatchKeyEvent({key:'Tab'})` does NOT run the focus-advance algorithm.
    // The shim runs after keyDown/keyUp dispatch and emulates the platform's tabbing algorithm.

    "Tab from element A advances focus to element B in DOM order" in run {
        withBrowser {
            onPage("<input id='a'/><input id='b'/><input id='c'/>") {
                Browser.eval("document.getElementById('a').focus()").andThen {
                    Browser.press(Key.Tab).andThen {
                        Browser.assertFocused(Browser.Selector.id("b")).map(_ => succeed)
                    }
                }
            }
        }
    }

    "Tab from last focusable wraps to first" in run {
        withBrowser {
            onPage("<input id='a'/><input id='b'/><input id='c'/>") {
                Browser.eval("document.getElementById('c').focus()").andThen {
                    Browser.press(Key.Tab).andThen {
                        Browser.assertFocused(Browser.Selector.id("a")).map(_ => succeed)
                    }
                }
            }
        }
    }

    "Tab skips elements with the hidden attribute" in run {
        withBrowser {
            onPage("<input id='a'/><input id='b' hidden/><input id='c'/>") {
                Browser.eval("document.getElementById('a').focus()").andThen {
                    Browser.press(Key.Tab).andThen {
                        Browser.assertFocused(Browser.Selector.id("c")).map(_ => succeed)
                    }
                }
            }
        }
    }

    "Tab skips disabled inputs" in run {
        withBrowser {
            onPage("<input id='a'/><input id='b' disabled/><input id='c'/>") {
                Browser.eval("document.getElementById('a').focus()").andThen {
                    Browser.press(Key.Tab).andThen {
                        Browser.assertFocused(Browser.Selector.id("c")).map(_ => succeed)
                    }
                }
            }
        }
    }

    "Tab skips display:none ancestors" in run {
        withBrowser {
            onPage(
                "<input id='a'/><div style='display:none'><input id='b'/></div><input id='c'/>"
            ) {
                Browser.eval("document.getElementById('a').focus()").andThen {
                    Browser.press(Key.Tab).andThen {
                        Browser.assertFocused(Browser.Selector.id("c")).map(_ => succeed)
                    }
                }
            }
        }
    }

    "Tab skips tabindex=-1" in run {
        withBrowser {
            onPage("<input id='a'/><input id='b' tabindex='-1'/><input id='c'/>") {
                Browser.eval("document.getElementById('a').focus()").andThen {
                    Browser.press(Key.Tab).andThen {
                        Browser.assertFocused(Browser.Selector.id("c")).map(_ => succeed)
                    }
                }
            }
        }
    }

    "positive tabindex sorts before tabindex=0 / unset" in run {
        // Page has <input id='a'/><input id='b' tabindex='2'/><input id='c' tabindex='1'/>.
        // From document.body (no element initially focused), Tab order should be:
        //   c (tabindex=1) → b (tabindex=2) → a (natural)
        withBrowser {
            onPage("<input id='a'/><input id='b' tabindex='2'/><input id='c' tabindex='1'/>") {
                Browser.eval("document.body.focus(); document.activeElement && document.activeElement.blur()").andThen {
                    Browser.press(Key.Tab).andThen {
                        Browser.assertFocused(Browser.Selector.id("c")).andThen {
                            Browser.press(Key.Tab).andThen {
                                Browser.assertFocused(Browser.Selector.id("b")).andThen {
                                    Browser.press(Key.Tab).andThen {
                                        Browser.assertFocused(Browser.Selector.id("a")).map(_ => succeed)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "Tab on a page with no focusables is a no-op (no abort)" in run {
        withBrowser {
            onPage("<p>nothing focusable here</p>") {
                Browser.press(Key.Tab).map(_ => succeed)
            }
        }
    }

    "Shift+Tab walks backward" in run {
        withBrowser {
            onPage("<input id='a'/><input id='b'/><input id='c'/>") {
                Browser.eval("document.getElementById('b').focus()").andThen {
                    Browser.press(Key.Tab, Browser.KeyModifiers(shift = true)).andThen {
                        Browser.assertFocused(Browser.Selector.id("a")).map(_ => succeed)
                    }
                }
            }
        }
    }

    "Shift+Tab from first focusable wraps to last" in run {
        withBrowser {
            onPage("<input id='a'/><input id='b'/><input id='c'/>") {
                Browser.eval("document.getElementById('a').focus()").andThen {
                    Browser.press(Key.Tab, Browser.KeyModifiers(shift = true)).andThen {
                        Browser.assertFocused(Browser.Selector.id("c")).map(_ => succeed)
                    }
                }
            }
        }
    }

    "selector-scoped press(sel, Key.Tab) advances focus from that selector" in run {
        // Without prior focus on 'a', call press(Selector.id("a"), Key.Tab) and confirm 'b' is focused.
        // The selector-scoped press first focuses 'a' (actionable side-effect), then Tab advances to 'b'.
        withBrowser {
            onPage("<input id='a'/><input id='b'/>") {
                Browser.press(Browser.Selector.id("a"), Browser.Key.Tab).andThen {
                    Browser.assertFocused(Browser.Selector.id("b")).map(_ => succeed)
                }
            }
        }
    }

    "Tab still emits a real keydown event for page listeners" in run {
        // Regression: shim runs AFTER keydown/keyup dispatch; this test ensures we didn't replace the dispatch with only the shim.
        withBrowser {
            onPage(
                """<input id='a' autofocus/><input id='b'/>
                  |<script>
                  |  window.__tabKeydown = 0;
                  |  document.addEventListener('keydown', function(e) {
                  |    if (e.key === 'Tab') window.__tabKeydown += 1;
                  |  });
                  |</script>""".stripMargin
            ) {
                Browser.press(Key.Tab).andThen {
                    Browser.eval("window.__tabKeydown").map { v =>
                        assert(v == "1", s"Expected Tab keydown listener to fire exactly once, got '$v'")
                    }
                }
            }
        }
    }

    // ---- withActionable requireEnabled flag (press / hover skip the disabled probe) ----

    // press / hover are now valid against disabled targets; real browsers still fire keydown/keyup/mouseover events
    // against disabled controls. The actionability gate's other arms (visibility, attached, stability, hittable) all
    // still run for press/hover; only the disabled probe is skipped.

    "press(disabledCheckbox, Key.Enter) does not toggle and does not abort" in run {
        // `press` must not raise on a disabled target; it is valid against disabled controls.
        withBrowser {
            onPage("<input type='checkbox' id='c' disabled/>") {
                // Best-effort focus: disabled inputs may decline focus, either outcome is acceptable as long as press does not abort.
                Browser.eval("document.getElementById('c').focus()").andThen {
                    Browser.press(Browser.Selector.id("c"), Browser.Key.Enter).andThen {
                        Browser.eval("document.getElementById('c').checked").map { v =>
                            assert(v == "false", s"Expected disabled checkbox to remain unchecked, got '$v'")
                        }
                    }
                }
            }
        }
    }

    "press(disabledSelect, Key.ArrowDown) does not change value and does not abort" in run {
        withBrowser {
            onPage("<select id='s' disabled><option value='a'>A</option><option value='b'>B</option></select>") {
                Browser.press(Browser.Selector.id("s"), Browser.Key.ArrowDown).andThen {
                    Browser.eval("document.getElementById('s').value").map { v =>
                        assert(v == "a", s"Expected disabled select value to remain 'a', got '$v'")
                    }
                }
            }
        }
    }

    "hover(disabledButton) is harmless and does not abort" in run {
        // hover on a disabled button must not raise BrowserElementNotActionableException; real browsers fire mouseover
        // against disabled controls (tooltips and :hover styles still need to work).
        withBrowser {
            onPage("<button id='b' disabled style='width:80px;height:30px'>x</button>") {
                Browser.hover(Browser.Selector.id("b")).map(_ => succeed)
            }
        }
    }

    "click(disabledButton) STILL aborts NotActionable (regression)" in run {
        // Catches an over-broad relaxation that flips requireEnabled = false for click too.
        withBrowser {
            onPage("<button id='b' disabled style='width:80px;height:30px'>x</button>") {
                expectNotActionable(
                    Browser.click(Browser.Selector.id("b")),
                    Reason.Disabled(Reason.DisabledKind.Attribute)
                )
            }
        }
    }

    "fill(disabledInput, 'x') STILL aborts NotActionable (regression)" in run {
        // Catches an over-broad relaxation that flips requireEnabled = false for fill too.
        withBrowser {
            onPage("<input id='t' disabled style='width:120px;height:24px'/>") {
                expectNotActionable(
                    Browser.fill(Browser.Selector.id("t"), "x"),
                    Reason.Disabled(Reason.DisabledKind.Attribute)
                )
            }
        }
    }

    // ---- Space activates button / checkbox / radio (click-synthesis JS shim) ----

    // Real browsers fire a synthetic `click` on the focused element on Space `keyup` for <button>, <input type="checkbox">,
    // and <input type="radio">; CDP's synthetic Space keystroke does not. The shim runs after keyDown/keyUp dispatch and
    // only invokes `el.click()` when document.activeElement is one of those activatable types; text inputs still receive
    // a literal space via Chromium's keyDown text field.

    "Space on a focused button fires onClick" in run {
        withBrowser {
            onPage(
                """<button id='b' onclick='window.__clicks=(window.__clicks||0)+1'>go</button>"""
            ) {
                Browser.eval("document.getElementById('b').focus()").andThen {
                    Browser.press(Key.Space).andThen {
                        Browser.eval("window.__clicks").map { v =>
                            assert(v == "1", s"Expected onClick to fire exactly once on Space, got '$v'")
                        }
                    }
                }
            }
        }
    }

    "Space on a focused checkbox toggles checked" in run {
        withBrowser {
            onPage("<input type='checkbox' id='c'/>") {
                Browser.eval("document.getElementById('c').focus()").andThen {
                    Browser.press(Key.Space).andThen {
                        Browser.eval("document.getElementById('c').checked").map { v1 =>
                            assert(v1 == "true", s"Expected checkbox to be checked after first Space, got '$v1'")
                        }.andThen {
                            Browser.press(Key.Space).andThen {
                                Browser.eval("document.getElementById('c').checked").map { v2 =>
                                    assert(v2 == "false", s"Expected checkbox to be unchecked after second Space, got '$v2'")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "Space on a focused radio activates" in run {
        withBrowser {
            onPage(
                "<input type='radio' name='r' id='r1'/><input type='radio' name='r' id='r2'/>"
            ) {
                Browser.eval("document.getElementById('r1').focus()").andThen {
                    Browser.press(Key.Space).andThen {
                        Browser.eval("document.getElementById('r1').checked").map { v1 =>
                            assert(v1 == "true", s"Expected r1 to be checked after Space, got '$v1'")
                        }.andThen {
                            Browser.eval("document.getElementById('r2').checked").map { v2 =>
                                assert(v2 == "false", s"Expected r2 to remain unchecked, got '$v2'")
                            }
                        }
                    }
                }
            }
        }
    }

    "Space on a focused text input inserts a literal space (no click synthesis)" in run {
        // Regression: page has <input id='t' value='ab'/>. Caret at position 2 (end). After Space the value must be 'ab '.
        // Catches a shim that synthesizes click on every Space regardless of active-element type.
        withBrowser {
            onPage("<input id='t' value='ab'/>") {
                Browser.eval(
                    "document.getElementById('t').focus(); document.getElementById('t').setSelectionRange(2,2)"
                ).andThen {
                    Browser.press(Key.Space).andThen {
                        Browser.eval("document.getElementById('t').value").map { v =>
                            assert(v == "ab ", s"Expected 'ab ' after Space on text input, got '$v'")
                        }
                    }
                }
            }
        }
    }

    "Space on a non-focused page is a no-op" in run {
        // Boundary: page has <button id='b'/> but body has focus. Press Space and assert no click fires.
        withBrowser {
            onPage(
                """<button id='b' onclick='window.__clicks=(window.__clicks||0)+1'>go</button>"""
            ) {
                // Ensure body is focused (no element is the active element other than body).
                Browser.eval("document.activeElement && document.activeElement.blur && document.activeElement.blur()").andThen {
                    Browser.press(Key.Space).andThen {
                        Browser.eval("(window.__clicks||0).toString()").map { v =>
                            assert(v == "0", s"Expected no click when no activatable element is focused, got '$v'")
                        }
                    }
                }
            }
        }
    }

    "Space on a disabled button does not fire onClick" in run {
        // press on disabled elements is allowed; the synthesis shim handles Space-on-disabled by delegating to
        // HTMLElement.click(), which is a no-op on disabled elements per HTML spec. This test guards against a
        // regression that uses dispatchEvent('click') directly (which would fire even on disabled targets).
        withBrowser {
            onPage(
                """<button id='b' disabled onclick='window.__clicks=(window.__clicks||0)+1'>go</button>"""
            ) {
                Browser.press(Browser.Selector.id("b"), Browser.Key.Space).andThen {
                    Browser.eval("(window.__clicks||0).toString()").map { v =>
                        assert(v == "0", s"Expected no click on disabled button, got '$v'")
                    }
                }
            }
        }
    }

    // ---- cursor preservation across repeated press calls ----

    // The selector-scoped press now probes `document.activeElement === el` and skips the focus call when the target is already
    // focused. This preserves the caret position between consecutive presses (CDP DOM.focus and JS el.focus() both reset the
    // caret to position 0). The unified fill leaves the caret at end of value, so the natural fill -> press -> press
    // flow exercises the "already" arm; the cursor stays where fill left it.

    "sequential press of two characters preserves caret" in run {
        // Two consecutive presses on the same focused input must append in order; re-focusing between presses would reset the caret
        // to position 0 and produce reversed output. `fill("")` primes focus + caret-at-0; the press sequence exercises the
        // already-focused arm of the actionability gate.
        withBrowser {
            onPage("<input id='t' type='text' />") {
                Browser.fill(Browser.Selector.id("t"), "").andThen {
                    Browser.press(Browser.Selector.id("t"), Browser.Key('a')).andThen {
                        Browser.press(Browser.Selector.id("t"), Browser.Key('b')).andThen {
                            Browser.eval("document.getElementById('t').value").map { v =>
                                assert(v == "ab", s"Expected 'ab' (caret advances) but got '$v'")
                            }
                        }
                    }
                }
            }
        }
    }

    "sequential press of three characters preserves caret" in run {
        // Catches partial fixes that handle 2-press but miss 3+.
        withBrowser {
            onPage("<input id='t' type='text' />") {
                Browser.fill(Browser.Selector.id("t"), "").andThen {
                    Browser.press(Browser.Selector.id("t"), Browser.Key('a')).andThen {
                        Browser.press(Browser.Selector.id("t"), Browser.Key('b')).andThen {
                            Browser.press(Browser.Selector.id("t"), Browser.Key('c')).andThen {
                                Browser.eval("document.getElementById('t').value").map { v =>
                                    assert(v == "abc", s"Expected 'abc' (caret advances) but got '$v'")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "press after Browser.fill preserves caret at end" in run {
        // fill leaves the caret at end of value (position 5 for "hello"); press skips re-focus when the target is
        // already focused, so Backspace deletes from cursor position 5 (the last character).
        withBrowser {
            onPage("<input id='t' type='text' />") {
                Browser.fill(Browser.Selector.id("t"), "hello").andThen {
                    Browser.press(Browser.Selector.id("t"), Browser.Key.Backspace).andThen {
                        Browser.eval("document.getElementById('t').value").map { v =>
                            assert(v == "hell", s"Expected 'hell' after Backspace at end of 'hello' but got '$v'")
                        }
                    }
                }
            }
        }
    }

    "press on an unfocused element still focuses it (regression)" in run {
        // Confirms the focus path still runs when the JS probe returns 'needs_focus'. Catches a regression that always
        // skips the focus.
        withBrowser {
            onPage("<input id='t' type='text' />") {
                // Do NOT pre-focus. Active element starts as document.body; the press must focus 't' before dispatching.
                Browser.press(Browser.Selector.id("t"), Browser.Key('a')).andThen {
                    Browser.eval("document.getElementById('t').value").map { v =>
                        assert(v == "a", s"Expected 'a' (focus path ran) but got '$v'")
                    }
                }
            }
        }
    }

    "press on element B when element A is focused focuses B first" in run {
        // Catches a buggy probe that returns 'already' whenever ANY element is focused rather than 'this element is focused'.
        // a is autofocused; press targets b; the probe must say 'needs_focus' for b and the focus path must run.
        withBrowser {
            onPage("<input id='a' autofocus/><input id='b'/>") {
                Browser.press(Browser.Selector.id("b"), Browser.Key('x')).andThen {
                    Browser.eval("document.getElementById('b').value").map { vb =>
                        assert(vb == "x", s"Expected b.value='x' (focus moved to b) but got '$vb'")
                    }.andThen {
                        Browser.eval("document.getElementById('a').value").map { va =>
                            assert(va == "", s"Expected a.value='' (a never received the key) but got '$va'")
                        }
                    }
                }
            }
        }
    }

    "press on detached element aborts NotAttached" in run {
        // Boundary: page is empty. The actionability gate aborts before the cursor-check probe can observe a missing node;
        // confirms the probe is scheduled INSIDE withActionable, not outside.
        withBrowser {
            onPage("<div></div>") {
                expectNotActionable(
                    Browser.press(Browser.Selector.id("nope"), Browser.Key('a')),
                    Reason.NotAttached
                )
            }
        }
    }

    // ---- keyDown / keyUp raw dispatch ----

    "keyDown dispatches a keydown event without a corresponding keyup" in run {
        withBrowser {
            onPage(
                """<div id='log'>none</div>
                  |<script>
                  |  window.__keydowns = 0;
                  |  window.__keyups   = 0;
                  |  document.addEventListener('keydown', function() { window.__keydowns++; });
                  |  document.addEventListener('keyup',   function() { window.__keyups++;   });
                  |</script>""".stripMargin
            ) {
                Browser.keyDown(Browser.Key('a')).andThen {
                    Browser.eval("window.__keydowns + '|' + window.__keyups").map { v =>
                        v.split('|').toList match
                            case List(downs, ups) =>
                                assert(
                                    downs == "1",
                                    s"Expected 1 keydown event after keyDown but got $downs"
                                )
                                assert(
                                    ups == "0",
                                    s"Expected 0 keyup events after keyDown (no corresponding keyUp was called) but got $ups"
                                )
                            case other =>
                                fail(s"Unexpected eval result: '$v'")
                    }
                }
            }
        }
    }

    "keyDown then keyUp produces the canonical down→up sequence" in run {
        withBrowser {
            onPage(
                """<script>
                  |  window.__keydowns = 0;
                  |  window.__keyups   = 0;
                  |  document.addEventListener('keydown', function() { window.__keydowns++; });
                  |  document.addEventListener('keyup',   function() { window.__keyups++;   });
                  |</script>""".stripMargin
            ) {
                Browser.keyDown(Browser.Key('a')).andThen {
                    Browser.keyUp(Browser.Key('a')).andThen {
                        Browser.eval("window.__keydowns + '|' + window.__keyups").map { v =>
                            v.split('|').toList match
                                case List(downs, ups) =>
                                    assert(downs == "1", s"Expected 1 keydown event but got $downs")
                                    assert(ups == "1", s"Expected 1 keyup event but got $ups")
                                case other =>
                                    fail(s"Unexpected eval result: '$v'")
                        }
                    }
                }
            }
        }
    }

    "keyUp without preceding keyDown still dispatches a keyup event" in run {
        withBrowser {
            onPage(
                """<script>
                  |  window.__keyups = 0;
                  |  document.addEventListener('keyup', function() { window.__keyups++; });
                  |</script>""".stripMargin
            ) {
                Browser.keyUp(Browser.Key('a')).andThen {
                    Browser.eval("window.__keyups").map { v =>
                        assert(v == "1", s"Expected exactly 1 keyup event from standalone keyUp but got '$v'")
                    }
                }
            }
        }
    }

    // ---- regression coverage ----

    "press('a') emits keydown, keypress, keyup in that order" in run {
        // The keypress event fires only on printable keys per HTML spec; pin the actual sequence so future modifier work can't
        // silently drop keypress on the printable path. If keypress doesn't fire (Chromium CDP quirk), the failure message records
        // the actual sequence so the contract is still pinned.
        withBrowser {
            onPage("<input id='inp' autofocus />") {
                Browser.eval("""
                    window.__events = [];
                    document.addEventListener('keydown',  () => window.__events.push('keydown'));
                    document.addEventListener('keypress', () => window.__events.push('keypress'));
                    document.addEventListener('keyup',    () => window.__events.push('keyup'));
                """).andThen {
                    Browser.eval("document.getElementById('inp').focus()")
                        .andThen(Browser.press(Browser.Selector.id("inp"), Browser.Key('a')))
                        .andThen {
                            Browser.eval("window.__events.join(',')").map { events =>
                                assert(
                                    events == "keydown,keypress,keyup",
                                    s"expected 'keydown,keypress,keyup' for printable key but got '$events'"
                                )
                            }
                        }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // press(selector, key) transparent fallback to document.activeElement when the
    // selector is stale (e.g. SPA replaced the input mid-flight).
    // -------------------------------------------------------------------------

    "press(selector, key) falls back to document.activeElement when the selector is stale" in run {
        // Fixture: a script destroys the original #target node + inserts a NEW one with the same id
        // and focuses it. press(Selector.id("target"), Key.Enter) targets the OLD node; actionability
        // says NotAttached; and should transparently fall back to dispatching against activeElement,
        // which is the newly-focused replacement input. The form's submit handler captures the keydown
        // and stamps "done" into #out.
        withBrowser {
            onPage(
                """<form id="f" onsubmit="event.preventDefault(); document.getElementById('out').textContent='done'">
                  |  <input id="target" autofocus />
                  |  <span id="out"></span>
                  |</form>
                  |<script>
                  |  // SPA-like replacement: destroy + recreate the input under the same id, focus it,
                  |  // then resolve so the test's `press(selector, Enter)` runs against the stale node.
                  |  const old = document.getElementById('target');
                  |  old.remove();
                  |  const fresh = document.createElement('input');
                  |  fresh.id = 'target';
                  |  document.getElementById('f').insertBefore(fresh, document.getElementById('out'));
                  |  fresh.focus();
                  |</script>""".stripMargin
            ) {
                Browser.press(Browser.Selector.id("target"), Browser.Key.Enter).andThen {
                    Browser.text(Browser.Selector.id("out")).map { out =>
                        assert(
                            out == "done",
                            s"expected the stale-selector fallback to dispatch Enter into activeElement (form submit handler stamps 'done'); got '$out'"
                        )
                    }
                }
            }
        }
    }

end BrowserKeyboardTest
