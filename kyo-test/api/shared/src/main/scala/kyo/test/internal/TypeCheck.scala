package kyo.test.internal

import kyo.Frame
import kyo.Maybe
import kyo.Result
import kyo.test.AssertionFailed
import kyo.test.AssertScope
import scala.annotation.publicInBinary
import scala.compiletime.testing.typeCheckErrors
import scala.util.Try

/** Compile-time `typeCheck` / `typeCheckFailure` helpers for kyo-test suites.
  *
  * Self-contained compile-time assertion helpers: a passing check returns `()`, a failing check throws
  * [[kyo.test.AssertionFailed]] (the throw-based failure channel the runner catches). The parametric `Assertion`
  * result type from the original design is monomorphized to `Unit` here.
  *
  * Each helper requires `using AssertScope`, keeping it inside the assert family (so a `typeCheck` lives only inside a leaf body). A failing
  * check records the `AssertionFailed` into the leaf scope before throwing it, matching the plain `assert` path.
  */
private[kyo] trait TypeCheck:

    given [A]: CanEqual[Try[A], Try[A]]                = CanEqual.derived
    given [A, B]: CanEqual[Either[A, B], Either[A, B]] = CanEqual.derived
    given CanEqual[Throwable, Throwable]               = CanEqual.derived

    private def typeCheckFail(msg: String)(using frame: Frame, as: AssertScope): Nothing =
        as.recordEvaluated()
        val failure = new AssertionFailed(msg, frame, Maybe.empty, Maybe.empty)
        as.record(failure)
        throw failure
    end typeCheckFail

    /** Asserts that `code` type-checks; throws [[kyo.test.AssertionFailed]] with the compiler errors when it does not. */
    transparent inline def typeCheck(inline code: String)(using frame: Frame, as: AssertScope): Unit =
        typeCheckWith(code):
            case Result.Error(e) => typeCheckFail(s"$code did not typecheck: $e")
            case Result.Success(_) =>
                as.recordEvaluated()
                ()
    end typeCheck

    @publicInBinary
    private[TypeCheck] transparent inline def typeCheckWith(inline code: String)(inline f: Result[String, Unit] => Unit): Unit =
        val result: Result[String, Unit] =
            try
                val errors = typeCheckErrors(code)
                if errors.isEmpty then Result.unit
                else Result.fail(errors.iterator.map(_.message.replace("\r\n", "\n")).mkString("\n"))
            catch
                case cause: Throwable =>
                    Result.panic(new RuntimeException("Compilation failed", cause))

        f(result)
    end typeCheckWith

    /** Asserts that `code` fails to type-check with an error containing `expected`; throws [[kyo.test.AssertionFailed]] otherwise. */
    transparent inline def typeCheckFailure(inline code: String)(inline expected: String)(using frame: Frame, as: AssertScope): Unit =
        typeCheckWith(code):
            case Result.Failure(errors) =>
                if errors.contains(expected) && expected.nonEmpty then
                    as.recordEvaluated()
                    ()
                else typeCheckFail(frame.render(Map("expected" -> expected, "actual" -> errors)))(using frame, as)
            case Result.Panic(exception) => typeCheckFail(exception.getMessage)(using frame, as)
            case _                       => typeCheckFail("Code type-checked successfully, expected a failure")(using frame, as)

    /** Asserts that `code` fails to type-check (with any error); throws [[kyo.test.AssertionFailed]] when it compiles.
      *
      * The message-agnostic counterpart of [[typeCheckFailure(code)(expected)]], for cases that only care that the
      * code is rejected (the faithful equivalent of ScalaTest's `assertDoesNotCompile`).
      */
    transparent inline def typeCheckFailure(inline code: String)(using frame: Frame, as: AssertScope): Unit =
        typeCheckWith(code):
            case Result.Failure(_) =>
                as.recordEvaluated()
                ()
            case Result.Panic(exception) => typeCheckFail(exception.getMessage)(using frame, as)
            case _                       => typeCheckFail("Code type-checked successfully, expected a failure")(using frame, as)

end TypeCheck
