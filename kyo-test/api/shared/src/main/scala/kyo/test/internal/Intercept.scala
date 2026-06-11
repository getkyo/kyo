package kyo.test.internal

import kyo.Frame
import kyo.Maybe
import kyo.test.AssertionFailed
import kyo.test.AssertScope
import scala.reflect.ClassTag
import scala.util.control.NonFatal

/** Runtime for `intercept[E]`, `interceptMessage[E]`, and their `Thrown` (Unit-returning) variants.
  *
  * Each runs `body`, expects it to throw an exception of type `E`, and either returns the caught exception (or `Unit`) or throws an
  * `AssertionFailed`. This is ordinary code rather than a macro: there is no subexpression diagram to render (the messages are built from the
  * `ClassTag` and the caught exception) and the source position arrives as the `Frame` parameter, so nothing here needs the call-site syntax
  * tree. `ClassTag[E]` drives the runtime type check, since the static `E` is erased.
  *
  * Each entry records one evaluation on the leaf's `AssertScope` (so the no-assertion check is satisfied) and, on failure, records the
  * `AssertionFailed` into the leaf sink before throwing, so a failure raised on a detached or leaked fiber still reaches the leaf.
  *
  * Only `NonFatal` exceptions and the expected `E` are caught. An `InterruptedException` that is not the expected `E` restores the interrupt
  * flag and propagates; other fatal errors (`VirtualMachineError`, `LinkageError`, and the like) propagate untouched.
  */
object Intercept:

    def intercept[E <: Throwable](body: => Any)(using ct: ClassTag[E], frame: Frame, scope: AssertScope): E =
        scope.recordEvaluated()
        capture[E](body) match
            case Maybe.Present(t) if ct.runtimeClass.isInstance(t) =>
                unrecordHandled(t)
                t.asInstanceOf[E] // Unsafe: ClassTag.isInstance guarantees the cast
            case Maybe.Present(t) => wrongType[E](t)
            case _                => notThrown[E]()
        end match
    end intercept

    def interceptMessage[E <: Throwable](msg: String)(body: => Any)(using ct: ClassTag[E], frame: Frame, scope: AssertScope): E =
        scope.recordEvaluated()
        capture[E](body) match
            case Maybe.Present(t) if ct.runtimeClass.isInstance(t) =>
                val e = t.asInstanceOf[E] // Unsafe: ClassTag.isInstance guarantees the cast
                if e.getMessage != msg then wrongMessage(msg, e.getMessage, t)
                unrecordHandled(t)
                e
            case Maybe.Present(t) => wrongType[E](t)
            case _                => notThrown[E]()
        end match
    end interceptMessage

    def interceptThrown[E <: Throwable](body: => Any)(using ct: ClassTag[E], frame: Frame, scope: AssertScope): Unit =
        scope.recordEvaluated()
        capture[E](body) match
            case Maybe.Present(t) if ct.runtimeClass.isInstance(t) => unrecordHandled(t)
            case Maybe.Present(t)                                  => wrongType[E](t)
            case _                                                 => notThrown[E]()
        end match
    end interceptThrown

    def interceptThrownMessage[E <: Throwable](msg: String)(body: => Any)(using ct: ClassTag[E], frame: Frame, scope: AssertScope): Unit =
        scope.recordEvaluated()
        capture[E](body) match
            case Maybe.Present(t) if ct.runtimeClass.isInstance(t) =>
                val e = t.asInstanceOf[E] // Unsafe: ClassTag.isInstance guarantees the cast
                if e.getMessage != msg then wrongMessage(msg, e.getMessage, t)
                unrecordHandled(t)
            case Maybe.Present(t) => wrongType[E](t)
            case _                => notThrown[E]()
        end match
    end interceptThrownMessage

    /** Runs `body`, returning the caught throwable when it is the expected `E` or any other non-fatal throwable, and empty when `body`
      * completed normally. Catches the expected `E` even when it is fatal (e.g. a Scala.js `UndefinedBehaviorError` caught by
      * `interceptThrown[Throwable]`, which `NonFatal` would let escape); re-raises a non-`E` interrupt after restoring the flag; lets a
      * non-`E` fatal error propagate.
      */
    private def capture[E <: Throwable](body: => Any)(using ct: ClassTag[E]): Maybe[Throwable] =
        try
            val _ = body
            Maybe.empty
        catch
            case t: Throwable if ct.runtimeClass.isInstance(t) => Maybe(t)
            case e: InterruptedException =>
                Thread.currentThread().interrupt()
                throw e
            case NonFatal(t) => Maybe(t)
        end try
    end capture

    // The expected exception was caught and HANDLED here. If it is itself an AssertionFailed it recorded itself into the
    // leaf sink before throwing, so remove that stale record now that intercept has consumed it.
    private def unrecordHandled(t: Throwable)(using scope: AssertScope): Unit =
        t match
            case af: AssertionFailed => scope.remove(af)
            case _                   => ()

    private def wrongType[E](t: Throwable)(using ct: ClassTag[E], frame: Frame, scope: AssertScope): Nothing =
        recordAndThrow(s"expected ${ct.runtimeClass.getSimpleName} but got ${t.getClass.getSimpleName}: ${t.getMessage}", Maybe(t))

    private def notThrown[E]()(using ct: ClassTag[E], frame: Frame, scope: AssertScope): Nothing =
        recordAndThrow(s"expected ${ct.runtimeClass.getSimpleName} to be thrown, but no exception was raised", Maybe.empty)

    private def wrongMessage(expected: String, actual: String, cause: Throwable)(using frame: Frame, scope: AssertScope): Nothing =
        recordAndThrow(s"expected message \"$expected\" but got \"$actual\"", Maybe(cause))

    private def recordAndThrow(diagram: String, cause: Maybe[Throwable])(using frame: Frame, scope: AssertScope): Nothing =
        val failure = AssertionFailed.make(diagram, frame, Maybe.empty, cause)
        scope.record(failure)
        throw failure
    end recordAndThrow

end Intercept
