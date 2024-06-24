package kyoTest

import kyo.*

class layersTest extends KyoTest:
    "composition" - {
        "a to b" in run {
            case class Food(calories: Int)
            case class Stomach(food: Food)

            val foodLayer    = Layers(IOs(Food(100)))
            val stomachLayer = Layers.from(Stomach.apply)
            val composed     = foodLayer to stomachLayer

            composed.run.map { env =>
                assert(env.get[Stomach].food.calories == 100)
            }
        }

        "a and b" in {
            case class Water(liters: Int)
            case class Fire(heat: Int)

            val waterLayer = Layers(Water(2))
            val fireLayer  = Layers(Fire(500))

            val combinedLayer = waterLayer and fireLayer

            val env = IOs.run(combinedLayer.run).pure
            assert(env.get[Water].liters == 2)
            assert(env.get[Fire].heat == 500)

        }

        "a using b using c" in {
            case class Guppy(name: String)
            case class Shark(belly: Guppy)
            case class MegaShark(belly: Shark)

            val guppyLayer     = Layers(Guppy("Tiny Guppy"))
            val sharkLayer     = Layers.from(g => Shark(g))
            val megaSharkLayer = Layers.from(s => MegaShark(s))

            val combinedLayer = guppyLayer using sharkLayer using megaSharkLayer

            val env = IOs.run(combinedLayer.run).pure
            assert(env.get[Guppy].name == "Tiny Guppy")
            assert(env.get[Shark].belly.name == "Tiny Guppy")
            assert(env.get[MegaShark].belly.belly.name == "Tiny Guppy")
        }

        "In =:= Out" in {
            trait Value:
                def result: String

            val first = Layers {
                new Value:
                    override def result: String = "First"
            }

            val second = Layers {
                for
                    value <- Envs.get[Value]
                yield new Value:
                    override def result: String = value.result + " Second"
            }

            val third = Layers {
                for
                    value <- Envs.get[Value]
                yield new Value:
                    override def result: String = value.result + " Third"
            }

            val all = first to second to third
            assert(IOs.run(all.run).pure.get[Value].result == "First Second Third")

            val firstThird = first to third
            assert(IOs.run(firstThird.run).pure.get[Value].result == "First Third")

            val firstThirdSecond = first to third to second
            assert(IOs.run(firstThirdSecond.run).pure.get[Value].result == "First Third Second")

            val withRepeats = first to second to second to second to third to third
            assert(IOs.run(withRepeats.run).pure.get[Value].result == "First Second Third")
        }
    }

    "memoization" - {
        "shared to a and shared to b" in run {
            case class MagicSource(name: String)
            case class Wizard(name: String, source: MagicSource)
            case class Spell(powerLevel: Int, source: MagicSource)

            var sourceInitializationCount = 0

            val magicSourceLayer = Layers {
                IOs { sourceInitializationCount += 1 }.map { _ => MagicSource("Fireball") }
            }

            val wizardLayer = magicSourceLayer to Layers.from(source => Wizard("Gandalf", source))
            val spellLayer  = magicSourceLayer to Layers.from(source => Spell(100, source))

            val combinedLayer = wizardLayer and spellLayer

            combinedLayer.run.map { env =>
                assert(sourceInitializationCount == 1)
                assert(env.get[Wizard].name == "Gandalf")
                assert(env.get[Spell].powerLevel == 100)
                assert(env.get[Spell].source.name == "Fireball")
                assert(env.get[Wizard].source.name == "Fireball")
            }
        }
    }
    "effects!" - {
        "Aborts" in {
            val shouldSucceed = Layers("A good string")
            val shouldFail    = Layers("")
            val maybeFail: Layer[Int, Envs[String] & Aborts[String]] =
                Layers.from { (s: String) => Aborts.when(s.length < 6)("Too short!").andThen(s.length) }

            val s = shouldSucceed to maybeFail
            val f = shouldFail to maybeFail

            IOs.run(
                Aborts.run(s.run).map {
                    case Right(env: TypeMap[Int]) => assert(env.get[Int] == 13)
                    case Left(_)                  => fail("Should not have aborted!")
                }
            )
            IOs.run(
                Aborts.run(f.run).map {
                    case Right(_)    => fail("Should not have succeeded!")
                    case Left(error) => assert(error == "Too short!")
                }
            )
        }
        "Vars" in {
            val int    = Layers(Vars.get[Int])
            val string = Layers.from((i: Int) => i.toString)
            val length = Layers.from((s: String) => Vars.update((_: Int) => s.length).andThen(s.length))

            val c = int to string to length

            IOs.run(Vars.run(42)(c.run.map(env => Vars.get[Int].map(_ -> env))).map { (varI, env) =>
                assert(env.get[Int] == 2)
                assert(varI == 2)
            })
        }
        "Fibers" in runJVM {
            val slow = Fibers.delay(50.millis)(IOs("slow"))
            val fast = IOs("fast")
            val f    = Layers(Fibers.race(fast, slow))

            f.run.map(env => assert(env.get[String] == "fast"))
        }
    }
    "make" - {
        "none" in {
            assertDoesNotCompile("""Layers.init[String]()""")
        }
        "missing Target" in {
            val a = Layers(true)
            assertDoesNotCompile("""Layers.init(a)""")
        }
        "circular dependency" in {
            val a = Layers.from((s: String) => s.size)
            val b = Layers.from((i: Int) => i % 2 == 0)
            val c = Layers.from((b: Boolean) => b.toString)

            assertDoesNotCompile("""Layers.init[String](a, b, c)""")
        }
        "missing input" in {
            val a = Layers.from((s: String) => s.size)
            val b = Layers.from((i: Int) => i % 2 == 0)

            assertDoesNotCompile("""Layers.init[Boolean](a, b)""")
        }
        "missing multiple inputs" in {
            val a = Layers.from((s: String) => s.isEmpty)
            val b = Layers.from((i: Int) => i.toDouble)
            val c = Layers.from((_: Boolean, _: Double) => 'c')

            assertDoesNotCompile("""Layers.init[Char](a, b, c)""")
        }
        "missing output" in {
            val a = Layers(42)
            val b = Layers.from((i: Int) => i.toDouble)
            val c = Layers.from((d: Double) => d.toLong)

            assertDoesNotCompile("""Layers.init[String](a, b, c)""")
        }
        "ambigious input" in {
            val a = Layers(42)
            val b = Layers(42)

            assertDoesNotCompile("""Layers.init[Int](a, b)""")
        }
        "complex layer" in run {
            val s = Layers("Start")
            val i = Layers.from((s: String) => s.size)
            val b = Layers.from((i: Int) => i % 2 == 0)
            case class Several(s: String, i: Int, b: Boolean)
            val several = Layers.from((s, i, b) => Several(s, i, b))

            trait Parent
            class Child(val age: Int) extends Parent
            val child = Layers.from((age: Int) => Child(age * 2))

            case class GrandChild(several: Several, age: Int, parent: Child) extends Parent
            val grandChild = Layers.from((several, age, child) => GrandChild(several, age, child))

            Layers.init[GrandChild](s, i, b, several, child, grandChild).run.map { env =>
                val grandchild = env.get[GrandChild]
                assert(grandchild.several.s == "Start")
                assert(grandchild.several.i == 5)
                assert(grandchild.several.b == false)
                assert(grandchild.age == 5)
                assert(grandchild.parent.age == 10)

                assert(env.size == 1)
            }
        }
        "effects" in run {
            class A
            case class B(a: A)
            case class C(b: B)

            val a = Layers(IOs(new A))
            val b = Layers.from((a: A) => Aborts.when(false)("").andThen(B(a)))
            val c = Layers.from((b: B) => Vars.get[Unit].andThen(C(b)))

            assertCompiles("""Layers.init[C](a, b, c)""")
        }
        "pruneable inputs" in pendingUntilFixed {
            val a = Layers("")
            val b = Layers(true)
            val c = Layers(0)

            assertDoesNotCompile("""Layers.init[String](a, b, c)""")
        }
    }
    "runLayers" - {
        "no layer" in {
            assertDoesNotCompile("""Envs.runLayers()(Envs.get[Boolean])""")
        }
        "one layer" in run {
            class Foo:
                def bar: Boolean = true
            val foo = Layers(Foo())
            Envs.runLayers(foo)(Envs.get[Foo].map(_.bar).map(assert(_)))
        }
        "multiple layers" in run {
            val a = Layers(42)
            val b = Layers.from((i: Int) => i.toString)
            val c = Layers.from((s: String) => s.length % 2 == 0)

            Envs.runLayers(a, b, c)(Envs.get[Boolean].map(assert(_)))
        }
        "missing layer" in {
            val c = Layers('c')
            val d = Layers.from((c: Char) => c.isDigit)

            assertDoesNotCompile("""Envs.runLayers(c, d)(Envs.get[String])""")
        }
        "intersection" in run {
            Envs.runLayers(Layers(42), Layers("hello"))(Envs.get[Int].map(_ => Envs.get[String]).map(_ => succeed))
        }
    }
end layersTest
