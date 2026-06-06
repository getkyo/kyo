package kyo.internal

import kyo.*

/** A keystroke that the browser interprets via the WebDriver-style key code map.
  *
  * `Key` is an opaque alias over `String`, where the underlying string is either an ordinary character (typed verbatim) or a single
  * Private-Use-Area code point taken from the WebDriver keyboard mapping (U+E000 - U+E03D). Modeling keys as strings keeps the wire format
  * trivial for `Browser.press` and friends while still allowing the type system to distinguish a keystroke from arbitrary text.
  *
  * Two kinds of values flow through the type:
  *
  *   - Printable characters obtained via [[Key.apply]], representing literal keystrokes.
  *   - Named constants such as [[Key.Enter]] and [[Key.ArrowUp]] for non-printable keys.
  *
  * Modifier keys ([[Key.Shift]], [[Key.Control]], [[Key.Alt]], [[Key.Meta]]) follow the same encoding and are sent as standalone
  * keystrokes; combination semantics are defined by the sending action rather than the `Key` value itself.
  *
  * @see
  *   [[kyo.Browser]] for actions that consume `Key` values, such as `press`.
  * @see
  *   [[kyo.Browser.Selector]] for choosing the element that receives the keystroke.
  */
/** Opaque (not `final case class`): zero runtime overhead at the call site, and the WebDriver-encoded `String` is the natural underlying
  * representation.
  */
opaque type Key = String

/** Companion containing the [[Key.apply]] constructor and the named WebDriver keys. */
object Key:
    /** Wraps a single printable character as a [[Key]]. */
    def apply(char: Char): Key                        = char.toString
    extension (k: Key) private[kyo] def value: String = k

    /** The Enter / Return key. */
    val Enter: Key = ""

    /** The Tab key. */
    val Tab: Key = ""

    /** The Backspace key. */
    val Backspace: Key = ""

    /** The Escape key. */
    val Escape: Key = ""

    /** The Up arrow key. */
    val ArrowUp: Key = ""

    /** The Down arrow key. */
    val ArrowDown: Key = ""

    /** The Left arrow key. */
    val ArrowLeft: Key = ""

    /** The Right arrow key. */
    val ArrowRight: Key = ""

    /** The Home key. */
    val Home: Key = ""

    /** The End key. */
    val End: Key = ""

    /** The Page Up key. */
    val PageUp: Key = ""

    /** The Page Down key. */
    val PageDown: Key = ""

    /** The Delete (forward-delete) key. */
    val Delete: Key = ""

    /** The Space bar. */
    val Space: Key = " "

    /** The Shift modifier key. */
    val Shift: Key = ""

    /** The Control modifier key. */
    val Control: Key = ""

    /** The Alt / Option modifier key. */
    val Alt: Key = ""

    /** The Meta / Command / Windows modifier key. */
    val Meta: Key = ""
end Key

/** Resolved CDP key info for a `Key`.
  *
  * @param keyName
  *   the CDP `key` field (e.g. "Enter", "Shift", "a")
  * @param keyCode
  *   the legacy `windowsVirtualKeyCode`
  * @param text
  *   the character emitted by the key, when applicable. For Enter Chrome requires "\r"; for Tab "\t"; for printable chars the char itself.
  *   Modifier keys and navigation keys carry no text.
  * @param domCode
  *   the CDP `code` field (e.g. "Enter", "ShiftLeft", "KeyA"). Required for many DOM events to fire correctly (e.g. form submit on Enter
  *   requires `code="Enter"`).
  * @param modifierBit
  *   the CDP modifier bitmask contribution for this key (1=Alt, 2=Ctrl, 4=Meta, 8=Shift, 0=non-modifier).
  */
final private[kyo] case class KeyInfo(
    keyName: String,
    keyCode: Int,
    text: Maybe[String],
    domCode: String,
    modifierBit: Int
)

