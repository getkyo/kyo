package kyo.test.laws

import zio.stacktracer.TracingImplicits.disableAutoTrace

trait ZLawful2[-CapsBoth[_, _], -CapsLeft[_], -CapsRight[_], -R] { self =>
  def laws: ZLaws2[CapsBoth, CapsLeft, CapsRight, R]
  def +[CapsBoth1[x, y] <: CapsBoth[x, y], CapsLeft1[x] <: CapsLeft[x], CapsRight1[x] <: CapsRight[x], R1 <: R](
    that: ZLawful2[CapsBoth1, CapsLeft1, CapsRight1, R1]
  ): ZLawful2[CapsBoth1, CapsLeft1, CapsRight1, R1] =
    new ZLawful2[CapsBoth1, CapsLeft1, CapsRight1, R1] {
      val laws = self.laws + that.laws
    }
}
