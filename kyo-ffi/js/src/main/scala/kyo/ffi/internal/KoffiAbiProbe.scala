package kyo.ffi.internal

import kyo.ffi.FfiLoadError
import scala.scalajs.js

/** Runtime ABI probe for the koffi npm package.
  *
  * kyo-ffi's JS backend targets the koffi 2.7.x ABI. A user environment can drift to an older 2.6.x release (missing variadic helpers) or
  * jump to a future 3.x with different marshalling semantics. This probe runs once per JVM/Node session at the first [[KoffiFacade.load]]
  * call and fails fast with [[FfiLoadError.Unsupported]] when either:
  *
  *   - `koffi.version` is absent or outside the supported range (see [[FfiPlatformErrors.KoffiSupportedRange]]);
  *   - any of the methods kyo-ffi's generated code relies on is missing.
  *
  * The probe is factored out of [[KoffiFacade]] so unit tests can drive it against a synthetic `js.Dynamic.literal` without requiring a
  * real koffi install. The `load` call site uses [[probeOnce]] which caches a positive result in a `@volatile Boolean`, subsequent calls
  * are a single field read.
  */
private[ffi] object KoffiAbiProbe:

    /** Methods the kyo-ffi runtime invokes on koffi. `variadic` lives under `koffi.as` rather than a dedicated export, so it is not in this
      * list; every other entry must exist as a callable on the koffi object.
      */
    val RequiredMethods: Seq[String] = Seq(
        "load",
        "errno",
        "proto",
        "pointer",
        "register",
        "unregister",
        "struct",
        "pack",
        "union",
        "as"
    )

    @volatile private var probed: Boolean = false

    /** Probe the real `Koffi` object once per process. First call validates; subsequent calls are a single volatile read. */
    def probeOnce(): Unit =
        if !probed then
            probe(Koffi.asInstanceOf[js.Dynamic])
            probed = true
        end if
    end probeOnce

    /** Visible-for-tests, reset the cached flag so a test can re-probe. Not part of the public API. */
    private[internal] def resetForTest(): Unit =
        probed = false

    /** Core probe. Visible-for-tests so the spec can drive it with a `js.Dynamic.literal` that stands in for koffi.
      *
      * @throws FfiLoadError.Unsupported
      *   when either (a) `koffi.version` is absent or does not satisfy [[FfiPlatformErrors.KoffiSupportedRange]], or (b) any method listed
      *   in [[RequiredMethods]] is missing.
      */
    def probe(koffi: js.Dynamic): Unit =
        val detected = readVersion(koffi)
        if !isSupported(detected) then
            throw new FfiLoadError.Unsupported(FfiPlatformErrors.koffiVersionMismatch(detected))
        end if
        RequiredMethods.foreach { m =>
            val v = koffi.selectDynamic(m)
            if js.isUndefined(v) || v == null || js.typeOf(v) != "function" then
                throw new FfiLoadError.Unsupported(FfiPlatformErrors.koffiMissingMethod(m, detected))
            end if
        }
    end probe

    /** Extract a stable version string from the koffi object. koffi 2.x exposes `koffi.version` as a `string` field (e.g. `"2.7.9"`).
      * Returns `"unknown"` when the field is missing or not a string so callers get a single sentinel to report.
      */
    private def readVersion(koffi: js.Dynamic): String =
        val raw = koffi.selectDynamic("version")
        if js.isUndefined(raw) || raw == null then "unknown"
        else if js.typeOf(raw) == "string" then raw.asInstanceOf[String]
        else "unknown"
    end readVersion

    /** Check `version` against the `^2.7` semver range embedded in [[FfiPlatformErrors.KoffiSupportedRange]]: `major == 2` AND
      * `minor >= 7`.
      *
      * We parse manually instead of depending on a semver npm lib, this code runs under Scala.js in the user's process and should not pull
      * an extra dependency just for one check. Prerelease / build suffixes (e.g. `2.7.0-rc.1`) are ignored for the range check; the leading
      * dotted numeric prefix determines acceptance.
      */
    private def isSupported(version: String): Boolean =
        if version == "unknown" || version.isEmpty then false
        else
            // Strip any non-digit trailing suffix before splitting (`2.7.0-rc.1` → `2.7.0`).
            val core =
                val dash = version.indexOf('-')
                val plus = version.indexOf('+')
                val end =
                    if dash >= 0 && plus >= 0 then math.min(dash, plus)
                    else if dash >= 0 then dash
                    else if plus >= 0 then plus
                    else version.length
                version.substring(0, end)
            end core
            val parts = core.split('.')
            if parts.length < 2 then false
            else
                val majorOpt = parseNonNeg(parts(0))
                val minorOpt = parseNonNeg(parts(1))
                (majorOpt, minorOpt) match
                    case (Some(2), Some(minor)) => minor >= 7
                    case _                      => false
                end match
            end if
        end if
    end isSupported

    private def parseNonNeg(s: String): Option[Int] =
        if s.isEmpty || !s.forall(_.isDigit) then None
        else
            try Some(s.toInt)
            catch case _: NumberFormatException => None
    end parseNonNeg
end KoffiAbiProbe
