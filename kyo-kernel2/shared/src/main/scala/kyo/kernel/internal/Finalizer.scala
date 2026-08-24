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
final private[kyo] class Finalizer[A, B](
    release: (A, Result[Nothing, B]) => Any < Any,
    resource: A,
    val frame: Frame
) extends AtomicBoolean with Region[B, B, Any]:

    /** Runs the release once, telling it how the extent it belongs to ended.
      *
      * Three outcomes reach it and each path is holding what it needs: the value where the extent completed,
      * the exception where an unwind passed it, and `Finalizer.Abandoned` where an eval ended holding a
      * continuation nobody resumed. That last one is a real third case rather than a failure, which is what a
      * `Maybe[Error]` could not say.
      */
    def run(outcome: Result[Nothing, B]): Unit =
        if compareAndSet(false, true) then
            // TEMPORARY DIAGNOSTIC LOGGING
            java.lang.System.err.println(
                s"[kfin] release id=${java.lang.System.identityHashCode(this)} at=${frame.position.show} outcome=$outcome thread=${Thread.currentThread().getName}"
            )
            discard(Eval(release(resource, outcome)))

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

    /** Raised where an extent whose release already ran is entered again.
      *
      * A continuation that carries a scope can be applied after that scope ended: a clause that applies one
      * more than once, or a holder that drives a remainder its eval already released. Either way the value
      * flowing in would reach code holding a resource that is gone, so the entry into the scope is refused
      * rather than the read being allowed to happen.
      *
      * A class rather than an object, so each throw is its own instance. This travels an unwind as the
      * failure being carried, and the walk suppresses onto whatever it is carrying, which a shared instance
      * would accumulate for the life of the process. It carries no `NoStackTrace` either: the frames are
      * what say where the scope was entered again, and the splice skips anything that declines them.
      *
      * @param frame
      *   where the scope was opened, which is the half a stack trace cannot show
      */
    final class Spent(frame: Frame)
        extends Exception(s"the resource opened at ${frame.position.show} was released, and its scope is being entered again")
end Finalizer
