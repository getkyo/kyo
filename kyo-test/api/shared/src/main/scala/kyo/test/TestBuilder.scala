package kyo.test

import kyo.Abort
import kyo.Async
import kyo.Duration
import kyo.Maybe
import kyo.Schedule
import kyo.Scope
import kyo.kernel.<
import scala.compiletime.erasedValue

/** Carrier for a test name plus its accumulated decorator metadata.
  *
  * Produced by calling decorator methods (.focus, .retry(n), .timeout(d), etc.) on a String inside a Test class. The Test class's `-`
  * extension on TestBuilder consumes this value and registers a leaf or group with the recorded metadata.
  *
  * Most users never construct TestBuilder directly; it is the type produced by the Test class's decorator chain.
  *
  * Platform restriction is NOT carried here: a platform filter (`.jvm`, `.js`, `.native`, `.onlyJvm`, `.onlyJs`, `.onlyNative`, `.notJvm`,
  * `.notJs`, `.notNative`) produces a [[kyo.test.PlatformTestBuilder]] whose phantom type parameter encodes the enabled-platform set, and the
  * terminal `in`/`-` on that carrier gates the body with a compile-time `inline if`. On a disabled platform the body is never emitted, so the
  * excluded leaf has no link or runtime cost (it is absent, not skipped).
  *
  * @param name
  *   the leaf or group name
  * @param tags
  *   tags applied via `.tagged(...)`
  * @param focus
  *   true when `.focus` is in the decorator chain
  * @param ignore
  *   `Present(reason)` when `.ignore` or `.ignore(reason)` is in the decorator chain (an empty reason for the no-arg form)
  * @param pendingUntilFixed
  *   `Present(reason)` when `.pendingUntilFixed(reason)` is in the chain. Unlike `pending`, the body RUNS: a still-failing body reports
  *   `Pending(reason)`, a now-passing body reports `Failed` so the marker gets removed. Retry/repeat do not apply (the body is expected to
  *   fail and runs exactly once).
  * @param timeout
  *   `Present(duration)` when `.timeout(d)` is in the chain
  * @param retrySchedule
  *   `Present(schedule)` when `.retry(n)`, `.retry(Schedule)`, or `.flaky` is in the chain; `Absent` means no retries
  * @param repeat
  *   repeat count from `.repeat(n)`; 1 means run once
  * @param onlyIf
  *   `Present(condition)` when `.only(cond)` is in the chain
  * @see
  *   [[kyo.test.internal.TestBase]] where decorator methods on String and TestBuilder are defined
  * @see
  *   [[kyo.test.PlatformTestBuilder]] the phantom-typed carrier produced by a platform filter
  * @see
  *   [[kyo.Schedule]] used by `.retry(s: Schedule)` and `.flaky` for retry backoff policies
  * @see
  *   [[kyo.test.RunConfig]] where a [[kyo.test.TestFilter]] can further restrict which tests run
  * @see
  *   [[kyo.test.LeafInfo]] which carries the tags and path fields populated from this builder
  */
final case class TestBuilder(
    name: String,
    tags: Set[String] = Set.empty,
    focus: Boolean = false,
    ignore: Maybe[String] = Maybe.empty,
    pendingUntilFixed: Maybe[String] = Maybe.empty,
    timeout: Maybe[Duration] = Maybe.empty,
    retrySchedule: Maybe[kyo.Schedule] = Maybe.empty,
    repeat: Int = 1,
    onlyIf: Maybe[() => Boolean] = Maybe.empty
)

object TestBuilder:
    /** Construct a TestBuilder with default metadata for the given name. */
    def apply(name: String): TestBuilder = new TestBuilder(name)
end TestBuilder

/** Phantom markers naming the enabled-platform SET for a platform-filtered leaf.
  *
  * Each marker is a phantom type only: it never has a value, it exists purely to drive [[gateOf]] at compile time. The platform-filter
  * methods on String/TestBuilder return a [[PlatformTestBuilder]] tagged with one of these, and the terminal `in`/`-` reduces `gateOf[P]` to
  * a single inline-constant Boolean for the platform currently compiling, so the leaf body is emitted only when that constant is true.
  *
  * @see
  *   [[gateOf]] the transparent inline reduction from a marker to `kyo.internal.Platform.is*`
  */
