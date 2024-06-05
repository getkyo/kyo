package kyoTest

import kyo.Maybe
import zio.Trace
import zio.prelude.Equal
import zio.prelude.coherent.CovariantDeriveEqual
import zio.prelude.coherent.CovariantDeriveEqualIdentityFlatten
import zio.prelude.laws.*
import zio.test.*
import zio.test.laws.*

object MaybeMonadLawsTest extends ZIOSpecDefault:

    val listGenF: GenF[Any, Maybe] =
        new GenF[Any, Maybe]:
            def apply[R, A](gen: Gen[R, A])(using trace: Trace) =
                Gen.oneOf(
                    gen.map(Maybe.Defined(_)),
                    Gen.const(Maybe.empty[A])
                )

    given CovariantDeriveEqualIdentityFlatten[Maybe] =
        new CovariantDeriveEqualIdentityFlatten[Maybe]:
            override def flatten[A](ffa: Maybe[Maybe[A]]): Maybe[A] =
                ffa.flatten
            override def any: Maybe[Any] =
                Maybe(())
            override def map[A, B](f: A => B): Maybe[A] => Maybe[B] =
                m => m.map(f)
            override def derive[A: Equal]: Equal[Maybe[A]] =
                new Equal[Maybe[A]]:
                    protected def checkEqual(l: Maybe[A], r: Maybe[A]): Boolean =
                        given CanEqual[A, A] = CanEqual.derived
                        l == r

    def spec = suite("MaybeMonadLawsTest")(
        test("covariant")(checkAllLaws(CovariantLaws)(listGenF, Gen.int)),
        test("identityFlatten")(
            checkAllLaws(IdentityFlattenLaws)(listGenF, Gen.int)
        )
    )
end MaybeMonadLawsTest
