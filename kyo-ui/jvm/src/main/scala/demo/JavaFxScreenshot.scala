package demo

import javafx.application.Platform
import javafx.stage.Stage
import kyo.*

object JavaFxScreenshot extends KyoApp:

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
        // Usage: JavaFxScreenshot <session-dir> <ui-name>
        val sessionDir = args.headOption.map(java.nio.file.Paths.get(_).toAbsolutePath.normalize)
            .getOrElse(java.nio.file.Paths.get("../sessions/default").toAbsolutePath.normalize)
        val name = args.drop(1).headOption.getOrElse("demo")
        uis.get(name) match
            case None =>
                Console.printLine(s"Unknown UI: $name. Available: ${uis.keys.toList.sorted.mkString(", ")}")
            case Some(buildUI) =>
                val outDir   = sessionDir.resolve("static")
                val _        = java.nio.file.Files.createDirectories(outDir)
                val demoHtml = java.nio.file.Paths.get("../demo.html").toAbsolutePath.normalize

                for
                    // JavaFX screenshot
                    ui      <- buildUI
                    session <- new JavaFxBackend(title = name, width = 1280, height = 6000).render(ui)
                    _       <- Async.sleep(2.seconds)
                    _       <- takeJfxScreenshot(outDir.resolve(s"javafx-$name.png"))
                    _       <- session.stop
                    _ = Platform.exit()

                    // Web screenshot via Playwright
                    _ <- Browser.run(30.seconds) {
                        for
                            _   <- Browser.goto(s"file://$demoHtml#$name")
                            _   <- Browser.runJavaScript("return new Promise(r => setTimeout(r, 500))")
                            img <- Browser.screenshot(1280, 6000)
                            _   <- img.writeFileBinary(s"$outDir/web-$name.png")
                        yield ()
                    }
                    _ <- Console.printLine(s"Done: $name")
                yield ()
                end for
        end match
    }

    private def takeJfxScreenshot(outPath: java.nio.file.Path): Unit < Async =
        val latch = new java.util.concurrent.CountDownLatch(1)
        Platform.runLater { () =>
            try
                val windows = javafx.stage.Window.getWindows
                if !windows.isEmpty then
                    val stage = windows.get(0).asInstanceOf[Stage]
                    val scene = stage.getScene
                    // Dump tree for debugging
                    val treeDump = kyo.JavaFxBackend.dumpTree(scene.getRoot)
                    val dumpPath = outPath.getParent.resolve(outPath.getFileName.toString.replace(".png", "-tree.txt"))
                    java.nio.file.Files.writeString(dumpPath, treeDump)
                    java.lang.System.err.println(s"Tree dump saved to $dumpPath")
                    // Snapshot the root node at its full preferred height (may exceed screen)
                    val root = scene.getRoot.asInstanceOf[javafx.scene.layout.Region]
                    root.applyCss()
                    // Resize root to its full preferred height so content isn't clipped
                    val prefH = root.prefHeight(scene.getWidth)
                    val fullH = math.max(prefH, scene.getHeight)
                    root.resize(scene.getWidth, fullH)
                    root.layout()
                    val params = new javafx.scene.SnapshotParameters()
                    params.setFill(javafx.scene.paint.Color.TRANSPARENT)
                    val img    = root.snapshot(params, null)
                    val w      = img.getWidth.toInt
                    val h      = img.getHeight.toInt
                    val reader = img.getPixelReader
                    val bImg   = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    for y <- 0 until h; x <- 0 until w do
                        bImg.setRGB(x, y, reader.getArgb(x, y))
                    javax.imageio.ImageIO.write(bImg, "png", outPath.toFile)
                    java.lang.System.err.println(s"JavaFX screenshot saved to $outPath")
                    stage.close()
                else
                    java.lang.System.err.println("No JavaFX windows found")
                end if
            finally
                latch.countDown()
            end try
        }
        latch.await()
    end takeJfxScreenshot

end JavaFxScreenshot
