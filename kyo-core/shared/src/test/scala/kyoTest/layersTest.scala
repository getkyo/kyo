package kyoTest

import kyo.*

class layersTest extends KyoTest:
    "composition" - {
        "a to b" in {
            case class Food(calories: Int)
            case class Stomach(food: Food)

            val foodLayer: Layer[Any, Food, Any]        = Layers(Food(100))
            val stomachLayer: Layer[Food, Stomach, Any] = Layers.from(Stomach.apply)
            val composed: Layer[Any, Stomach, Any]      = foodLayer to stomachLayer

            val env = composed.run.pure
            assert(env.get[Stomach].food.calories == 100)
        }

        "a and b" in {
            case class Water(liters: Int)
            case class Fire(heat: Int)

            val waterLayer: Layer[Any, Water, Any] = Layers(Water(2))
            val fireLayer: Layer[Any, Fire, Any]   = Layers(Fire(500))

            val combinedLayer: Layer[Any, Water & Fire, Any] = waterLayer and fireLayer

            val env = combinedLayer.run.pure
            assert(env.get[Water].liters == 2)
            assert(env.get[Fire].heat == 500)

        }

        "a using b using c" in {
            case class Guppy(name: String)
            case class Shark(belly: Guppy)
            case class MegaShark(belly: Shark)

            val guppyLayer: Layer[Any, Guppy, Any]           = Layers(Guppy("Tiny Guppy"))
            val sharkLayer: Layer[Guppy, Shark, Any]         = Layers.from(g => Shark(g))
            val megaSharkLayer: Layer[Shark, MegaShark, Any] = Layers.from(s => MegaShark(s))

            val combinedLayer: Layer[Any, Guppy & Shark & MegaShark, Any] = guppyLayer using sharkLayer using megaSharkLayer

            val env = combinedLayer.run.pure
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

            val second: Layer[Value, Value, Any] = Layers {
                for
                    value <- Envs.get[Value]
                yield new Value:
                    override def result: String = value.result + " Second"
            }

            val third: Layer[Value, Value, Any] = Layers {
                for
                    value <- Envs.get[Value]
                yield new Value:
                    override def result: String = value.result + " Third"
            }

            val all: Layer[Any, Value, Any] = first to second to third
            assert(all.run.pure.get[Value].result == "First Second Third")

            val firstThird: Layer[Any, Value, Any] = first to third
            assert(firstThird.run.pure.get[Value].result == "First Third")

            val firstThirdSecond: Layer[Any, Value, Any] = first to third to second
            assert(firstThirdSecond.run.pure.get[Value].result == "First Third Second")

            val withRepeats: Layer[Any, Value, Any] = first to second to second to second to third to third
            assert(withRepeats.run.pure.get[Value].result == "First Second Third")
        }
    }

    "memoization" - {
        "shared to a and shared to b" in run {
            case class MagicSource(name: String)
            case class Wizard(name: String, source: MagicSource)
            case class Spell(powerLevel: Int, source: MagicSource)

            var sourceInitializationCount = 0

            val magicSourceLayer: Layer[Any, MagicSource, IOs] = Layers {
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
end layersTest