object PlatformSet:
    /** Enabled on every platform (the default for an unfiltered leaf). */
    sealed trait All

    /** Enabled only on the JVM. */
    sealed trait OnlyJvm

    /** Enabled only on Scala.js. */
    sealed trait OnlyJs

    /** Enabled only on Scala Native. */
    sealed trait OnlyNative

    /** Enabled on every platform except the JVM. */
    sealed trait NotJvm

    /** Enabled on every platform except Scala.js. */
    sealed trait NotJs

    /** Enabled on every platform except Scala Native. */
    sealed trait NotNative
end PlatformSet

/** Reduce a [[PlatformSet]] marker `P` to the compile-time-constant Boolean saying whether the current platform is in `P`'s enabled set.
  *
  * `transparent inline` plus the `inline erasedValue[P] match` makes the result a literal `true`/`false` at each platform compile, because
  * `kyo.internal.Platform.isJVM`/`isJS`/`isNative` are themselves inline constants per platform. That constant is what makes
  * `inline if gateOf[P]` a legal compile-time branch whose dead arm is never emitted.
  */
transparent inline def gateOf[P]: Boolean =
    inline erasedValue[P] match
        case _: PlatformSet.All        => true
        case _: PlatformSet.OnlyJvm    => kyo.internal.Platform.isJVM
        case _: PlatformSet.OnlyJs     => kyo.internal.Platform.isJS
        case _: PlatformSet.OnlyNative => kyo.internal.Platform.isNative
        case _: PlatformSet.NotJvm     => !kyo.internal.Platform.isJVM
        case _: PlatformSet.NotJs      => !kyo.internal.Platform.isJS
        case _: PlatformSet.NotNative  => !kyo.internal.Platform.isNative

/** Phantom-typed carrier produced by a platform filter (`.jvm`, `.js`, `.native`, `.onlyJvm`, `.onlyJs`, `.onlyNative`, `.notJvm`, `.notJs`,
  * `.notNative`).
  *
  * It wraps the runtime [[TestBuilder]] metadata and adds a phantom type parameter `P` naming the enabled-platform set (a [[PlatformSet]]
  * marker). The terminal `in`/`-` (defined as extensions on this type inside [[kyo.test.internal.TestBase]]) branch on `inline if
  * gateOf[P]`: on an enabled platform they register exactly as the unfiltered DSL does, and on a disabled platform they discard the body
  * UNAPPLIED via `discardScoped`/`discardGroup`, so its code is never emitted.
  *
  * Decorators chained after a platform filter (`.pending`, `.pendingUntilFixed`, `.focus`, `.retry`, `.timeout`, ...) preserve `P`, so a
  * chain like `"x".notNative.pending("...") in { ... }` stays compile-excluded on Native.
  *
  * @tparam P
  *   the [[PlatformSet]] marker naming the enabled-platform set
  * @param builder
  *   the underlying decorator metadata; the platform restriction lives in `P`, not in this value
  * @see
  *   [[gateOf]] which `in`/`-` evaluate at compile time
  */
final case class PlatformTestBuilder[P](builder: TestBuilder)

/** Discard a leaf body UNAPPLIED on a disabled platform.
  *
  * The parameter type matches the leaf body shape (`kyo.test.AssertScope ?=> Unit < (S & ...)`) so passing the body does NOT apply the
  * context function (which would demand a given `AssertScope`). Because `x` is an inline by-name parameter that is never used, its code is
  * never emitted into the platform's output and never reaches the linker. Mirrors the old `runNotJS`-style no-emit, but typed.
  */
inline def discardScoped[S](inline x: kyo.test.AssertScope ?=> Unit < (S & Async & Abort[Any] & Scope)): Unit = ()

/** Discard a group body UNAPPLIED on a disabled platform. The group body is a plain by-name (no context function), so this mirror keeps the
  * dead arm of a platform-filtered `-` from being emitted.
  */
inline def discardGroup[S](inline x: => Unit < (S & Async & Abort[Any] & Scope)): Unit = ()