private[kyo] object KeyInfo:

    // --- Interned keys ---

    /** Interned [[KeyInfo]] instances for the 18 named keys. Each call to [[mapKey]] returns the shared `private val` rather than
      * allocating a fresh `KeyInfo` per `keyDown` / `keyUp` / `press`. The dynamic `case other` branch (printable chars / Unicode) still
      * allocates per call; those are unbounded and not worth pre-interning.
      */
    private val EnterKey      = KeyInfo("Enter", 13, Maybe("\r"), "Enter", 0)
    private val TabKey        = KeyInfo("Tab", 9, Maybe("\t"), "Tab", 0)
    private val BackspaceKey  = KeyInfo("Backspace", 8, Absent, "Backspace", 0)
    private val EscapeKey     = KeyInfo("Escape", 27, Absent, "Escape", 0)
    private val ArrowUpKey    = KeyInfo("ArrowUp", 38, Absent, "ArrowUp", 0)
    private val ArrowDownKey  = KeyInfo("ArrowDown", 40, Absent, "ArrowDown", 0)
    private val ArrowLeftKey  = KeyInfo("ArrowLeft", 37, Absent, "ArrowLeft", 0)
    private val ArrowRightKey = KeyInfo("ArrowRight", 39, Absent, "ArrowRight", 0)
    private val HomeKey       = KeyInfo("Home", 36, Absent, "Home", 0)
    private val EndKey        = KeyInfo("End", 35, Absent, "End", 0)
    private val PageUpKey     = KeyInfo("PageUp", 33, Absent, "PageUp", 0)
    private val PageDownKey   = KeyInfo("PageDown", 34, Absent, "PageDown", 0)
    private val DeleteKey     = KeyInfo("Delete", 46, Absent, "Delete", 0)
    private val SpaceKey      = KeyInfo("Space", 32, Maybe(" "), "Space", 0)
    private val ShiftKey      = KeyInfo("Shift", 16, Absent, "ShiftLeft", 8)
    private val ControlKey    = KeyInfo("Control", 17, Absent, "ControlLeft", 2)
    private val AltKey        = KeyInfo("Alt", 18, Absent, "AltLeft", 1)
    private val MetaKey       = KeyInfo("Meta", 91, Absent, "MetaLeft", 4)

    // --- Lookup ---

    /** Maps a Key to its CDP descriptor. Unknown keys fall back to keyCode 0, which is the standard "unidentified" keyCode. */
    private[kyo] def mapKey(key: Key): KeyInfo =
        key.value match
            case "\uE006" => EnterKey
            case "\uE004" => TabKey
            case "\uE003" => BackspaceKey
            case "\uE00C" => EscapeKey
            case "\uE013" => ArrowUpKey
            case "\uE015" => ArrowDownKey
            case "\uE012" => ArrowLeftKey
            case "\uE014" => ArrowRightKey
            case "\uE011" => HomeKey
            case "\uE010" => EndKey
            case "\uE00E" => PageUpKey
            case "\uE00F" => PageDownKey
            case "\uE017" => DeleteKey
            case " "      => SpaceKey
            case "\uE008" => ShiftKey
            case "\uE009" => ControlKey
            case "\uE00A" => AltKey
            case "\uE03D" => MetaKey
            case other if other.length == 1 =>
                val (code, vk) = physicalKey(other.charAt(0))
                KeyInfo(other, vk, Maybe(other), code, 0)
            case other =>
                KeyInfo(other, 0, Maybe(other), other, 0)
    end mapKey

    /** Physical DOM `code` and `windowsVirtualKeyCode` for a printable US-QWERTY character. Chrome rejects a `code` it does not
      * recognize, so synthetic key dispatch must use the real physical code ("KeyX", "Digit1", "Period", ...) rather than the typed
      * character. Shifted variants share the unshifted key's code and keycode ('!' and '1' are both Digit1 / 49). Non-ASCII characters
      * fall back to the character itself with codepoint 0.
      */
    private def physicalKey(c: Char): (String, Int) =
        c match
            case l if l >= 'a' && l <= 'z' => (s"Key${l.toUpper}", l.toUpper.toInt)
            case u if u >= 'A' && u <= 'Z' => (s"Key$u", u.toInt)
            case d if d >= '0' && d <= '9' => (s"Digit$d", d.toInt)
            case '`' | '~'                 => ("Backquote", 192)
            case '!'                       => ("Digit1", 49)
            case '@'                       => ("Digit2", 50)
            case '#'                       => ("Digit3", 51)
            case '$'                       => ("Digit4", 52)
            case '%'                       => ("Digit5", 53)
            case '^'                       => ("Digit6", 54)
            case '&'                       => ("Digit7", 55)
            case '*'                       => ("Digit8", 56)
            case '('                       => ("Digit9", 57)
            case ')'                       => ("Digit0", 48)
            case '-' | '_'                 => ("Minus", 189)
            case '=' | '+'                 => ("Equal", 187)
            case '[' | '{'                 => ("BracketLeft", 219)
            case ']' | '}'                 => ("BracketRight", 221)
            case '\\' | '|'                => ("Backslash", 220)
            case ';' | ':'                 => ("Semicolon", 186)
            case '\'' | '"'                => ("Quote", 222)
            case ',' | '<'                 => ("Comma", 188)
            case '.' | '>'                 => ("Period", 190)
            case '/' | '?'                 => ("Slash", 191)
            case _                         => (c.toString, c.toInt)
    end physicalKey

end KeyInfo
