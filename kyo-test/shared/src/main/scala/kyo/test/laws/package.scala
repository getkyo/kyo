package kyo.test

import kyo.*

/** The `laws` package provides functionality for describing laws as values. The fundamental abstraction is a set of `ZLaws[Caps, R]`. These
  * laws model the laws that instances having a capability of type `Caps` are expected to satisfy. A capability `Caps[_]` is an abstraction
  * describing some functionality that is common across different data types and obeys certain laws. For example, we can model the
  * capability of two values of a type being compared for equality as follows:
  *
  * {{{
  * trait Equal[-A] {
  *   def equal(a1: A, a2: A): Boolean
  * }
  * }}}
  *
  * Definitions of equality are expected to obey certain laws:
  *   1. Reflexivity - `a1 === a1`
  *   1. Symmetry - `a1 === a2 ==> a2 === a1`
  *   1. Transitivity - `(a1 === a2) && (a2 === a3) ==> (a1 === a3)`
  *
  * These laws define what the capabilities mean and ensure that it is safe to abstract across different instances with the same capability.
  *
  * Using Kyo Test, we can represent these laws as values. To do so, we define each law using one of the `ZLaws` constructors. For example:
  *
  * {{{
  * val transitivityLaw = ZLaws.Laws3[Equal]("transitivityLaw") {
  *   def apply[A: Equal](a1: A, a2: A, a3: A): TestResult =
  *     ???
  * }
  * }}}
  *
  * We can then combine laws using the `+` operator:
  *
  * {{{
  * val reflexivityLaw: = ???
  * val symmetryLaw:    = ???
  *
  * val equalLaws = reflexivityLaw + symmetryLaw + transitivityLaw
  * }}}
  *
  * Laws have a `run` method that takes a generator of values of type `A` and checks that those values satisfy the laws. In addition,
  * objects can extend `ZLawful` to provide an even more convenient syntax for users to check that instances satisfy certain laws.
  *
  * {{{
  * object Equal extends Lawful[Equal]
  *
  * object Hash extends Lawful[Hash]
  *
  * object Ord extends Lawful[Ord]
  *
  * checkAllLaws(Equal + Hash + Ord)(Gen.int)
  * }}}
  *
  * Note that capabilities compose seamlessly because of contravariance. We can combine laws describing different capabilities to construct
  * a set of laws requiring that instances having all of the capabilities satisfy each of the laws.
  */
