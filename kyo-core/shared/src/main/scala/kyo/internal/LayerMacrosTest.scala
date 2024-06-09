package kyo.internal

import kyo.*

object LayerMacrosTest extends KyoApp:
    trait MagicSource
    case object Fireball extends MagicSource
    case object Water    extends MagicSource

    case class Wizard(name: String, source: MagicSource)
    case class Spell(powerLevel: Int, source: MagicSource)

    val fireBallLayer = Layers(Fireball)
    val waterLayer    = Layers(Water)

    val gandalfLayer: Layer[Wizard, Envs[MagicSource] & IOs] =
        Layers.from((source: MagicSource) => IOs(Wizard("Gandalf", source))) and Layers(12)
    val harryLayer = Layers.from((source: MagicSource) => IOs(Wizard("Harry", source)))
    val spellLayer = Layers.from(source => Spell(100, source))

    val autoWired = LayerMacros.mergeLayers[Wizard & Spell](
        // gandalfLayer,
        harryLayer,
        spellLayer,
        // fireBallLayer // this is bad
        waterLayer
    )

    run:
        autoWired.run.map(_.show)
end LayerMacrosTest
