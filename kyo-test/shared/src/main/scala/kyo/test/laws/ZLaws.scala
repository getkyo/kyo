package kyo.test.laws

import kyo.*
import kyo.test.Gen
import kyo.test.TestResult
import kyo.test.Trace
import kyo.test.check

/** `ZLaws[Caps, R]` represents a set of laws that values with capabilities `Caps` are expected to satisfy. Laws can be run by providing a
  * generator of values of a type `A` with the required capabilities to return a test result. Laws can be combined using `+` to produce a
  * set of laws that require both sets of laws to be satisfied.
  */
abstract class ZLaws[-Caps[_], -R]:
    self =>

    /** Test that values of type `A` satisfy the laws using the specified generator.
      */
    def run[R1 <: R, A: Caps](gen: Gen[R1, A])(implicit
        trace: Trace
    ): TestResult < Env[R1] & Abort[Nothing]

    /** Combine these laws with the specified laws to produce a set of laws that require both sets of laws to be satisfied.
      */
    def +[Caps1[x] <: Caps[x], R1 <: R](that: ZLaws[Caps1, R1]): ZLaws[Caps1, R1] =
        ZLaws.Both(self, that)
end ZLaws

object ZLaws:

    final private case class Both[-Caps[_], -R](left: ZLaws[Caps, R], right: ZLaws[Caps, R]) extends ZLaws[Caps, R]:
        final def run[R1 <: R, A: Caps](gen: Gen[R1, A])(implicit
            trace: Trace
        ): TestResult < Env[R1] & Abort[Nothing] =
            left.run(gen).zipWith(right.run(gen))(_ && _)
    end Both

    /** Constructs a law from a pure function taking a single parameter.
      */
    abstract class Law1[-Caps[_]](label: String) extends ZLaws[Caps, Any]:
        self =>
        def apply[A: Caps](a1: A): TestResult
        final def run[R, A: Caps](gen: Gen[R, A])(implicit trace: Trace): TestResult < Env[R] =
            check(gen)(apply(_).??(label))
    end Law1

    /** Constructs a law from an effectual function taking a single parameter.
      */
    abstract class Law1Kyo[-Caps[_], -R](label: String) extends ZLaws[Caps, R]:
        self =>
        def apply[A: Caps](a1: A): TestResult < Env[R]
        final def run[R1 <: R, A: Caps](gen: Gen[R1, A])(implicit
            trace: Trace
        ): TestResult < Env[R1] & Abort[Nothing] =
            check(gen)(apply(_).map(_.??(label)))
    end Law1Kyo

    /** Constructs a law from a pure function taking two parameters.
      */
    abstract class Law2[-Caps[_]](label: String) extends ZLaws[Caps, Any]:
        self =>
        def apply[A: Caps](a1: A, a2: A): TestResult
        final def run[R, A: Caps](gen: Gen[R, A])(implicit trace: Trace): TestResult < Env[R] =
            check(gen, gen)(apply(_, _).label(label))
    end Law2

    /** Constructs a law from an effectual function taking two parameters.
      */
    abstract class Law2Kyo[-Caps[_], -R](label: String) extends ZLaws[Caps, R]:
        self =>
        def apply[A: Caps](a1: A, a2: A): TestResult < Env[R]
        final def run[R1 <: R, A: Caps](gen: Gen[R1, A])(implicit
            trace: Trace
        ): TestResult < Env[R1] & Abort[Nothing] =
            check(gen, gen)(apply(_, _).map(_.label(label)))
    end Law2Kyo

    /** Constructs a law from a pure function taking three parameters.
      */
    abstract class Law3[-Caps[_]](label: String) extends ZLaws[Caps, Any]:
        self =>
        def apply[A: Caps](a1: A, a2: A, a3: A): TestResult
        final def run[R, A: Caps](gen: Gen[R, A])(implicit trace: Trace): TestResult < Env[R] =
            check(gen, gen, gen)(apply(_, _, _).label(label))
    end Law3

    /** Constructs a law from an effectual function taking three parameters.
      */
    abstract class Law3Kyo[-Caps[_], -R](label: String) extends ZLaws[Caps, R]:
        self =>
        def apply[A: Caps](a1: A, a2: A, a3: A): TestResult < Env[R]
        final def run[R1 <: R, A: Caps](gen: Gen[R1, A])(implicit
            trace: Trace
        ): TestResult < Env[R1] & Abort[Nothing] =
            check(gen, gen, gen)(apply(_, _, _).map(_.label(label)))
    end Law3Kyo
end ZLaws
