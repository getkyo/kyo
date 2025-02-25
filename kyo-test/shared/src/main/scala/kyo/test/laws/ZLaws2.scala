package kyo.test.laws

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.{Gen, TestResult, check}
import zio.{URIO, ZIO, Trace}

abstract class ZLaws2[-CapsBoth[_, _], -CapsLeft[_], -CapsRight[_], -R] { self =>

  def run[R1 <: R, A: CapsLeft, B: CapsRight](left: Gen[R1, A], right: Gen[R1, B])(implicit
    CapsBoth: CapsBoth[A, B],
    trace: Trace
  ): ZIO[R1, Nothing, TestResult]

  def +[CapsBoth1[x, y] <: CapsBoth[x, y], CapsLeft1[x] <: CapsLeft[x], CapsRight1[x] <: CapsRight[x], R1 <: R](
    that: ZLaws2[CapsBoth1, CapsLeft1, CapsRight1, R1]
  ): ZLaws2[CapsBoth1, CapsLeft1, CapsRight1, R1] =
    ZLaws2.Both(self, that)
}

object ZLaws2 {

  private final case class Both[-CapsBoth[_, _], -CapsLeft[_], -CapsRight[_], -R](
    left: ZLaws2[CapsBoth, CapsLeft, CapsRight, R],
    right: ZLaws2[CapsBoth, CapsLeft, CapsRight, R]
  ) extends ZLaws2[CapsBoth, CapsLeft, CapsRight, R] {
    final def run[R1 <: R, A: CapsLeft, B: CapsRight](a: Gen[R1, A], b: Gen[R1, B])(implicit
      CapsBoth: CapsBoth[A, B],
      trace: Trace
    ): ZIO[R1, Nothing, TestResult] =
      left.run(a, b).zipWith(right.run(a, b))(_ && _)
  }

  abstract class Law1Left[-CapsBoth[_, _], -CapsLeft[_], -CapsRight[_]](label: String)
      extends ZLaws2[CapsBoth, CapsLeft, CapsRight, Any] { self =>
    def apply[A: CapsLeft, B: CapsRight](a1: A)(implicit CapsBoth: CapsBoth[A, B]): TestResult
    final def run[R, A: CapsLeft, B: CapsRight](a: Gen[R, A], b: Gen[R, B])(implicit
      CapsBoth: CapsBoth[A, B],
      trace: Trace
    ): URIO[R, TestResult] =
      check(a, b)((a, _) => apply(a).label(label))
  }

  abstract class Law1Right[-CapsBoth[_, _], -CapsLeft[_], -CapsRight[_]](label: String)
      extends ZLaws2[CapsBoth, CapsLeft, CapsRight, Any] { self =>
    def apply[A: CapsLeft, B: CapsRight](b1: B)(implicit CapsBoth: CapsBoth[A, B]): TestResult
    final def run[R, A: CapsLeft, B: CapsRight](a: Gen[R, A], b: Gen[R, B])(implicit
      CapsBoth: CapsBoth[A, B],
      trace: Trace
    ): URIO[R, TestResult] =
      check(a, b)((_, b) => apply(b).label(label))
  }
}
