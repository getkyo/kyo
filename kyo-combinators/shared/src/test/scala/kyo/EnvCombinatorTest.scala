package kyo

class EnvCombinatorTest extends Test:

    class Dep(val value: Int)
    object DepImpl extends Dep(1)

    "envs" - {
        "construct" - {
            "should construct from type" in {
                val effect = Kyo.service[String]
                assert(Env.run("value")(effect).eval == "value")
            }

            "should construct from type and use" in {
                val effect = Kyo.serviceWith[String](_.length)
                assert(Env.run("value")(effect).eval == 5)
            }
        }

        "handle" - {
            "should provide" in {
                val effect: Int < Env[String] = Env.get[String].map(_.length)
                assert(effect.provide("value").eval == 5)
            }

            "should provide incrementally" in {
                val effect: Int < Env[String & Int & Boolean & Char] =
                    Env.get[String] *> Env.get[Int] *> Env.get[Boolean] *> Env.get[Char].as(23)
                val handled =
                    effect
                        .provide('c')
                        .provide("value")
                        .provide(1)
                        .provide(false)
                assert(handled.eval == 23)
            }

            "should provide layer" in {
                val effect: Int < Env[String] = Env.get[String].map(_.length)
                val layer                     = Layer[String, Any]("value")
                assert(Memo.run(effect.provideLayer(layer)).eval == 5)
            }

            "should provide layer incrementally" in {
                val effect: Int < Env[String & Int & Boolean & Char] =
                    Env.get[String] *> Env.get[Int] *> Env.get[Boolean] *> Env.get[Char].as(23)
                val layerChar   = Layer('c')
                val layerString = Layer("value")
                val layerInt    = Layer(1)
                val layerBool   = Layer(false)
                val handled =
                    effect
                        .provideLayer(layerChar)
                        .provideLayer(layerString)
                        .provideLayer(layerInt)
                        .provideLayer(layerBool)
                assert(Memo.run(handled).eval == 23)
            }
        }
    }

end EnvCombinatorTest
