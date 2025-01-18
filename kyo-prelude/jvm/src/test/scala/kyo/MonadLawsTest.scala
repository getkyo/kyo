package kyo

import Flat.unsafe.bypass
import kyo.Result
import zio.Trace
import zio.prelude.Equal
import zio.prelude.coherent.CovariantDeriveEqual
import zio.prelude.coherent.CovariantDeriveEqualIdentityFlatten
import zio.prelude.laws.*
import zio.test.*
import zio.test.laws.*

object MonadLawsTest extends ZIOSpecDefault:

    type Myo[+A] = A < (Env[String] & Abort[String] & Emit[Int] & Var[Boolean])

    val listGenF: GenF[Any, Myo] =
        new GenF[Any, Myo]:
            def apply[R, A](gen: Gen[R, A])(using trace: Trace) =
                val boolGen   = Gen.boolean
                val intGen    = Gen.int
                val stringGen = Gen.string

                Gen.oneOf(
                    gen.map(v => (v: A < Any)),
                    gen.zip(boolGen).map((v, b) =>
                        if b then Abort.fail("fail") else (v: A < Any)
                    ),
                    gen.zip(intGen).map((v, i) =>
                        Emit.value(i).andThen(v)
                    ),
                    gen.zip(boolGen).map((v, b) =>
                        Var.setDiscard(b).andThen(v)
                    ),
                    gen.zip(stringGen).map((v, s) =>
                        Env.use[String](_ => ()).andThen(v)
                    ),
                    gen.zip(boolGen).map((v, b) =>
                        Var.get[Boolean].map(x => if x then v else Abort.fail("var fail"))
                    ),
                    gen.zip(intGen).map((v, i) =>
                        Emit.value(i).map(_ => if i % 2 == 0 then v else Abort.fail("sum fail"))
                    ),
                    gen.map(v =>
                        for
                            s <- Env.get[String]
                            _ <- Var.update[Boolean](!_)
                            i <- Emit.value(s.length)
                            _ <- Abort.when(s.length() > 10)("length exceeded")
                        yield v
                    )
                )
            end apply

    given CovariantDeriveEqualIdentityFlatten[Myo] =
        new CovariantDeriveEqualIdentityFlatten[Myo]:
            override def flatten[A](ffa: Myo[Myo[A]]): Myo[A] =
                ffa.flatten
            override def any: Myo[Any] =
                ()
            override def map[A, B](f: A => B): Myo[A] => Myo[B] =
                _.map(f(_))
            override def derive[A: Equal]: Equal[Myo[A]] =
                new Equal[Myo[A]]:
                    protected def checkEqual(l: Myo[A], r: Myo[A]): Boolean =
                        def run(m: Myo[A]): Result[String, A] =
                            Var.run(true)(
                                Emit.run(
                                    Abort.run(
                                        Env.run("test")(m)
                                    )
                                )
                            ).eval._2
                        (run(l), run(r)) match
                            case (Result.Success(l), Result.Success(r)) => summon[Equal[A]].equal(l, r)
                            case (Result.Failure(l), Result.Failure(r)) => l == r
                            case _                                      => false
                        end match
                    end checkEqual

    def spec = suite("MonadLawsTest")(
        test("covariant")(checkAllLaws(CovariantLaws)(listGenF, Gen.int)),
        test("identityFlatten")(
            checkAllLaws(IdentityFlattenLaws)(listGenF, Gen.int)
        )
    )
end MonadLawsTest
