package kyo.ffi.internal

import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.ffi.FfiLoadError

/** Runtime ABI + version check embedded in every generated impl initializer, and reused by the manifest-driven
  * direct-load pre-check.
  *
  * Two gates, both routed through the typed [[kyo.ffi.FfiLoadError.AbiMismatch]] carrier (matching
  * [[StructAbiCheck]]) so a caller catches one error type instead of a bare `IllegalStateException`:
  *   - the monotone [[runtimeAbi]] `Int`, the hard binary-compatibility gate; increment it on any
  *     binary-incompatible change to the generated-impl contract.
  *   - a semantic `version` / `minRuntime` comparison read from the native manifest: a bundled native declares the
  *     minimum kyo-ffi runtime it needs, and a runtime OLDER than that floor is an `AbiMismatch`, catchable, not a
  *     crash. Skipped when the runtime version or the manifest floor cannot be determined (for example on JS /
  *     Native, or when running from an exploded classes directory), rather than guessed.
  */
object AbiCheck:

    /** Current ABI version. */
    val runtimeAbi: Int = 1

    /** Verify a generated impl's baked ABI version and its native's `minRuntime` floor.
      *
      * @throws kyo.ffi.FfiLoadError.AbiMismatch
      *   if `generatedAbi` does not match [[runtimeAbi]], or if the bundled native for `bindingFqn` declares a
      *   `minRuntime` newer than the current kyo-ffi runtime.
      */
    def verify(generatedAbi: Int, bindingFqn: String): Unit =
        if generatedAbi != runtimeAbi then
            throw new FfiLoadError.AbiMismatch(
                runtimeAbi.toString,
                generatedAbi.toString,
                FfiErrors.abiMismatch(generatedAbi, runtimeAbi, bindingFqn)
            )
        end if
        verifyRuntimeVersion(bindingFqn)
    end verify

    /** Verify only the manifest `minRuntime` floor for `bindingFqn`, used by the load-time pre-check so a version
      * shortfall surfaces from `Ffi.load[T]` before the impl companion initializes. A no-op when the trait is not
      * in any manifest, the id has no block, or either version is undeterminable.
      *
      * @throws kyo.ffi.FfiLoadError.AbiMismatch
      *   if the bundled native declares a `minRuntime` newer than the current kyo-ffi runtime.
      */
    def verifyRuntimeVersion(bindingFqn: String): Unit =
        NativeManifest.libraryIdFor(bindingFqn) match
            case Present(id) =>
                NativeManifest.entryFor(id) match
                    case Present(entry) => verifyRuntimeFloor(bindingFqn, entry.minRuntime)
                    case Absent         => ()
            case Absent => ()
    end verifyRuntimeVersion

    /** Compare the runtime version against an already-resolved `minRuntime` floor. Exposed so the load-time
      * pre-check, which has the manifest entry in hand, avoids re-resolving it.
      *
      * @throws kyo.ffi.FfiLoadError.AbiMismatch
      *   if `minRuntime` is newer than the current kyo-ffi runtime.
      */
    def verifyRuntimeFloor(bindingFqn: String, minRuntime: String): Unit =
        NativeManifestPlatform.runtimeVersion match
            case Present(runtime) if minRuntime.nonEmpty && runtime.nonEmpty && compareVersions(runtime, minRuntime) < 0 =>
                throw new FfiLoadError.AbiMismatch(
                    minRuntime,
                    runtime,
                    FfiErrors.runtimeVersionTooOld(bindingFqn, minRuntime, runtime)
                )
            case _ => ()
    end verifyRuntimeFloor

    /** Compare two dotted version strings numerically component by component (`"1.2.0"` vs `"1.10"`), padding the
      * shorter with zeros. Each component's leading digits are used, so a pre-release suffix (`"1.2.0-RC1"`) does
      * not derail the comparison; a component with no leading digit counts as zero. Returns a negative number when
      * `a` is older than `b`, zero when equal, positive when newer.
      */
    def compareVersions(a: String, b: String): Int =
        val as = a.split('.')
        val bs = b.split('.')
        val n  = math.max(as.length, bs.length)
        var i  = 0
        var r  = 0
        while i < n && r == 0 do
            val ai = if i < as.length then leadingInt(as(i)) else 0
            val bi = if i < bs.length then leadingInt(bs(i)) else 0
            r = java.lang.Integer.compare(ai, bi)
            i += 1
        end while
        r
    end compareVersions

    private def leadingInt(s: String): Int =
        var i = 0
        while i < s.length && s.charAt(i).isDigit do i += 1
        if i == 0 then 0
        else
            try s.substring(0, i).toInt
            catch case _: NumberFormatException => 0
        end if
    end leadingInt
end AbiCheck
