package kyo.test

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace

trait TimeoutVariants {

  /**
   * A test aspect that prints a warning to the console when a test takes longer
   * than the specified duration.
   */
  def timeoutWarning(
    duration: Duration
  ): TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](
        spec: Spec[R, E]
      )(implicit trace: Trace): Spec[R, E] = {
        def loop(labels: List[String], spec: Spec[R, E]): Spec[R, E] =
          spec.caseValue match {
            case Spec.ExecCase(exec, spec)     => Spec.exec(exec, loop(labels, spec))
            case Spec.LabeledCase(label, spec) => Spec.labeled(label, loop(label :: labels, spec))
            case Spec.ScopedCase(scoped)       => Spec.scoped[R](scoped.map(loop(labels, _)))
            case Spec.MultipleCase(specs) =>
              Spec.multiple(specs.map(loop(labels, _)))
            case Spec.TestCase(test, annotations) =>
              Spec.test(warn(labels, test, duration), annotations)
          }

        loop(Nil, spec)
      }
    }

  private def warn[R, E](
    labels: List[String],
    test: ZTest[R, E],
    duration: Duration
  )(implicit trace: Trace): ZTest[R, E] =
    test.raceWith(Live.withLive(showWarning(labels, duration))(_.delay(duration)))(
      (result, fiber) => fiber.interrupt *> result,
      (_, fiber) => fiber.join
    )

  private def showWarning(
    labels: List[String],
    duration: Duration
  )(implicit trace: Trace): UIO[Unit] =
    ZIO.logWarning(renderWarning(labels, duration))

  private def renderWarning(labels: List[String], duration: Duration): String =
    "Test " + labels.reverse.mkString(" - ") + " has taken more than " + duration.render +
      " to execute. If this is not expected, consider using TestAspect.timeout to timeout runaway tests for faster diagnostics."
}
