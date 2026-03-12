package demo

import java.nio.file.*
import javafx.application.Platform
import kyo.*

/** Interactive UI session controlled via file-based commands.
  *
  * Start: sbt 'kyo-ui/runMain demo.InteractiveSession'
  *
  * Then write commands to sessions/<name>/cmd.txt
  *
  * Results appear in sessions/<name>/result.txt
  *
  * Commands:
  *   - render <name> — render a UI on both JFX and Browser
  *   - jfx-click <selector> — click a JFX node (CSS selector, e.g. .button)
  *   - jfx-click-nth <sel> <n> — click Nth matching JFX node
  *   - jfx-text <selector> — get text from JFX node
  *   - jfx-fill <sel> <text> — fill text in JFX input (supports quoted selectors: "sel with spaces")
  *   - jfx-count <selector> — count matching JFX nodes
  *   - jfx-exists <selector> — check if JFX node exists
  *   - jfx-screenshot <path> — take JFX screenshot
  *   - jfx-dump — dump JFX scene tree
  *   - web-click <selector> — click web element (CSS selector, e.g. button)
  *   - web-text <selector> — get innerText from web
  *   - web-fill <sel> <text> — fill web input (supports quoted selectors)
  *   - web-count <selector> — count web elements
  *   - web-exists <selector> — check if web element exists (non-blocking)
  *   - web-screenshot <path> — take web screenshot
  *   - web-js <code> — run JS in browser (auto-prepends 'return' if needed)
  *   - screenshot <path> — take both JFX + web screenshots
  *   - stop — shut down
  *
  * NOTE: JFX uses CSS class selectors (.button, .input) while web uses HTML tag selectors (button, input). For compound selectors with
  * spaces, use quotes: web-fill ".todo-input input" some text
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
        "animated"    -> AnimatedDashboardUI.build,
        "signals"     -> SignalCombinatorUI.build,
        "rapid"       -> RapidMutationUI.build,
        "deepnest"    -> DeepNestingUI.build,
        "swap"        -> SignalSwapUI.build,
        "attrs"       -> GenericAttrUI.build,
        "rechref"     -> ReactiveHrefUI.build,
        "formreset"   -> FormResetUI.build,
        "formval"     -> FormValidationUI.build,
        "navigation"  -> NavigationUI.build,
        "datagrid"    -> DataGridUI.build,
        "a11y"        -> AccessibilityUI.build,
        "cards"       -> ResponsiveCardUI.build
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
                                    _      <- Sync.defer(Files.writeString(cmdFile, ""))
                                    result <- executeWithTimeout(cmd)
                                    _      <- Sync.defer(Files.writeString(resFile, result + "\n"))
                                    _      <- Sync.defer(java.lang.System.err.println(s"[cmd] $cmd -> ${result.take(100)}"))
                                yield ()
                        _ <- pollLoop
                    yield ()
        yield ()

    /** Execute a command with error protection so failures don't kill the session. */
    private def executeWithTimeout(cmd: String)(using kyo.Frame): String < (Browser & Sync & Async & Scope) =
        Abort.run[Throwable](processCommand(cmd)).map {
            case Result.Success(r) => r
            case Result.Error(e)   => s"ERROR: ${e.getMessage}"
            case Result.Panic(e)   => s"ERROR: ${e.getMessage}"
        }

    /** Parse a selector that may be quoted (for compound selectors with spaces). Examples:
      *   - parseSelectorAndRest(".button Submit") => (".button", "Submit")
      *   - parseSelectorAndRest("\"form input\" text") => ("form input", "text")
      */
    private def parseSelectorAndRest(s: String): (String, String) =
        if s.startsWith("\"") then
            val endQuote = s.indexOf('"', 1)
            if endQuote > 0 then
                (s.substring(1, endQuote), s.substring(endQuote + 1).trim)
            else
                // No closing quote, treat entire string as selector
                (s.substring(1), "")
            end if
        else
            val spaceIdx = s.indexOf(' ')
            if spaceIdx > 0 then
                (s.substring(0, spaceIdx), s.substring(spaceIdx + 1))
            else
                (s, "")
            end if

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

            case "jfx-fill-nth" :: rest =>
                val afterCmd          = cmd.stripPrefix("jfx-fill-nth").trim
                val (selector, rest2) = parseSelectorAndRest(afterCmd)
                val spaceIdx          = rest2.indexOf(' ')
                if spaceIdx < 0 then Sync.defer("ERROR: usage: jfx-fill-nth <selector> <index> <text>")
                else
                    val index = rest2.substring(0, spaceIdx).trim.toInt
                    val text  = rest2.substring(spaceIdx + 1)
                    Sync.defer { JFx.fillTextNth(selector, index, text); JFx.waitForUpdates(); "OK: filled nth" }
                end if

            case "jfx-fill" :: rest =>
                val afterCmd         = cmd.stripPrefix("jfx-fill").trim
                val (selector, text) = parseSelectorAndRest(afterCmd)
                Sync.defer { JFx.fillText(selector, text); JFx.waitForUpdates(); "OK: filled" }

            case "jfx-check" :: selector :: value :: _ =>
                Sync.defer { JFx.setChecked(selector, value.trim.toBoolean); JFx.waitForUpdates(); "OK: checked" }

            case "jfx-select" :: rest =>
                val afterCmd          = cmd.stripPrefix("jfx-select").trim
                val (selector, value) = parseSelectorAndRest(afterCmd)
                Sync.defer { JFx.selectOption(selector, value); JFx.waitForUpdates(); "OK: selected" }

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
                val escaped  = selector.replace("'", "\\'")
                for
                    r <- Browser.runJavaScript(
                        s"var el = document.querySelector('$escaped'); if (!el) return 'ERROR: no elements match'; el.click(); return 'OK: clicked'"
                    )
                yield r

            case "web-text" :: rest =>
                val selector = rest.mkString(" ")
                for
                    c <- Browser.count(selector)
                    r <-
                        if c == 0 then Sync.defer(s"ERROR: no elements match '$selector'")
                        else
                            for t <- Browser.innerText(selector)
                            yield s"OK: $t"
                yield r
                end for

            case "web-fill" :: rest =>
                val afterCmd         = cmd.stripPrefix("web-fill").trim
                val (selector, text) = parseSelectorAndRest(afterCmd)
                val escaped          = selector.replace("'", "\\'")
                val escapedText      = text.replace("\\", "\\\\").replace("'", "\\'")
                for
                    r <- Browser.runJavaScript(
                        s"var el = document.querySelector('$escaped'); if (!el) return 'ERROR: no elements match'; " +
                            s"var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype; " +
                            s"var setter = Object.getOwnPropertyDescriptor(proto, 'value'); " +
                            s"if (setter && setter.set) { setter.set.call(el, '$escapedText'); } else { el.value = '$escapedText'; } " +
                            s"el.dispatchEvent(new Event('input', {bubbles: true})); return 'OK: filled'"
                    )
                yield r

            case "web-count" :: rest =>
                val selector = rest.mkString(" ")
                for c <- Browser.count(selector)
                yield s"OK: $c"

            // Bug #2 fix: use count() instead of exists() to avoid waitForSelector hang
            case "web-exists" :: rest =>
                val selector = rest.mkString(" ")
                for c <- Browser.count(selector)
                yield s"OK: ${c > 0}"

            case "web-screenshot" :: path :: _ =>
                val resolved = sessionDir.resolve(path).toString
                for
                    img <- Browser.screenshot(560, 1400)
                    _   <- img.writeFileBinary(resolved)
                yield s"OK: saved $resolved"
                end for

            // Bug #6 fix: auto-prepend 'return' if missing
            case "web-js" :: rest =>
                val rawCode = rest.mkString(" ")
                val code =
                    if rawCode.trim.startsWith("return ") || rawCode.trim.startsWith("try ") then rawCode
                    else s"return $rawCode"
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
