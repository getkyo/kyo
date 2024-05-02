package kyoTest

import kyo.*
import kyo.Flat.unsafe.bypass
import zio.Trace
import zio.prelude.Equal
import zio.prelude.coherent.CovariantDeriveEqual
import zio.prelude.coherent.CovariantDeriveEqualIdentityFlatten
import zio.prelude.laws.*
import zio.test.*
import zio.test.laws.*

object MonadLawsTest extends ZIOSpecDefault:

    case class Myo[+T](v: T < Defers)

    val listGenF: GenF[Any, Myo] =
        new GenF[Any, Myo]:
            def apply[R, A](gen: Gen[R, A])(using trace: Trace) =
                Gen.oneOf(
                    gen.map(v => (v: A < Defers)),
                    gen.map(v => Defers(v))
                ).map(Myo(_))

    given CovariantDeriveEqualIdentityFlatten[Myo] =
        new CovariantDeriveEqualIdentityFlatten[Myo]:
            override def flatten[A](ffa: Myo[Myo[A]]): Myo[A] =
                Myo(ffa.v.flatMap(_.v))
            override def any: Myo[Any] =
                Myo(())
            override def map[A, B](f: A => B): Myo[A] => Myo[B] =
                m => Myo[B](m.v.map(f))
            override def derive[A: Equal]: Equal[Myo[A]] =
                new Equal[Myo[A]]:
                    protected def checkEqual(l: Myo[A], r: Myo[A]): Boolean =
                        def run(m: Myo[A]): A = Defers.run(m.v).pure
                        summon[Equal[A]].equal(run(l), run(r))

    def spec = suite("MonadLawsTest")(
        test("covariant")(checkAllLaws(CovariantLaws)(listGenF, Gen.int)),
        test("identityFlatten")(
            checkAllLaws(IdentityFlattenLaws)(listGenF, Gen.int)
        )
    )
end MonadLawsTest
