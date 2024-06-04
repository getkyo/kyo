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

            val env = combinedLayer.run.pure
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
            assert(all.run.pure.get[Value].result == "First Second Third")

            val firstThird = first to third
            assert(firstThird.run.pure.get[Value].result == "First Third")

            val firstThirdSecond = first to third to second
            assert(firstThirdSecond.run.pure.get[Value].result == "First Third Second")

            val withRepeats = first to second to second to second to third to third
            assert(withRepeats.run.pure.get[Value].result == "First Second Third")
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
end layersTest
