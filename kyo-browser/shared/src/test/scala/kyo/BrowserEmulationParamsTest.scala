package kyo

import kyo.internal.EmulatedMediaFeature
import kyo.internal.SetEmulatedMediaParams

/** Pure unit tests for the `withEmulation` param-composition contract.
  *
  * All tests are synchronous (no Chrome, no `withBrowser`). They call `Browser.emulatedMediaParams` and
  * `Browser.clearEmulatedMediaParams` directly and assert concrete values on the returned
  * `SetEmulatedMediaParams`. The composed feature list IS the CDP wire contract that `withEmulation` sends to
  * `Emulation.setEmulatedMedia`, so these asserts pin the exact contract: a feature is emitted only when the
  * caller requested it, and the no-prior restore clears every override rather than forcing a value.
  *
  * An end-to-end behavioral test cannot discriminate this contract on headless Chrome: the host's
  * `prefers-reduced-motion` is `no-preference`, the same value an unconditional apply would force, and
  * `setEmulatedMedia` is replace-semantics, so a raw-CDP baseline is cleared by the apply regardless. The pure
  * contract test here is the genuine regression guard. No reflection: the function is called and its result is
  * asserted. Feature comparisons decompose to `(name, value)` String pairs because the internal CDP case classes
  * derive `Schema` but not `CanEqual`.
  */
class BrowserEmulationParamsTest extends Test:

    // Convenience accessors for the CDP wire strings of the public enums, the exact values withEmulation feeds
    // into emulatedMediaParams (it calls colorScheme.map(_.wire) / media.map(_.wire)).
    private val dark: Maybe[String]   = Present(Browser.ColorScheme.Dark).map(_.wire)
    private val print: Maybe[String]  = Present(Browser.MediaType.Print).map(_.wire)
    private val screen: Maybe[String] = Present(Browser.MediaType.Screen).map(_.wire)

    // Decompose the feature list into comparable (name, value) String pairs.
    private def pairsOf(params: SetEmulatedMediaParams): Seq[(String, String)] =
        params.features match
            case Present(fs) => fs.map(f => (f.name, f.value))
            case Absent      => fail(s"expected features to be Present but got Absent in $params")

    // A color-scheme-only apply (reducedMotion = false) must emit ONLY prefers-color-scheme and NO
    // prefers-reduced-motion feature, so an unrelated media feature is never perturbed.
    "color-scheme-only with reducedMotion=false emits only prefers-color-scheme" in {
        val params = Browser.emulatedMediaParams(media = Absent, colorScheme = dark, reducedMotion = false)
        val pairs  = pairsOf(params)
        assert(
            pairs == Seq(("prefers-color-scheme", "dark")),
            s"expected exactly prefers-color-scheme=dark and NO prefers-reduced-motion entry but got $pairs"
        )
        assert(
            !pairs.exists(_._1 == "prefers-reduced-motion"),
            s"a prefers-reduced-motion feature must not leak into a color-scheme-only call: $pairs"
        )
        assert(params.media == Absent, s"expected no media override but got ${params.media}")
        succeed
    }

    // reducedMotion = true must add prefers-reduced-motion: reduce alongside the requested color-scheme.
    "color-scheme with reducedMotion=true adds prefers-reduced-motion=reduce" in {
        val params = Browser.emulatedMediaParams(media = Absent, colorScheme = dark, reducedMotion = true)
        val pairs  = pairsOf(params)
        assert(
            pairs.contains(("prefers-reduced-motion", "reduce")),
            s"expected prefers-reduced-motion=reduce when reducedMotion=true but got $pairs"
        )
        assert(
            pairs.contains(("prefers-color-scheme", "dark")),
            s"expected prefers-color-scheme=dark to remain present but got $pairs"
        )
        succeed
    }

    // Requesting nothing (no color-scheme, no reduced-motion, no media) emits an EMPTY feature list: nothing is
    // emulated, so no prefers-* feature is forced.
    "nothing requested emits empty features and no media" in {
        val params = Browser.emulatedMediaParams(media = Absent, colorScheme = Absent, reducedMotion = false)
        assert(
            pairsOf(params) == Seq.empty,
            s"expected an empty feature list when nothing is emulated but got ${params.features}"
        )
        assert(params.media == Absent, s"expected no media override but got ${params.media}")
        succeed
    }

    // All three requested together: media on the top-level field, both features present with correct values.
    "all three requested compose media plus both features with correct values" in {
        val params = Browser.emulatedMediaParams(media = print, colorScheme = dark, reducedMotion = true)
        val pairs  = pairsOf(params)
        assert(params.media == Present("print"), s"expected media=print on the top-level field but got ${params.media}")
        assert(
            pairs.toSet == Set(("prefers-color-scheme", "dark"), ("prefers-reduced-motion", "reduce")),
            s"expected both prefers-color-scheme=dark and prefers-reduced-motion=reduce but got $pairs"
        )
        succeed
    }

    // The no-prior restore CLEAR params drop every media-feature override back to the host: empty media plus an
    // empty features list is the true clear, rather than a non-empty feature that would leave an active forced
    // override after exit.
    "clear params are empty media and empty features" in {
        val params = Browser.clearEmulatedMediaParams
        assert(params.media == Present(""), s"expected empty media on the clear params but got ${params.media}")
        assert(
            pairsOf(params) == Seq.empty,
            s"the clear must send an EMPTY feature list (no forced override) but got ${params.features}"
        )
        succeed
    }

    // The NoPreference color-scheme clears via the empty-string wire value (W3C dropped no-preference as a settable
    // value). The feature is still Present (so the page's color-scheme override is cleared, not left untouched).
    "NoPreference color-scheme emits an empty-string prefers-color-scheme feature" in {
        val noPref = Present(Browser.ColorScheme.NoPreference).map(_.wire)
        val params = Browser.emulatedMediaParams(media = screen, colorScheme = noPref, reducedMotion = false)
        val pairs  = pairsOf(params)
        assert(
            pairs == Seq(("prefers-color-scheme", "")),
            s"expected a single empty-string prefers-color-scheme feature but got $pairs"
        )
        assert(params.media == Present("screen"), s"expected media=screen but got ${params.media}")
        succeed
    }

end BrowserEmulationParamsTest
