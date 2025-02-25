package kyo.test.laws

import kyo.*
import kyo.test.Gen
import kyo.test.TestResult
import kyo.test.check

abstract class ZLaws2[-CapsBoth[_, _], -CapsLeft[_], -CapsRight[_], -R]:
    self =>

    def run[R1 <: R, A: CapsLeft, B: CapsRight](left: Gen[R1, A], right: Gen[R1, B])(implicit
        CapsBoth: CapsBoth[A, B],
        trace: Trace
    ): TestResult < Env[R1] & Abort[Nothing]

    def +[CapsBoth1[x, y] <: CapsBoth[x, y], CapsLeft1[x] <: CapsLeft[x], CapsRight1[x] <: CapsRight[x], R1 <: R](
        that: ZLaws2[CapsBoth1, CapsLeft1, CapsRight1, R1]
    ): ZLaws2[CapsBoth1, CapsLeft1, CapsRight1, R1] =
        ZLaws2.Both(self, that)
end ZLaws2

object ZLaws2:

    final private case class Both[-CapsBoth[_, _], -CapsLeft[_], -CapsRight[_], -R](
        left: ZLaws2[CapsBoth, CapsLeft, CapsRight, R],
        right: ZLaws2[CapsBoth, CapsLeft, CapsRight, R]
    ) extends ZLaws2[CapsBoth, CapsLeft, CapsRight, R]:
        final def run[R1 <: R, A: CapsLeft, B: CapsRight](a: Gen[R1, A], b: Gen[R1, B])(implicit
            CapsBoth: CapsBoth[A, B],
            trace: Trace
        ): TestResult < Env[R1] & Abort[Nothing] =
            left.run(a, b).zipWith(right.run(a, b))(_ && _)
    end Both

    abstract class Law1Left[-CapsBoth[_, _], -CapsLeft[_], -CapsRight[_]](label: String)
        extends ZLaws2[CapsBoth, CapsLeft, CapsRight, Any]:
        self =>
        def apply[A: CapsLeft, B: CapsRight](a1: A)(implicit CapsBoth: CapsBoth[A, B]): TestResult
        final def run[R, A: CapsLeft, B: CapsRight](a: Gen[R, A], b: Gen[R, B])(implicit
            CapsBoth: CapsBoth[A, B],
            trace: Trace
        ): TestResult < Env[R] =
            check(a, b)((a, _) => apply(a).label(label))
    end Law1Left

    abstract class Law1Right[-CapsBoth[_, _], -CapsLeft[_], -CapsRight[_]](label: String)
        extends ZLaws2[CapsBoth, CapsLeft, CapsRight, Any]:
        self =>
        def apply[A: CapsLeft, B: CapsRight](b1: B)(implicit CapsBoth: CapsBoth[A, B]): TestResult
        final def run[R, A: CapsLeft, B: CapsRight](a: Gen[R, A], b: Gen[R, B])(implicit
            CapsBoth: CapsBoth[A, B],
            trace: Trace
        ): TestResult < Env[R] =
            check(a, b)((_, b) => apply(b).label(label))
    end Law1Right
end ZLaws2
