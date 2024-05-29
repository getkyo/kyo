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

            val foodLayer: Layer[Any, Food, Any]        = Layers(Food(100))
            val stomachLayer: Layer[Food, Stomach, Any] = Layers.from(Stomach(_))
            val composed: Layer[Any, Stomach, Any]      = foodLayer >>> stomachLayer

            val env = composed.run.pure
            assert(env.get[Stomach].food.calories == 100)
        }

        "a ++ b" in run {
            case class Water(liters: Int)
            case class Fire(heat: Int)

            val waterLayer: Layer[Any, Water, Any] = Layers(Water(2))
            val fireLayer: Layer[Any, Fire, Any]   = Layers(Fire(500))

            val combinedLayer: Layer[Any, Water & Fire, Any] = waterLayer ++ fireLayer

            val env = combinedLayer.run.pure
            assert(env.get[Water].liters == 2)
            assert(env.get[Fire].heat == 500)

        }

        "a >+> b >+> c" in run {
            case class Guppy(name: String)
            case class Shark(belly: Guppy)
            case class MegaShark(belly: Shark)

            val guppyLayer: Layer[Any, Guppy, Any]           = Layers(Guppy("Tiny Guppy"))
            val sharkLayer: Layer[Guppy, Shark, Any]         = Layers.from(g => Shark(g))
            val megaSharkLayer: Layer[Shark, MegaShark, Any] = Layers.from(s => MegaShark(s))

            val combinedLayer: Layer[Any, Guppy & Shark & MegaShark, Any] = guppyLayer >+> sharkLayer >+> megaSharkLayer

            val env = combinedLayer.run.pure
            assert(env.get[Guppy].name == "Tiny Guppy")
            assert(env.get[Shark].belly.name == "Tiny Guppy")
            assert(env.get[MegaShark].belly.belly.name == "Tiny Guppy")
        }
    }

    "memoization" - {
        "shared >>> a ++ shared >>> b" in run {
            case class MagicSource(name: String)
            case class Wizard(name: String, source: MagicSource)
            case class Spell(powerLevel: Int, source: MagicSource)

            var sourceInitializationCount = 0

            val magicSourceLayer: Layer[Any, MagicSource, IOs] = Layers {
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
