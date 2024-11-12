package kyo

import org.scalatest.Assertion
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.compiletime.testing.Error
import scala.compiletime.testing.typeCheckErrors
import scala.util.Try

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions:
    given tryCanEqual[A]: CanEqual[Try[A], Try[A]]                   = CanEqual.derived
    given eitherCanEqual[A, B]: CanEqual[Either[A, B], Either[A, B]] = CanEqual.derived
    given throwableCanEqual: CanEqual[Throwable, Throwable]          = CanEqual.derived

    inline def typeCheck(inline code: String): Result[String, Unit] =
        try
            val errors = typeCheckErrors(code)
            if errors.isEmpty then Result.unit else Result.fail(errors.iterator.map(_.message).mkString("\n"))
        catch
            case cause: Throwable =>
                Result.panic(new RuntimeException("Compilation failed", cause))
    end typeCheck

    inline def typeCheckFailure(inline code: String)(inline predicate: String => Boolean): Assertion =
        typeCheck(code) match
            case Result.Fail(errors)     => if predicate(errors) then succeed else fail(s"Predicate not satisfied by $errors")
            case Result.Panic(exception) => fail(exception.getMessage)
            case Result.Success(_)       => fail("Code type-checked successfully, expected a failure")
end Test
