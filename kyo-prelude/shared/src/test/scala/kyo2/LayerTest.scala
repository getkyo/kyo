package kyo2

class LayerTest extends Test:

    "composition" - {
        "a to b" in run {
            case class Food(calories: Int)
            case class Stomach(food: Food)

            val foodLayer    = Layer(Food(100))
            val stomachLayer = Layer.from(Stomach(_))
            val composed     = foodLayer to stomachLayer

            Memo.run(composed.run).map { env =>
                assert(env.get[Stomach].food.calories == 100)
            }
        }

        "a and b" in {
            case class Water(liters: Int)
            case class Fire(heat: Int)

            val waterLayer = Layer(Water(2))
            val fireLayer  = Layer(Fire(500))

            val combinedLayer = waterLayer and fireLayer

            val env = Memo.run(combinedLayer.run).eval
            assert(env.get[Water].liters == 2)
            assert(env.get[Fire].heat == 500)

        }

        "a using b using c" in {
            case class Guppy(name: String)
            case class Shark(belly: Guppy)
            case class MegaShark(belly: Shark)

            val guppyLayer     = Layer(Guppy("Tiny Guppy"))
            val sharkLayer     = Layer.from(g => Shark(g))
            val megaSharkLayer = Layer.from(s => MegaShark(s))

            val combinedLayer = guppyLayer using sharkLayer using megaSharkLayer

            val env = Memo.run(combinedLayer.run).eval
            assert(env.get[Guppy].name == "Tiny Guppy")
            assert(env.get[Shark].belly.name == "Tiny Guppy")
            assert(env.get[MegaShark].belly.belly.name == "Tiny Guppy")
        }

        "In =:= Out" in {
            trait Value:
                def result: String

            val first = Layer {
                new Value:
                    override def result: String = "First"
            }

            val second = Layer {
                for
                    value <- Env.get[Value]
                yield new Value:
                    override def result: String = value.result + " Second"
            }

            val third = Layer {
                for
                    value <- Env.get[Value]
                yield new Value:
                    override def result: String = value.result + " Third"
            }

            val all = first to second to third
            assert(Memo.run(all.run).eval.get[Value].result == "First Second Third")

            val firstThird = first to third
            assert(Memo.run(firstThird.run).eval.get[Value].result == "First Third")

            val firstThirdSecond = first to third to second
            assert(Memo.run(firstThirdSecond.run).eval.get[Value].result == "First Third Second")

            val withRepeats = first to second to second to second to third to third
            assert(Memo.run(withRepeats.run).eval.get[Value].result == "First Second Third")
        }
    }

    "memoization" - {
        "shared to a and shared to b" in run {
            case class MagicSource(name: String)
            case class Wizard(name: String, source: MagicSource)
            case class Spell(powerLevel: Int, source: MagicSource)

            var sourceInitializationCount = 0

            val magicSourceLayer = Layer {
                sourceInitializationCount += 1
                MagicSource("Fireball")
            }

            val wizardLayer = magicSourceLayer to Layer.from(source => Wizard("Gandalf", source))
            val spellLayer  = magicSourceLayer to Layer.from(source => Spell(100, source))

            val combinedLayer = wizardLayer and spellLayer

            Memo.run(combinedLayer.run).map { env =>
                assert(sourceInitializationCount == 1)
                assert(env.get[Wizard].name == "Gandalf")
                assert(env.get[Spell].powerLevel == 100)
                assert(env.get[Spell].source.name == "Fireball")
                assert(env.get[Wizard].source.name == "Fireball")
            }
        }
    }
    "effects!" - {
        "Abort" in {
            val shouldSucceed = Layer("A good string")
            val shouldFail    = Layer("")
            val maybeFail: Layer[Int, Env[String] & Abort[String]] =
                Layer.from { (s: String) => Abort.when(s.length < 6)("Too short!").andThen(s.length) }

            val s = shouldSucceed to maybeFail
            val f = shouldFail to maybeFail

            Memo.run(
                Abort.run(s.run).map {
                    case Result.Success(env: TypeMap[Int]) => assert(env.get[Int] == 13)
                    case _                                 => fail("Should not have aborted!")
                }
            )
            Memo.run(
                Abort.run(f.run).map { result =>
                    assert(result.failure.contains("Too short!"))
                }
            ).eval
        }
        "Var" in {
            val int    = Layer(Var.get[Int])
            val string = Layer.from((i: Int) => i.toString)
            val length = Layer.from((s: String) => Var.update((_: Int) => s.length).map(_ => s.length))

            val c = int to string to length

            Memo.run(Var.run(42)(c.run.map(env => Var.get[Int].map(_ -> env))).map { (varI, env) =>
                assert(env.get[Int] == 2)
                assert(varI == 2)
            }).eval
        }
    }
    "make" - {
        "none" in {
            assertDoesNotCompile("""Layer.init[String]()""")
        }
        "missing Target" in {
            val a = Layer(true)
            assertDoesNotCompile("""Layer.init(a)""")
        }
        "circular dependency" in {
            val a = Layer.from((s: String) => s.size)
            val b = Layer.from((i: Int) => (i % 2 == 0))
            val c = Layer.from((b: Boolean) => b.toString)

            assertDoesNotCompile("""Layer.init[String](a, b, c)""")
        }
        "missing input" in {
            val a = Layer.from((s: String) => s.size)
            val b = Layer.from((i: Int) => i % 2 == 0)

            assertDoesNotCompile("""Layer.init[Boolean](a, b)""")
        }
        "missing multiple inputs" in {
            val a = Layer.from((s: String) => s.isEmpty)
            val b = Layer.from((i: Int) => i.toDouble)
            val c = Layer.from((_: Boolean, _: Double) => 'c')

            assertDoesNotCompile("""Layer.init[Char](a, b, c)""")
        }
        "missing output" in {
            val a = Layer(42)
            val b = Layer.from((i: Int) => i.toDouble)
            val c = Layer.from((d: Double) => d.toLong)

            assertDoesNotCompile("""Layer.init[String](a, b, c)""")
        }
        "ambigious input" in {
            val a = Layer(42)
            val b = Layer(42)

            assertDoesNotCompile("""Layer.init[Int](a, b)""")
        }
        "complex layer" in run {
            val s = Layer("Start")
            val i = Layer.from((s: String) => s.size)
            val b = Layer.from((i: Int) => i % 2 == 0)
            case class Several(s: String, i: Int, b: Boolean)
            val several = Layer.from((s, i, b) => Several(s, i, b))

            trait Parent
            class Child(val age: Int) extends Parent
            val child = Layer.from((age: Int) => Child(age * 2))

            case class GrandChild(several: Several, age: Int, parent: Child) extends Parent
            val grandChild = Layer.from((several, age, child) => GrandChild(several, age, child))

            Layer.init[GrandChild](s, i, b, several, child, grandChild).run.map { env =>
                val grandchild = env.get[GrandChild]
                assert(grandchild.several.s == "Start")
                assert(grandchild.several.i == 5)
                assert(grandchild.several.b == false)
                assert(grandchild.age == 5)
                assert(grandchild.parent.age == 10)

                assert(env.size == 1)
            }.pipe(Memo.run)
        }
        "effects" in run {
            class A
            case class B(a: A)
            case class C(b: B)

            val a = Layer(new A)
            val b = Layer.from((a: A) => Abort.when(false)("").andThen(B(a)))
            val c = Layer.from((b: B) => Var.get[Unit].andThen(C(b)))

            assertCompiles("""Layer.init[C](a, b, c)""")
        }
        "pruneable inputs" in pendingUntilFixed {
            val a = Layer("")
            val b = Layer(true)
            val c = Layer(0)

            assertDoesNotCompile("""Layer.init[String](a, b, c)""")
        }
    }
    "runLayer" - {
        "no layer" in {
            assertDoesNotCompile("""Env.runLayer()(Env.get[Boolean])""")
        }
        "one layer" in run {
            class Foo:
                def bar: Boolean = true
            val foo = Layer(Foo())
            Memo.run(Env.runLayer(foo)(Env.get[Foo].map(_.bar).map(assert(_))))
        }
        "multiple layers" in run {
            val a = Layer(42)
            val b = Layer.from((i: Int) => i.toString)
            val c = Layer.from((s: String) => (s.length % 2 == 0))

            Memo.run(Env.runLayer(a, b, c)(Env.get[Boolean].map(assert(_))))
        }
        "missing layer" in {
            val c = Layer('c')
            val d = Layer.from((c: Char) => c.isDigit)

            assertDoesNotCompile("""Env.runLayer(c, d)(Env.get[String])""")
        }
        "intersection" in run {
            Memo.run(Env.runLayer(Layer(42), Layer("hello"))(Env.get[Int].map(_ => Env.get[String]).map(_ => succeed)))
        }
    }
end LayerTest
