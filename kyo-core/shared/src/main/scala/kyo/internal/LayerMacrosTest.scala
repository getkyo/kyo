package kyo.internal

import kyo.*

object LayerMacrosTest extends KyoApp:
    trait MagicSource
    case object Fire           extends MagicSource
    case object Water          extends MagicSource
    case class Heart(bpm: Int) extends MagicSource

    case class Wizard(name: String, source: MagicSource)
    case class Spell(powerLevel: Int, source: MagicSource)
    case class Killdozer(heart: Heart, model: String, armorLevel: Int)
    case class Rampage(damage: Int, killdozer: Killdozer)

    val fireBallLayer = Layers(Fire)
    val waterLayer    = Layers(Water)
    val heartLayer    = Layers(Heart(120))

    val gandalfLayer: Layer[Wizard, Envs[MagicSource] & IOs] =
        Layers.from((source: MagicSource) => IOs(Wizard("Gandalf", source))) and Layers(12)
    val harryLayer = Layers.from((source: MagicSource) => IOs(Wizard("Harry", source)))
    val spellLayer = Layers.from(source => Spell(100, source))

    val killdozerLayer = Layers.from((heart: Heart) => Killdozer(heart, "Komatsu D355A", 5))
    val rampageLayer   = Layers.from((killdozer: Killdozer) => Rampage(1000000, killdozer))

    val heartFromHeartLayer: Layer[Heart, Envs[Heart]] = Layers.from((heart: Heart) => heart.copy(bpm = heart.bpm * 2))

    val autoWired = LayerMacros.make[Wizard & Spell & Killdozer & Rampage](
        harryLayer,
        spellLayer,
        killdozerLayer,
        rampageLayer
        // waterLayer,
        // heartLayer
        // TODO: Handle
        // heartFromHeartLayer
    )

    run:
        autoWired.run.map(_.show)
end LayerMacrosTest
