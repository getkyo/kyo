package demo

import java.nio.file.*
import javafx.application.Platform
import kyo.*

/** Interactive UI session controlled via file-based commands.
  *
  * Start: sbt 'kyo-ui/runMain demo.InteractiveSession'
  *
  * Then write commands to screenshots/interaction/cmd.txt: echo "render demo" > kyo-ui/screenshots/interaction/cmd.txt
  *
  * Results appear in screenshots/interaction/result.txt
  *
  * Commands: render <name> — render a UI on both JFX and Browser jfx-click <selector> — click a JFX node jfx-click-nth <sel> <n> — click
  * Nth matching JFX node jfx-text <selector> — get text from JFX node jfx-fill <sel> <text> — fill text in JFX input jfx-count <selector> —
  * count matching JFX nodes jfx-exists <selector> — check if JFX node exists jfx-screenshot <path> — take JFX screenshot jfx-dump — dump
  * JFX scene tree web-click <selector> — click web element web-text <selector> — get innerText from web web-fill <sel> <text> — fill web
  * input web-count <selector> — count web elements web-exists <selector> — check if web element exists web-screenshot <path> — take web
  * screenshot web-js <code> — run JS in browser screenshot <path> — take both JFX + web screenshots (appends -jfx.png / -web.png) stop —
  * shut down
  */