package object laws:

    type Lawful[-Caps[_]]                                      = ZLawful[Caps, Any]
    type Lawful2[-CapsBoth[_, _], -CapsLeft[_], -CapsRight[_]] = ZLawful2[CapsBoth, CapsLeft, CapsRight, Any]
    type Laws[-Caps[_]]                                        = ZLaws[Caps, Any]
    type Laws2[-CapsBoth[_, _], -CapsLeft[_], -CapsRight[_]]   = ZLaws2[CapsBoth, CapsLeft, CapsRight, Any]

    object LawfulF:
        type Covariant[-CapsF[_[+_]], -Caps[_]]     = ZLawfulF.Covariant[CapsF, Caps, Any]
        type Contravariant[-CapsF[_[-_]], -Caps[_]] = ZLawfulF.Contravariant[CapsF, Caps, Any]
        type Invariant[-CapsF[_[_]], -Caps[_]]      = ZLawfulF.Invariant[CapsF, Caps, Any]
    end LawfulF

    object LawfulF2:
        type Divariant[-CapsBoth[_[-_, +_]], -CapsLeft[_], -CapsRight[_]] =
            ZLawfulF2.Divariant[CapsBoth, CapsLeft, CapsRight, Any]

    object Laws:
        type Law1[-Caps[_]]    = ZLaws.Law1[Caps]
        type Law1Kyo[-Caps[_]] = ZLaws.Law1Kyo[Caps, Any]
        type Law2[-Caps[_]]    = ZLaws.Law2[Caps]
        type Law2Kyo[-Caps[_]] = ZLaws.Law2Kyo[Caps, Any]
        type Law3[-Caps[_]]    = ZLaws.Law3[Caps]
        type Law3Kyo[-Caps[_]] = ZLaws.Law3Kyo[Caps, Any]
    end Laws

    object Laws2:
        type Law1Left[-CapsBoth[_, _], -CapsLeft[_], -CapsRight[_]]  = ZLaws2.Law1Left[CapsBoth, CapsLeft, CapsRight]
        type Law1Right[-CapsBoth[_, _], -CapsLeft[_], -CapsRight[_]] = ZLaws2.Law1Right[CapsBoth, CapsLeft, CapsRight]

    object LawsF:

        type Covariant[-CapsF[_[+_]], -Caps[_]]     = ZLawsF.Covariant[CapsF, Caps, Any]
        type Contravariant[-CapsF[_[-_]], -Caps[_]] = ZLawsF.Contravariant[CapsF, Caps, Any]
        type Invariant[-CapsF[_[_]], -Caps[_]]      = ZLawsF.Invariant[CapsF, Caps, Any]

        object Covariant:
            type ComposeLaw[-CapsF[_[+_]], -Caps[_]] = ZLawsF.Covariant.ComposeLaw[CapsF, Caps]
            type FlattenLaw[-CapsF[_[+_]], -Caps[_]] = ZLawsF.Covariant.FlattenLaw[CapsF, Caps]
            type Law1[-CapsF[_[+_]], -Caps[_]]       = ZLawsF.Covariant.Law1[CapsF, Caps]
            type Law1Kyo[-CapsF[_[+_]], -Caps[_]]    = ZLawsF.Covariant.Law1Kyo[CapsF, Caps, Any]
            type Law2[-CapsF[_[+_]], -Caps[_]]       = ZLawsF.Covariant.Law2[CapsF, Caps]
            type Law2Kyo[-CapsF[_[+_]], -Caps[_]]    = ZLawsF.Covariant.Law2Kyo[CapsF, Caps, Any]
            type Law3[-CapsF[_[+_]], -Caps[_]]       = ZLawsF.Covariant.Law3[CapsF, Caps]
            type Law3Kyo[-CapsF[_[+_]], -Caps[_]]    = ZLawsF.Covariant.Law3Kyo[CapsF, Caps, Any]
        end Covariant

        object Contravariant:
            type ComposeLaw[-CapsF[_[-_]], -Caps[_]] = ZLawsF.Contravariant.ComposeLaw[CapsF, Caps]
            type Law1[-CapsF[_[-_]], -Caps[_]]       = ZLawsF.Contravariant.Law1[CapsF, Caps]
            type Law1Kyo[-CapsF[_[-_]], -Caps[_]]    = ZLawsF.Contravariant.Law1Kyo[CapsF, Caps, Any]
            type Law2[-CapsF[_[-_]], -Caps[_]]       = ZLawsF.Contravariant.Law2[CapsF, Caps]
            type Law2Kyo[-CapsF[_[-_]], -Caps[_]]    = ZLawsF.Contravariant.Law2Kyo[CapsF, Caps, Any]
            type Law3[-CapsF[_[-_]], -Caps[_]]       = ZLawsF.Contravariant.Law3[CapsF, Caps]
            type Law3Kyo[-CapsF[_[-_]], -Caps[_]]    = ZLawsF.Contravariant.Law3Kyo[CapsF, Caps, Any]
        end Contravariant

        object Invariant:
            type Law1[-CapsF[_[_]], -Caps[_]]    = ZLawsF.Invariant.Law1[CapsF, Caps]
            type Law1Kyo[-CapsF[_[_]], -Caps[_]] = ZLawsF.Invariant.Law1Kyo[CapsF, Caps, Any]
            type Law2[-CapsF[_[_]], -Caps[_]]    = ZLawsF.Invariant.Law2[CapsF, Caps]
            type Law2Kyo[-CapsF[_[_]], -Caps[_]] = ZLawsF.Invariant.Law2Kyo[CapsF, Caps, Any]
            type Law3[-CapsF[_[_]], -Caps[_]]    = ZLawsF.Invariant.Law3[CapsF, Caps]
            type Law3Kyo[-CapsF[_[_]], -Caps[_]] = ZLawsF.Invariant.Law3Kyo[CapsF, Caps, Any]
        end Invariant
    end LawsF

    object LawsF2:
        type Divariant[-CapsBoth[_[-_, +_]], -CapsLeft[_], -CapsRight[_]] =
            ZLawsF2.Divariant[CapsBoth, CapsLeft, CapsRight, Any]

        object Divariant:
            type ComposeLaw[-CapsBothF[_[-_, +_]], -Caps[_]] = ZLawsF2.Divariant.ComposeLaw[CapsBothF, Caps]
            type Law1[CapsBothF[_[-_, +_]], -CapsLeft[_], -CapsRight[_]] =
                ZLawsF2.Divariant.Law1[CapsBothF, CapsLeft, CapsRight]
        end Divariant
    end LawsF2

    /** Checks that all values generated by a the specified generator satisfy the expected behavior of the lawful instance.
      */
    def checkAllLaws[Caps[_], R, R1 <: R, A: Caps](
        lawful: ZLawful[Caps, R]
    )(gen: Gen[R1, A])(implicit trace: Trace): TestResult < Env[R1] & Abort[Nothing] =
        lawful.laws.run(gen)

    /** Checks that all values generated by a the specified generator satisfy the expected behavior of the lawful instance.
      */
    def checkAllLaws[CapsBoth[_, _], CapsLeft[_], CapsRight[_], R, R1 <: R, A: CapsLeft, B: CapsRight](
        lawful: ZLawful2[CapsBoth, CapsLeft, CapsRight, R]
    )(a: Gen[R1, A], b: Gen[R1, B])(implicit
        CapsBoth: CapsBoth[A, B],
        trace: Trace
    ): TestResult < Env[R1] & Abort[Nothing] =
        lawful.laws.run(a, b)

    def checkAllLaws[CapsF[_[+_]], Caps[_], R, R1 <: R, F[+_]: CapsF, A: Caps](
        lawful: ZLawfulF.Covariant[CapsF, Caps, R]
    )(genF: GenF[R1, F], gen: Gen[R1, A])(implicit trace: Trace): TestResult < Env[R1] & Abort[Nothing] =
        lawful.laws.run(genF, gen)

    /** Checks that all values generated by a the specified generator satisfy the expected behavior of the lawful instance.
      */
    def checkAllLaws[CapsF[_[-_]], Caps[_], R, R1 <: R, F[-_]: CapsF, A: Caps](
        lawful: ZLawfulF.Contravariant[CapsF, Caps, R]
    )(genF: GenF[R1, F], gen: Gen[R1, A])(implicit trace: Trace): TestResult < Env[R1] & Abort[Nothing] =
        lawful.laws.run(genF, gen)

    /** Checks that all values generated by a the specified generator satisfy the expected behavior of the lawful instance.
      */
    def checkAllLaws[CapsF[_[_]], Caps[_], R, R1 <: R, F[_]: CapsF, A: Caps](
        lawful: ZLawfulF.Invariant[CapsF, Caps, R]
    )(genF: GenF[R1, F], gen: Gen[R1, A])(implicit trace: Trace): TestResult < Env[R1] & Abort[Nothing] =
        lawful.laws.run(genF, gen)
end laws
