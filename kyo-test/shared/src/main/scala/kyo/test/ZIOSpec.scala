package kyo.test

import zio._
import zio.internal.stacktracer.SourceLocation
import zio.stacktracer.TracingImplicits.disableAutoTrace

abstract class ZIOSpec[R: EnvironmentTag] extends ZIOSpecAbstract with ZIOSpecVersionSpecific[R] { self =>
  type Environment = R

  final val environmentTag: EnvironmentTag[R] = EnvironmentTag[R]

  /**
   * Builds a spec with a single test.
   */
  def test[In](label: String)(
    assertion: => In
  )(implicit
    testConstructor: TestConstructor[Nothing, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): testConstructor.Out =
    zio.test.test(label)(assertion)

  def suite[In](label: String)(specs: In*)(implicit
    suiteConstructor: SuiteConstructor[In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): Spec[suiteConstructor.OutEnvironment, suiteConstructor.OutError] =
    zio.test.suite(label)(specs: _*)

}
