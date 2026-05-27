package kyo

import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext

// ── Test discoverability: the nested `freespec` `-` convention ────────────────
//
// Several test files use the `"<parent string>" - { ... "<leaf>" in { ... } }`
// form to group related scenarios. ScalaTest's `-z` narrowing matches anywhere
// in the FULL test name (parent strings concatenated with the leaf), so the
// leaf's words alone may not be enough.
//
// Example:
//   "Accessibility.AxValue Schema decodes each CDP discriminator" - {
//       "string lifts the JSON string to a typed Wire.Str" in { ... }
//   }
//
// To run that one leaf via `testOnly`:
//   sbt 'kyo-browser/testOnly *AccessibilityTest -- -z "string lifts"'
//
// The `-z` substring will match if your filter appears anywhere in the joined
// path, including the parent. Files that currently use this style:
//   - internal/MutationSettlementTest.scala
//   - internal/cdp/AccessibilityTest.scala
//   - internal/CdpTypesTest.scala
//   - internal/ActionabilityTest.scala
//   - internal/CdpParamsRoundTripTest.scala
//   - internal/NavigationWatcherTest.scala
//
// New tests should prefer the flat form unless the grouping carries genuine
// semantic value (cf. CLAUDE.md "test placement: topic-based files").
// ──────────────────────────────────────────────────────────────────────────────

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext

    /** JSON decode helper used across CDP tests. */
    def decode[A: Schema](json: String)(using Frame): A =
        Json.decode[A](json) match
            case Result.Success(v) => v
            case other             => fail(s"decode failed: $other")

    /** Decodes a CDP wire response (the whole `{id, result, error}` envelope) into the typed result `A`. The
      * dispatcher carrier is the entire wire frame, so any test that takes a `client.send(...)` reply and wants
      * the typed result calls this helper instead of `decode[A]` directly.
      */
    def decodeCdpResult[A: Schema](wire: String)(using Frame): A =
        Json.decode[kyo.internal.CdpReply[A]](wire) match
            case Result.Success(reply) =>
                reply.result match
                    case Present(v) => v
                    case Absent =>
                        reply.error match
                            case Present(err) => fail(s"expected CdpReply.result but got error: $err")
                            case Absent       => fail(s"CdpReply has neither result nor error: $reply")
            case other => fail(s"CdpReply decode failed: $other")

    /** Measures the elapsed monotonic duration of a computation. */
    def timed[A, S](v: A < (Async & S))(using Frame): (Duration, A) < (Async & S) =
        Clock.nowMonotonic.map { t0 =>
            v.map { a => Clock.nowMonotonic.map { t1 => (t1 - t0, a) } }
        }

    /** Outer `Result.{Success,Failure,Panic}` fold helper that fails the test on `Failure`/`Panic`.
      *
      * The `PANIC: ` prefix on the panic path makes it visually obvious in test output that an unexpected programming bug surfaced (as
      * opposed to a typed-failure path that the test ought to handle). Without the prefix, `Failure` and `Panic` would collapse into the
      * same shape of fail message and a panic could be mistaken for an expected typed-error path that was caught too loosely.
      */
    extension [E <: Throwable, A, S](v: Result[E, A] < (Async & S))
        def orFail(label: String)(using Frame): A < (Async & S) =
            v.map {
                case Result.Success(a)   => a
                case Result.Failure(err) => fail(s"$label failed: ${err.getMessage}")
                case Result.Panic(ex)    => fail(s"PANIC: $label panic: ${ex.getMessage}")
            }
    end extension
end Test
