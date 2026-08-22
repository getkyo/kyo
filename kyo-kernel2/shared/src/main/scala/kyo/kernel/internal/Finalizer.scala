package kyo.kernel.internal

import java.util.concurrent.atomic.AtomicBoolean
import kyo.Arrow
// unqualified so an inline expansion does not select it from Arrow.type at a site outside package kyo,
// where it is not accessible. See the note in Pending.scala
import kyo.Arrow.Region
import kyo.Arrow.Transform
import kyo.Frame
import kyo.Result
import kyo.discard
import kyo.kernel.*
import scala.util.control.NoStackTrace

/** A release that has not run yet, together with the resource that owes it.
  *
  * One object fills three roles. It is the entry a stack holds, so an eval that throws or abandons a
  * continuation can still release. It is the arrow spliced after the extent it belongs to, so an eval that
  * completes releases where that extent ends rather than at the boundary. And it is the flag that makes those
  * two paths exclusive, which they have to be because both can be reached for the same resource.
  *
  * Atomic rather than a plain `var`: a captured continuation can be resumed on one thread while the eval that
  * created it drains on another, so the two paths genuinely race.
  *
  * The flag is set before the release runs, so a release that throws still counts as run and the drain does
  * not retry it.
  */
final private[kyo] class Finalizer[A, B](release: (A, Result[Nothing, B]) => Any < Any, resource: A)
    extends AtomicBoolean with Region[B, B, Any]:

    def frame = Frame.internal

    /** Runs the release once, telling it how the extent it belongs to ended.
      *
      * Three outcomes reach it and each path is holding what it needs: the value where the extent completed,
      * the exception where an unwind passed it, and `Finalizer.Abandoned` where an eval ended holding a
      * continuation nobody resumed. That last one is a real third case rather than a failure, which is what a
      * `Maybe[Error]` could not say.
      */
    def run(outcome: Result[Nothing, B]): Unit =
        if compareAndSet(false, true) then discard(Eval(release(resource, outcome)))

    // the value flowing out is the extent's result, which is what the release is told it completed with
    override def apply(v: B): B < Any =
        run(Result.succeed(v))
        v

    // defers on a pending input: while the value has not settled the extent has not finished, and the release
    // is owed only once it has
    def apply[C, S2](v: B < S2, cont: Arrow[B, C, S2]): C < S2 =
        v match
            case kyo: Kyo[B, S2] @unchecked => Effect.defer(kyo, this, cont)
            case _                          => cont(apply(Nested.unnest[B](v)), Arrow.id)
end Finalizer

private[kyo] object Finalizer:

    /** What a release is told when the extent it belongs to never ended.
      *
      * An eval that finishes holding a continuation nobody resumed, or a park whose holder gives it up, owes
      * the release without having an outcome to report. That is neither a completion nor a failure, so it has
      * a value of its own rather than borrowing an error the computation never raised.
      */
    case object Abandoned extends Exception("the extent this release belonged to was abandoned") with NoStackTrace
end Finalizer
