package kyo.test

import zio.internal.stacktracer.{SourceLocation, Tracer}
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.{UIO, ZIO, Trace}

import scala.annotation.tailrec
import scala.compiletime.testing.{typeCheckErrors, Error}

trait CompileVariants {

  /**
   * Returns either `Right` if the specified string type checks as valid Scala
   * code or `Left` with an error message otherwise. Dies with a runtime
   * exception if specified string cannot be parsed or is not a known value at
   * compile time.
   */
  inline def typeCheck(inline code: String): UIO[Either[String, Unit]] =
    try failWith(typeCheckErrors(code))
    catch {
      case cause: Throwable =>
        ZIO.die(new RuntimeException("Compilation failed", cause))
    }

  private def failWith(errors: List[Error])(using Trace) =
    if errors.isEmpty then ZIO.right(())
    else ZIO.left(errors.iterator.map(_.message).mkString("\n"))

  inline def assertTrue(inline exprs: => Boolean*): TestResult =
    ${ SmartAssertMacros.smartAssert('exprs) }

  inline def assert[A](inline value: => A)(
    inline assertion: Assertion[A]
  )(implicit trace: Trace, sourceLocation: SourceLocation): TestResult =
    ${ Macros.assert_impl('value)('assertion, 'trace, 'sourceLocation) }

  inline def assertZIO[R, E, A](effect: ZIO[R, E, A])(assertion: Assertion[A]): ZIO[R, E, TestResult] =
    ${ Macros.assertZIO_impl('effect)('assertion) }

  private[zio] inline def showExpression[A](inline value: => A): String = ${ Macros.showExpression_impl('value) }
}

/**
 * Proxy methods to call package private methods from the macro
 */
object CompileVariants {

  def assertProxy[A](value: => A, expression: String, assertionCode: String)(
    assertion: Assertion[A]
  )(implicit trace: Trace, sourceLocation: SourceLocation): TestResult =
    zio.test.assertImpl(value, Some(expression), Some(assertionCode))(assertion)

  def assertZIOProxy[R, E, A](effect: ZIO[R, E, A], expression: String, assertionCode: String)(
    assertion: Assertion[A]
  )(implicit trace: Trace, sourceLocation: SourceLocation): ZIO[R, E, TestResult] =
    zio.test.assertZIOImpl(effect, Some(expression), Some(assertionCode))(assertion)
}
