package kyo

import kyo.internal.LocalePlatformSpecific

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
  *   [[Locale.default]] for the host environment's language, [[Locale.preferred]] for picking a supported one
  * @see
  *   [[I18n]] for the translation facade keyed on `Locale`
  */
opaque type Locale = String

object Locale:

    given CanEqual[Locale, Locale] = CanEqual.derived

    /** The `und` subtag, registered in the IANA Language Subtag Registry with a scope of `special` for content
      * whose language is undetermined. It is a valid language tag rather than a placeholder string, which is
      * why it can stand wherever a `Locale` is structurally required but none has been established.
      *
      * That is the ambient handle installed before any [[I18n.let]]. The handle carries no bundles, so every
      * key renders its miss marker whatever its locale is; [[default]] is the one to reach for when an actual
      * language is wanted.
      */
    val Undetermined: Locale = "und"

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

    /** The host environment's language, from the first source that names one: the browser's
      * `navigator.language`, the `user.language` property, then the `LC_ALL` and `LANG` environment
      * variables. [[Undetermined]] when none of them does.
      *
      * This is the raw host preference and need not be a locale the application has a bundle for; see
      * [[preferred]] for choosing among the ones it does have.
      */
    def default(using Frame): Locale < Sync =
        for
            browser  <- Sync.Unsafe.defer(LocalePlatformSpecific.browserLanguageTag())
            property <- System.property[String]("user.language", "")
            lcAll    <- System.env[String]("LC_ALL", "")
            lang     <- System.env[String]("LANG", "")
        yield Seq(browser, property, lcAll, lang)
            .foldLeft(Absent: Maybe[Locale])((found, raw) => found.orElse(parse(raw)))
            .getOrElse(Undetermined)

    /** The host locale when `available` contains it, otherwise `fallback`. The usual way to pick a start locale:
      * it honors the reader's environment without ever selecting one that has no bundle behind it.
      */
    def preferred(available: Seq[Locale], fallback: Locale)(using Frame): Locale < Sync =
        default.map(host => if available.contains(host) then host else fallback)

    private def normalize(raw: String): String =
        raw.trim.toLowerCase.takeWhile(c => c != '-' && c != '_')
end Locale
