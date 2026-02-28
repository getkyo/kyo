package demo

import kyo.*
import scala.scalajs.js

object DemoApp extends KyoApp:

    private val uis: Map[String, UI < Async] = Map(
        "demo"        -> DemoUI.build,
        "interactive" -> InteractiveUI.build,
        "form"        -> FormUI.build,
        "typography"  -> TypographyUI.build,
        "layout"      -> LayoutUI.build,
        "reactive"    -> ReactiveUI.build,
        "dashboard"   -> DashboardUI.build,
        "semantic"    -> SemanticElementsUI.build,
        "nested"      -> NestedReactiveUI.build,
        "pseudo"      -> MultiPseudoStateUI.build,
        "collections" -> CollectionOpsUI.build,
        "transforms"  -> TransformsUI.build,
        "sizing"      -> SizingUnitsUI.build,
        "keyboard"    -> KeyboardNavUI.build,
        "colors"      -> ColorSystemUI.build,
        "dynamic"     -> DynamicStyleUI.build,
        "tables"      -> TableAdvancedUI.build,
        "auto"        -> AutoTransitionUI.build,
        "animated"    -> AnimatedDashboardUI.build
    )

    run {
        val hash = js.Dynamic.global.window.location.hash.asInstanceOf[String].stripPrefix("#")
        val name = if hash.nonEmpty then hash else "demo"
        uis.get(name) match
            case Some(buildUI) =>
                for
                    ui      <- buildUI
                    session <- new DomBackend().render(ui)
                    _       <- session.await
                yield ()
            case None =>
                for
                    ui      <- DemoUI.build
                    session <- new DomBackend().render(ui)
                    _       <- session.await
                yield ()
        end match
    }
end DemoApp
