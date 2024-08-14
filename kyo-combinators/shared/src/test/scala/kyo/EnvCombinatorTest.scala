package KyoTest

import kyo.*
import kyoTest.KyoTest

class envsTest extends KyoTest:

    class Dep(val value: Int)
    object DepImpl extends Dep(1)

    "envs" - {
        "construct" - {
            "should construct from type" in {
                val effect = Kyo.service[String]
                assert(Envs.run("value")(effect).pure == "value")
            }

            "should construct from type and use" in {
                val effect = Kyo.serviceWith[String](_.length)
                assert(Envs.run("value")(effect).pure == 5)
            }
        }

        "handle" - {
            "should provide" in {
                val effect: Int < Envs[String] = Envs.get[String].map(_.length)
                assert(effect.provide("value").pure == 5)
            }

            "should provide as" in {
                val effect: Int < Envs[Dep] = Envs.get[Dep].map(_.value)
                assert(effect.provideAs[Dep](DepImpl).pure == 1)
            }

            "should provide incrementally" in {
                val effect: Int < Envs[String & Int & Boolean & Char] =
                    Envs.get[String] *> Envs.get[Int] *> Envs.get[Boolean] *> Envs.get[Char].as(23)
                val handled =
                    effect
                        .provide('c')
                        .provide("value")
                        .provide(1)
                        .provide(false)
                assert(handled.pure == 23)
            }
        }
    }

end envsTest
