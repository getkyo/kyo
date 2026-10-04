package kyo.ffi.internal

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.ffi.FfiLoadError
import scala.annotation.tailrec

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

    /** Compare two version strings by SemVer 2.0.0 precedence (section 11). Returns a negative number when `a` is older
      * than `b`, zero when they rank the same, positive when newer.
      *
      * Build metadata, from the first `+`, is ignored, so an sbt-dynver build after a tag (`1.0.0-RC8+12-abcdef12`)
      * ranks as the tag. The release components before the first `-` compare numerically, the shorter padded with
      * zeros, and each reads its leading digits, so a component with none counts as zero. A pre-release ranks below its
      * release. Two pre-releases compare identifier by identifier: numeric identifiers as numbers, below any
      * alphanumeric one, and alphanumeric identifiers in ASCII order, so `RC10` ranks below `RC9`; when every shared
      * identifier is equal, the one with fewer ranks lower. Numbers compare as digit strings, never overflowing.
      */
    def compareVersions(a: String, b: String): Int =
        val (aRelease, aPre) = releaseAndPreRelease(a)
        val (bRelease, bPre) = releaseAndPreRelease(b)
        val release          = compareRelease(aRelease.split('.'), bRelease.split('.'))
        if release != 0 then release
        else
            aPre match
                case Absent     => if bPre.isEmpty then 0 else 1
                case Present(x) =>
                    bPre match
                        case Absent     => -1
                        case Present(y) => comparePreRelease(x.split('.'), y.split('.'))
        end if
    end compareVersions

    private def releaseAndPreRelease(version: String): (String, Maybe[String]) =
        val withoutBuild = version.indexOf('+') match
            case -1 => version
            case i  => version.substring(0, i)
        withoutBuild.indexOf('-') match
            case -1 => (withoutBuild, Absent)
            case i  => (withoutBuild.substring(0, i), Present(withoutBuild.substring(i + 1)))
    end releaseAndPreRelease

    private def compareRelease(as: Array[String], bs: Array[String]): Int =
        @tailrec def loop(i: Int): Int =
            if i >= math.max(as.length, bs.length) then 0
            else
                val r = compareNumber(
                    if i < as.length then leadingDigits(as(i)) else "",
                    if i < bs.length then leadingDigits(bs(i)) else ""
                )
                if r != 0 then r else loop(i + 1)
        loop(0)
    end compareRelease

    private def comparePreRelease(as: Array[String], bs: Array[String]): Int =
        @tailrec def loop(i: Int): Int =
            if i >= as.length || i >= bs.length then Integer.compare(as.length, bs.length)
            else
                val r = (isNumeric(as(i)), isNumeric(bs(i))) match
                    case (true, true)   => compareNumber(as(i), bs(i))
                    case (true, false)  => -1
                    case (false, true)  => 1
                    case (false, false) => Integer.signum(as(i).compareTo(bs(i)))
                if r != 0 then r else loop(i + 1)
        loop(0)
    end comparePreRelease

    private def isNumeric(identifier: String): Boolean =
        identifier.nonEmpty && identifier.forall(c => c >= '0' && c <= '9')

    private def leadingDigits(component: String): String =
        component.takeWhile(c => c >= '0' && c <= '9')

    /** Two runs of ASCII digits compared as the numbers they spell; an empty run is zero. */
    private def compareNumber(a: String, b: String): Int =
        val x = a.dropWhile(_ == '0')
        val y = b.dropWhile(_ == '0')
        if x.length != y.length then Integer.compare(x.length, y.length)
        else Integer.signum(x.compareTo(y))
    end compareNumber
end AbiCheck