object InteractiveSession extends KyoApp:

    import JavaFxInteraction as JFx

    private lazy val sessionsDir = Paths.get("../sessions").toAbsolutePath.normalize
    private lazy val sessionName =
        if args.nonEmpty then args.head
        else java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
    private lazy val sessionDir = sessionsDir.resolve(sessionName)
    private lazy val cmdFile    = sessionDir.resolve("cmd.txt")
    private lazy val resFile    = sessionDir.resolve("result.txt")
    private lazy val demoHtml   = Paths.get("../demo.html").toAbsolutePath.normalize

    private val uiBuilders: Map[String, UI < Async] = Map(
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

    @volatile private var currentSession: Option[UISession] = None
    @volatile private var running                           = true

    run {
        val _ = Files.createDirectories(sessionDir)
        // Clear command file
        Files.writeString(cmdFile, "")
        Files.writeString(resFile, "READY\n")
        Files.writeString(sessionDir.resolve("session-name.txt"), sessionName)
        java.lang.System.err.println(s"Session '$sessionName' ready at: $sessionDir")
        java.lang.System.err.println(s"Commands: $cmdFile | Results: $resFile")

        Browser.run(600.seconds) {
            for
                _ <- Browser.goto(s"file://$demoHtml#demo")
                _ <- Browser.runJavaScript("return new Promise(r => setTimeout(r, 500))")
                _ <- pollLoop
            yield ()
        }
    }

    private def pollLoop(using kyo.Frame): Unit < (Browser & Sync & Async & Scope) =
        for
            _ <- Async.sleep(200.millis)
            _ <-
                if !running then Sync.defer(())
                else
                    for
                        cmd <- Sync.defer(Files.readString(cmdFile).trim)
                        _ <-
                            if cmd.isEmpty then Sync.defer(())
                            else
                                for
                                    _ <- Sync.defer(Files.writeString(cmdFile, ""))
                                    result <- Abort.run[Throwable](processCommand(cmd)).map {
                                        case Result.Success(r) => r
                                        case Result.Error(e)   => s"ERROR: ${e.getMessage}"
                                        case Result.Panic(e)   => s"ERROR: ${e.getMessage}"
                                    }
                                    _ <- Sync.defer(Files.writeString(resFile, result + "\n"))
                                    _ <- Sync.defer(java.lang.System.err.println(s"[cmd] $cmd -> ${result.take(100)}"))
                                yield ()
                        _ <- pollLoop
                    yield ()
        yield ()

    private def processCommand(cmd: String)(using kyo.Frame): String < (Browser & Sync & Async & Scope) =
        val parts = cmd.split("\\s+", 3).toList
        parts match
            case "render" :: name :: _ =>
                uiBuilders.get(name) match
                    case None => Sync.defer(s"ERROR: Unknown UI '$name'. Available: ${uiBuilders.keys.mkString(", ")}")
                    case Some(builder) =>
                        for
                            _ <- Sync.defer {
                                import AllowUnsafe.embrace.danger
                                if currentSession.isDefined then
                                    currentSession.foreach(s => Sync.Unsafe.evalOrThrow(s.stop))
                                    currentSession = None
                                    // Close all JFX stages from previous renders
                                    val latch = new java.util.concurrent.CountDownLatch(1)
                                    Platform.runLater { () =>
                                        Platform.setImplicitExit(false)
                                        import scala.jdk.CollectionConverters.*
                                        javafx.stage.Window.getWindows.asScala.collect {
                                            case s: javafx.stage.Stage => s
                                        }.toList.foreach(_.close())
                                        latch.countDown()
                                    }
                                    val _ = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                                end if
                            }
                            ui      <- builder
                            session <- new JavaFxBackend(title = name, width = 560, height = 1400).render(ui)
                            _       <- Async.sleep(1.seconds)
                            _       <- Sync.defer { currentSession = Some(session) }
                            _ <- Browser.runJavaScript(s"window.location.hash = '$name'; setTimeout(function(){ location.reload(); }, 50)")
                            _ <- Async.sleep(2.seconds)
                        yield s"OK: Rendered '$name' on both JFX and Web"

            case "jfx-click" :: selector :: _ =>
                Sync.defer { JFx.click(selector); JFx.waitForUpdates(); "OK: clicked" }

            case "jfx-click-nth" :: selector :: nStr :: _ =>
                Sync.defer { JFx.clickNth(selector, nStr.trim.toInt); JFx.waitForUpdates(); "OK: clicked nth" }

            case "jfx-text" :: rest =>
                val selector = rest.mkString(" ")
                Sync.defer { s"OK: ${JFx.getText(selector)}" }

            case "jfx-fill" :: selector :: rest =>
                val text = rest.mkString(" ")
                Sync.defer { JFx.fillText(selector, text); JFx.waitForUpdates(); "OK: filled" }

            case "jfx-count" :: selector :: _ =>
                Sync.defer { s"OK: ${JFx.count(selector)}" }

            case "jfx-exists" :: selector :: _ =>
                Sync.defer { s"OK: ${JFx.exists(selector)}" }

            case "jfx-screenshot" :: path :: _ =>
                val resolved = sessionDir.resolve(path).toString
                Sync.defer { JFx.screenshot(Paths.get(resolved)); s"OK: saved $resolved" }

            case "jfx-dump" :: _ =>
                Sync.defer { s"OK:\n${JFx.dumpTree()}" }

            case "web-click" :: rest =>
                val selector = rest.mkString(" ")
                for _ <- Browser.click(selector)
                yield "OK: clicked"

            case "web-text" :: rest =>
                val selector = rest.mkString(" ")
                for t <- Browser.innerText(selector)
                yield s"OK: $t"

            case "web-fill" :: selector :: rest =>
                val text = rest.mkString(" ")
                for _ <- Browser.fill(selector, text)
                yield "OK: filled"

            case "web-count" :: rest =>
                val selector = rest.mkString(" ")
                for c <- Browser.count(selector)
                yield s"OK: $c"

            case "web-exists" :: rest =>
                val selector = rest.mkString(" ")
                for e <- Browser.exists(selector)
                yield s"OK: $e"

            case "web-screenshot" :: path :: _ =>
                val resolved = sessionDir.resolve(path).toString
                for
                    img <- Browser.screenshot(560, 1400)
                    _   <- img.writeFileBinary(resolved)
                yield s"OK: saved $resolved"
                end for

            case "web-js" :: rest =>
                val code = rest.mkString(" ")
                for r <- Browser.runJavaScript(code)
                yield s"OK: $r"

            case "screenshot" :: basePath :: _ =>
                val resolved = sessionDir.resolve(basePath)
                val jfxPath  = resolved.toString + "-jfx.png"
                val webPath  = resolved.toString + "-web.png"
                for
                    _   <- Sync.defer { JFx.screenshot(Paths.get(jfxPath)) }
                    img <- Browser.screenshot(560, 1400)
                    _   <- img.writeFileBinary(webPath)
                yield s"OK: saved $jfxPath and $webPath"
                end for

            case "stop" :: _ =>
                Sync.defer {
                    running = false
                    currentSession.foreach(_ => ())
                    Platform.exit()
                    "OK: stopping"
                }

            case _ =>
                Sync.defer(s"ERROR: Unknown command '$cmd'")
        end match
    end processCommand

end InteractiveSession
