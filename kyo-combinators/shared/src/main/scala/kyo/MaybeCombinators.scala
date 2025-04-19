package kyo

import kyo.debug.Debug
import kyo.kernel.ArrowEffect
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.util.NotGiven

extension [A, S, E](effect: A < (Abort[Absent] & S))

    /** Handles the Abort[Absent] effect and returns its result as a `Maybe[A]`.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[Absent] effect handled
      */
    def maybe(using Frame): Maybe[A] < S =
        Abort.run[Absent](effect).map {
            case Result.Failure(_) => Absent
            case Result.Panic(e)   => throw e
            case Result.Success(a) => Present(a)
        }

    /** Translates the Abort[Absent] effect to a Choice effect.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[Absent] effect translated to Choice
      */
    def absentToChoice(using Frame): A < (S & Choice) =
        effect.forAbort[Absent].toChoiceDrop

    /** Handles Abort[Absent], aborting in Absent cases with NoSuchElementException exceptions
      *
      * @return
      *   A computation that aborts with Maybe.Empty when its Choice effect is reduced to an empty sequence
      */
    def absentToThrowable(using Frame): A < (S & Abort[NoSuchElementException]) =
        for
            res <- effect.forAbort[Absent].result
        yield res match
            case Result.Failure(_) => Abort.catching(Absent.get)
            case Result.Success(a) => Abort.get(Result.succeed(a))
            case res: Result.Panic => Abort.get(res)

    /** Handles the Abort[Absent] effect translating it to an Abort[E] effect.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[Absent] effect translated to Abort[E]
      */
    def absentToFailure(failure: => E)(using Frame): A < (S & Abort[E]) =
        for
            res <- effect.forAbort[Absent].result
        yield res match
            case Result.Failure(_) => Abort.get(Result.Failure(failure))
            case Result.Success(a) => Abort.get(Result.succeed(a))
            case res: Result.Panic => Abort.get(res)
end extension
