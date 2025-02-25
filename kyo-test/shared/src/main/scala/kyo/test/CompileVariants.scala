package kyo.test

import kyo.*
import scala.annotation.tailrec
import scala.compiletime.testing.Error
import scala.compiletime.testing.typeCheckErrors

trait CompileVariants:

    /** Returns either `Right` if the specified string type checks as valid Scala code or `Left` with an error message otherwise. Dies with
      * a runtime exception if specified string cannot be parsed or is not a known value at compile time.
      */
    inline def typeCheck(inline code: String): Either[String, Unit] < Abort[Throwable] =
        try failWith(typeCheckErrors(code))
        catch
            case cause: Throwable =>
                Abort.panic(new RuntimeException("Compilation failed", cause))

    private def failWith(errors: List[Error])(using trace: Frame): Either[String, Unit] < Abort[Throwable] =
        if errors.isEmpty then
            Right(())
        else
            Left(errors.iterator.map(_.message).mkString("\n"))

    inline def assertTrue(inline exprs: => Boolean*): TestResult =
        ${ SmartAssertMacros.smartAssert('exprs) }

    inline def assert[A](inline value: => A)(
        inline assertion: Assertion[A]
    )(implicit trace: Trace, position: FramePosition): TestResult =
        ${ Macros.assert_impl('value)('assertion, 'trace, 'position) }

    inline def assertKyo[R, E, A](
        inline effect: A < Env[R] & Abort[E]
    )(inline assertion: Assertion[A])(implicit trace: Trace, position: FramePosition): TestResult < Env[R] & Abort[E] =
        ${ Macros.assertKyo_impl('effect)('assertion, 'trace, 'position) }

    private[kyo] inline def showExpression[A](inline value: => A): String = ${ Macros.showExpression_impl('value) }
end CompileVariants

/** Proxy methods to call package private methods from the macro
  */
object CompileVariants:

    def assertProxy[A](value: => A, expression: String, assertionCode: String)(
        assertion: Assertion[A]
    )(implicit trace: Trace, position: Frame.Position): TestResult =
        kyo.test.assertImpl(value, Some(expression), Some(assertionCode))(assertion)

    def assertKyoProxy[R, E, A](effect: A < Env[R] & Abort[E], expression: String, assertionCode: String)(
        assertion: Assertion[A]
    )(implicit trace: Trace, position: Frame.Position): TestResult < Env[R] & Abort[E] =
        kyo.test.assertKyoImpl(effect, Some(expression), Some(assertionCode))(assertion)
end CompileVariants
