package kyo

/** A language identifier, held as a normalized primary language subtag (for example `en`, `de`).
  *
  * `Locale` is a zero-cost wrapper over the code string. It carries only the primary subtag: [[Locale.apply]]
  * and [[Locale.parse]] lowercase the input and drop any region or script (`de-DE` and `DE` both become `de`),
  * so a locale used as a bundle key, a persisted value, and a `navigator.language` prefix all compare equal.
  *
  * The module is not tied to a fixed set of locales: the consumer decides which locales exist by which bundles
  * it loads and which start locale it passes to [[I18n.init]]. `derives`-style `CanEqual` is provided because a
  * `Signal[Locale]` needs `CanEqual[Locale, Locale]` to detect changes.
  *
  * @see
  *   [[Locale.parse]] for turning a code or BCP-47 tag into a `Locale`
  * @see
  *   [[I18n]] for the translation facade keyed on `Locale`
  */
opaque type Locale = String

object Locale:

    given CanEqual[Locale, Locale] = CanEqual.derived

    /** Wraps a language code, normalized to its lowercase primary subtag. */
    def apply(code: String): Locale = normalize(code)

    extension (locale: Locale)
        /** The normalized language code, for use as a bundle id or persisted value. */
        def code: String = locale

    /** Parses a code or BCP-47 tag (`de`, `de-DE`, `DE`) into a `Locale`. Input with no language subtag (empty
      * or punctuation-only) is [[Absent]] so the caller can apply its own fallback.
      */
    def parse(raw: String): Maybe[Locale] =
        val code = normalize(raw)
        if code.isEmpty then Absent else Present(code)

    private def normalize(raw: String): String =
        raw.trim.toLowerCase.takeWhile(c => c != '-' && c != '_')
end Locale
