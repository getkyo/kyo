package kyoTest

import kyo.*
// import kyo.syntax.*

// TODO:
// - Replace Envs with the implementation of EnvMaps
// - Allow Envs.run to accept a layer...
// - kyo.provide(food, heat, stomach, spells)
// - kyo.provide(food, heat, stomach, spells)
// - Envs.run(1)
class layersTest extends KyoTest:
    "composition" - {
        "a >>> b" in run {
            case class Food(calories: Int)
            case class Stomach(food: Food)

            val foodLayer: Layer[Any, Food]        = Layers(Food(100))
            val stomachLayer: Layer[Food, Stomach] = Layers.from(Stomach(_))
            val composed: Layer[Any, Stomach]      = foodLayer >>> stomachLayer

            composed.run.map { env =>
                assert(env.get[Stomach].food.calories == 100)
            }
        }

        "a ++ b" in run {
            case class Water(liters: Int)
            case class Fire(heat: Int)

            val waterLayer: Layer[Any, Water] = Layers(Water(2))
            val fireLayer: Layer[Any, Fire]   = Layers(Fire(500))

            val combinedLayer: Layer[Any, Water & Fire] = waterLayer ++ fireLayer

            combinedLayer.run.map { env =>
                assert(env.get[Water].liters == 2)
                assert(env.get[Fire].heat == 500)
            }
        }

        "a >+> b >+> c" in run {
            case class Guppy(name: String)
            case class Shark(belly: Guppy)
            case class MegaShark(belly: Shark)

            val guppyLayer: Layer[Any, Guppy]           = Layers(Guppy("Tiny Guppy"))
            val sharkLayer: Layer[Guppy, Shark]         = Layers.from(g => Shark(g))
            val megaSharkLayer: Layer[Shark, MegaShark] = Layers.from(s => MegaShark(s))

            val combinedLayer: Layer[Any, Guppy & Shark & MegaShark] = guppyLayer >+> sharkLayer >+> megaSharkLayer

            combinedLayer.run.map { env =>
                assert(env.get[Guppy].name == "Tiny Guppy")
                assert(env.get[Shark].belly.name == "Tiny Guppy")
                assert(env.get[MegaShark].belly.belly.name == "Tiny Guppy")
            }
        }
    }

    "memoization" - {
        "shared >>> a ++ shared >>> b" in run {
            case class MagicSource(name: String)
            case class Wizard(name: String, source: MagicSource)
            case class Spell(powerLevel: Int, source: MagicSource)

            var sourceInitializationCount = 0

            val magicSourceLayer = Layers {
                IOs { sourceInitializationCount += 1 }.map { _ => MagicSource("Fireball") }
            }

            val wizardLayer = magicSourceLayer >>> Layers.from(source => Wizard("Gandalf", source))
            val spellLayer  = magicSourceLayer >>> Layers.from(source => Spell(100, source))

            val combinedLayer = wizardLayer ++ spellLayer

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
