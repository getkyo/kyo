package kyo.internal

import kyo.Frame
import kyo.Result
import scala.compiletime
import scala.compiletime.testing.typeCheckErrors
import scala.util.Try

private[kyo] trait BaseKyoDataTest:

    type Assertion

    def assertionSuccess: Assertion
    def assertionFailure(msg: String): Assertion

    given [A]: CanEqual[Try[A], Try[A]]                = CanEqual.derived
    given [A, B]: CanEqual[Either[A, B], Either[A, B]] = CanEqual.derived
    given CanEqual[Throwable, Throwable]               = CanEqual.derived

    transparent inline def typeCheck(inline code: String): Assertion =
        typeCheckWith(code):
            case Result.Error(e)   => assertionFailure(s"$code is not typecheck: $e")
            case Result.Success(_) => assertionSuccess
    end typeCheck

    transparent inline def typeCheckWith(inline code: String)(inline f: Result[String, Unit] => Assertion): Assertion =
        val result: Result[String, Unit] =
            try
                val errors = typeCheckErrors(code)
                if errors.isEmpty then Result.unit else Result.fail(errors.iterator.map(_.message).mkString("\n"))
            catch
                case cause: Throwable =>
                    Result.panic(new RuntimeException("Compilation failed", cause))

        f(result)
    end typeCheckWith

    transparent inline def typeCheckFailure(inline code: String)(inline expected: String)(using frame: Frame): Assertion =
        typeCheckWith(code):
            case Result.Failure(errors) =>
                if errors.contains(expected) && expected.nonEmpty then assertionSuccess
                else assertionFailure(frame.render(Map("expected" -> expected, "actual" -> errors)))
            case Result.Panic(exception) => assertionFailure(exception.getMessage)
            case _                       => assertionFailure("Code type-checked successfully, expected a failure")

end BaseKyoDataTest
