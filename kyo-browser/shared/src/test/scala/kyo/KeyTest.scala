package kyo

import kyo.Browser.Key.value
import kyo.internal.KeyInfo

/** Pure unit tests for [[Key]]: no browser, no Async.delay, no Thread.sleep.
  *
  * Observable shape tested: the underlying WebDriver string (`.value`) that flows into CDP `Input.dispatchKeyEvent` as `key` /
  * `windowsVirtualKeyCode`. All expected encodings are taken from the W3C WebDriver key table and the CDP mapping in `kyo.internal.mapKey`.
  */
class KeyTest extends BaseBrowserTest:

    // ── Key.apply(char) ────────────────────────────────────────────────────

    "Key.apply('a') encodes as the character string" in {
        assert(Browser.Key.apply('a').value == "a")
    }

    "Key.apply('A') encodes as the character string" in {
        assert(Browser.Key.apply('A').value == "A")
    }

    "Key.apply('1') encodes as the character string" in {
        assert(Browser.Key.apply('1').value == "1")
    }

    "Key.apply(' ') encodes as a single space string" in {
        assert(Browser.Key.apply(' ').value == " ")
    }

    "Key.apply('$') encodes as the character string" in {
        assert(Browser.Key.apply('$').value == "$")
    }

    // ── Named keys: canonical (key, webdriver-codepoint, DOM-keyCode) ────
    // Order matches source declaration order in Key.scala.
    // Expected codepoints are taken from the W3C WebDriver key table.
    // DOM keyCode values match the CDP windowsVirtualKeyCode used in kyo.internal.mapKey.

    "Key.Enter encodes as WebDriver U+E006 (Enter / keyCode 13)" in {
        assert(Browser.Key.Enter.value == "")
        assert(Browser.Key.Enter.value.length == 1)
        assert(Browser.Key.Enter.value.head.toInt == 0xe006)
    }

    "Key.Tab encodes as WebDriver U+E004 (Tab / keyCode 9)" in {
        assert(Browser.Key.Tab.value == "")
        assert(Browser.Key.Tab.value.head.toInt == 0xe004)
    }

    "Key.Backspace encodes as WebDriver U+E003 (Backspace / keyCode 8)" in {
        assert(Browser.Key.Backspace.value == "")
        assert(Browser.Key.Backspace.value.head.toInt == 0xe003)
    }

    "Key.Escape encodes as WebDriver U+E00C (Escape / keyCode 27)" in {
        assert(Browser.Key.Escape.value == "")
        assert(Browser.Key.Escape.value.head.toInt == 0xe00c)
    }

    "Key.ArrowUp encodes as WebDriver U+E013 (ArrowUp / keyCode 38)" in {
        assert(Browser.Key.ArrowUp.value == "")
        assert(Browser.Key.ArrowUp.value.head.toInt == 0xe013)
    }

    "Key.ArrowDown encodes as WebDriver U+E015 (ArrowDown / keyCode 40)" in {
        assert(Browser.Key.ArrowDown.value == "")
        assert(Browser.Key.ArrowDown.value.head.toInt == 0xe015)
    }

    "Key.ArrowLeft encodes as WebDriver U+E012 (ArrowLeft / keyCode 37)" in {
        assert(Browser.Key.ArrowLeft.value == "")
        assert(Browser.Key.ArrowLeft.value.head.toInt == 0xe012)
    }

    "Key.ArrowRight encodes as WebDriver U+E014 (ArrowRight / keyCode 39)" in {
        assert(Browser.Key.ArrowRight.value == "")
        assert(Browser.Key.ArrowRight.value.head.toInt == 0xe014)
    }

    "Key.Home encodes as WebDriver U+E011 (Home / keyCode 36)" in {
        assert(Browser.Key.Home.value == "")
        assert(Browser.Key.Home.value.head.toInt == 0xe011)
    }

    "Key.End encodes as WebDriver U+E010 (End / keyCode 35)" in {
        assert(Browser.Key.End.value == "")
        assert(Browser.Key.End.value.head.toInt == 0xe010)
    }

    "Key.PageUp encodes as WebDriver U+E00E (PageUp / keyCode 33)" in {
        assert(Browser.Key.PageUp.value == "")
        assert(Browser.Key.PageUp.value.head.toInt == 0xe00e)
    }

    "Key.PageDown encodes as WebDriver U+E00F (PageDown / keyCode 34)" in {
        assert(Browser.Key.PageDown.value == "")
        assert(Browser.Key.PageDown.value.head.toInt == 0xe00f)
    }

    "Key.Delete encodes as WebDriver U+E017 (Delete / keyCode 46)" in {
        assert(Browser.Key.Delete.value == "")
        assert(Browser.Key.Delete.value.head.toInt == 0xe017)
    }

    "Key.Space encodes as a plain ASCII space (keyCode 32)" in {
        assert(Browser.Key.Space.value == " ")
        assert(Browser.Key.Space.value.head.toInt == 0x0020)
    }

    "Key.Shift encodes as WebDriver U+E008 (Shift / keyCode 16)" in {
        assert(Browser.Key.Shift.value == "")
        assert(Browser.Key.Shift.value.head.toInt == 0xe008)
    }

    "Key.Control encodes as WebDriver U+E009 (Control / keyCode 17)" in {
        assert(Browser.Key.Control.value == "")
        assert(Browser.Key.Control.value.head.toInt == 0xe009)
    }

    "Key.Alt encodes as WebDriver U+E00A (Alt / keyCode 18)" in {
        assert(Browser.Key.Alt.value == "")
        assert(Browser.Key.Alt.value.head.toInt == 0xe00a)
    }

    "Key.Meta encodes as WebDriver U+E03D (Meta / keyCode 91)" in {
        assert(Browser.Key.Meta.value == "")
        assert(Browser.Key.Meta.value.head.toInt == 0xe03d)
    }

    // ── Uniqueness sanity check ────────────────────────────────────────────
    // All 18 named keys must map to distinct underlying values; a collision
    // would mean two named keys dispatch identical CDP events.

    "all 18 named keys have distinct underlying values" in {
        val all = Seq(
            Browser.Key.Enter,
            Browser.Key.Tab,
            Browser.Key.Backspace,
            Browser.Key.Escape,
            Browser.Key.ArrowUp,
            Browser.Key.ArrowDown,
            Browser.Key.ArrowLeft,
            Browser.Key.ArrowRight,
            Browser.Key.Home,
            Browser.Key.End,
            Browser.Key.PageUp,
            Browser.Key.PageDown,
            Browser.Key.Delete,
            Browser.Key.Space,
            Browser.Key.Shift,
            Browser.Key.Control,
            Browser.Key.Alt,
            Browser.Key.Meta
        )
        assert(all.map(_.value).distinct.length == 18)
    }

    // ── Key.apply round-trips ─────────────────────────────────────────────
    // A Key constructed from a char must equal a Key constructed from the same char.

    "Key.apply produces equal Keys for the same char" in {
        assert(Browser.Key.apply('z').value == Browser.Key.apply('z').value)
    }

    "Key.apply produces distinct Keys for different chars" in {
        assert(Browser.Key.apply('x').value != Browser.Key.apply('y').value)
    }

    // ── Printable chars: CDP `code` (physical key) + windowsVirtualKeyCode ──
    // A printable key must dispatch a real DOM `code` ("KeyX", "Digit1", ...) and the
    // matching virtual keycode, not the raw character. Chrome rejects an unrecognized
    // `code` ("unrecognized code string 'x'"), so the wrong value can drop the keystroke.

    "mapKey('x') uses physical code KeyX and virtual keycode 88" in {
        val info = KeyInfo.mapKey(Browser.Key('x'))
        assert(info.domCode == "KeyX")
        assert(info.keyCode == 88)
        assert(info.keyName == "x")
    }

    "mapKey('A') uses physical code KeyA and virtual keycode 65" in {
        val info = KeyInfo.mapKey(Browser.Key('A'))
        assert(info.domCode == "KeyA")
        assert(info.keyCode == 65)
    }

    "mapKey('1') uses physical code Digit1 and virtual keycode 49" in {
        val info = KeyInfo.mapKey(Browser.Key('1'))
        assert(info.domCode == "Digit1")
        assert(info.keyCode == 49)
    }

    "mapKey('!') uses physical code Digit1 and virtual keycode 49" in {
        val info = KeyInfo.mapKey(Browser.Key('!'))
        assert(info.domCode == "Digit1")
        assert(info.keyCode == 49)
    }

    "mapKey('.') uses physical code Period and virtual keycode 190" in {
        val info = KeyInfo.mapKey(Browser.Key('.'))
        assert(info.domCode == "Period")
        assert(info.keyCode == 190)
    }

end KeyTest
