package kyo.internal

import kyo.*

/** Modifier-key bundle for [[kyo.Browser.press]]. Mirrors the four DOM `KeyboardEvent` modifier flags (`shift`, `ctrl`, `alt`, `meta`)
  * in a single value type, replacing positional booleans on the press API.
  *
  * Construct via the case-class apply, the field-defaulted [[KeyModifiers.of]] factory, or the [[KeyModifiers.none]] zero value.
  */
final case class KeyModifiers(
    shift: Boolean = false,
    ctrl: Boolean = false,
    alt: Boolean = false,
    meta: Boolean = false
) derives CanEqual

object KeyModifiers:
    /** Zero-modifier value; equivalent to `KeyModifiers()`. */
    val none: KeyModifiers = KeyModifiers()

    /** Field-defaulted factory; identical to the case-class apply but documents the named-arg call style. */
    def of(
        shift: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false
    ): KeyModifiers =
        KeyModifiers(shift, ctrl, alt, meta)
end KeyModifiers
