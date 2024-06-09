package kyo.internal

import kyo.*

object LayerMacrosTest extends KyoApp:
    case class MagicSource(name: String)
    case class Wizard(name: String, source: MagicSource)
    case class Spell(powerLevel: Int, source: MagicSource)

    val magicSourceLayer = Layers {
        MagicSource("Fireball")
    }
    val wizardLayer = Layers.from(source => Wizard("Gandalf", source))
    val spellLayer  = Layers.from(source => Spell(100, source))
    val autoWired   = LayerMacros.layersToNodesTest[Wizard & Spell](wizardLayer, spellLayer, magicSourceLayer)

    run:
        autoWired.run.map(_.show)
end LayerMacrosTest
