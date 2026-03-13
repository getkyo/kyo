package kyo

// Commented out: TuiSignalCollector was deleted with old tui2 code.
// This backend will be replaced by Tui2Backend in Phase 10.

/*
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.web.WebView
import javafx.stage.Stage
import kyo.internal.CssStyleRenderer
import scala.annotation.tailrec

object JavaFxBackend extends UIBackend:

    private var platformStarted = false

    private def ensurePlatform(): Unit =
        if !platformStarted then
            platformStarted = true
            Platform.startup(() => ())
            Platform.setImplicitExit(false)
    end ensurePlatform

    def render(ui: UI, theme: Theme = Theme.Default)(using Frame): UISession < (Async & Scope) =
        for
            rendered <- Signal.initRef[UI](UI.empty)
            webView  <- Sync.defer(createWebView(ui))
            _        <- rendered.set(ui)
            fiber    <- Fiber.init(renderLoop(ui, webView, rendered))
        yield UISession(fiber, rendered)

    private def createWebView(ui: UI)(using Frame): WebView =
        ensurePlatform()
        // unsafe: null used for cross-thread handoff with Platform.runLater
        var result: WebView = null // unsafe: null for JavaFX thread handoff
        val latch           = new java.util.concurrent.CountDownLatch(1)
        Platform.runLater { () =>
            val stage   = new Stage()
            val webView = new WebView()
            val scene   = new Scene(webView, 800, 600)
            stage.setScene(scene)
            stage.setTitle("kyo-ui")
            stage.show()
            webView.getEngine.loadContent(toHtml(ui))
            result = webView
            latch.countDown()
        }
        latch.await()
        result
    end createWebView

    private def renderLoop(
        ui: UI,
        webView: WebView,
        rendered: SignalRef[UI]
    )(using Frame): Nothing < Async =
        val signals = new kyo.internal.TuiSignalCollector(256)
        import AllowUnsafe.embrace.danger
        collectSignals(ui, signals)
        Loop.forever {
            val sigs = signals.toSpan
            for
                _ <-
                    if sigs.isEmpty then Async.sleep(100.millis)
                    else Async.race(Array.tabulate(sigs.size)(i => sigs(i).next.unit))
                _ <- Async.sleep(16.millis)
                _ <- Sync.defer {
                    signals.reset()
                    collectSignals(ui, signals)
                    val html = toHtml(ui)
                    Platform.runLater { () =>
                        webView.getEngine.loadContent(html)
                    }
                }
                _ <- rendered.set(ui)
            yield ()
            end for
        }
    end renderLoop

    /** Collect all signals referenced by the UI tree for change detection. */
    private def collectSignals(ui: UI, signals: kyo.internal.TuiSignalCollector)(using Frame, AllowUnsafe): Unit =
        ui match
            case UI.Text(_) => ()
            case elem: UI.Element =>
                elem.attrs.uiStyle match
                    case sig: Signal[?] => signals.add(sig)
                    case _              => ()
                elem.children.foreach(child => collectSignals(child, signals))
            case UI.Reactive(signal) =>
                signals.add(signal)
                val resolved = Sync.Unsafe.evalOrThrow(signal.current)
                collectSignals(resolved, signals)
            // unsafe: asInstanceOf for erased type parameter
            case fe: UI.Foreach[?] @unchecked =>
                signals.add(fe.signal)
            case UI.Fragment(children) =>
                children.foreach(child => collectSignals(child, signals))
    end collectSignals

    /** Convert UI AST to HTML string using CssStyleRenderer for styles. */
    private def toHtml(ui: UI)(using Frame): String =
        import AllowUnsafe.embrace.danger
        val sb = new StringBuilder
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>")
        sb.append("* { box-sizing: border-box; margin: 0; padding: 0; } ")
        sb.append("body { font-family: system-ui, sans-serif; } ")
        sb.append("</style></head><body>")
        renderNode(ui, sb)
        sb.append("</body></html>")
        sb.toString
    end toHtml

    private def renderNode(ui: UI, sb: StringBuilder)(using Frame, AllowUnsafe): Unit =
        ui match
            case UI.Text(value) =>
                appendEscaped(sb, value)

            case elem: UI.Element =>
                val tag = elemToHtml(elem)
                sb.append('<').append(tag): Unit
                val style: Style = elem.attrs.uiStyle match
                    case s: Style       => s
                    case sig: Signal[?] =>
                        // unsafe: asInstanceOf for union type resolution
                        Sync.Unsafe.evalOrThrow(sig.asInstanceOf[Signal[Style]].current)
                val css = CssStyleRenderer.render(style)
                if css.nonEmpty then
                    sb.append(" style=\"").append(css).append('"'): Unit
                end if
                elem match
                    case a: UI.Anchor if a.href.isDefined =>
                        a.href.get match
                            case s: String => sb.append(" href=\"").append(s).append('"'): Unit
                            case _         => ()
                    case i: UI.Img =>
                        if i.src.isDefined then
                            i.src.get match
                                case s: String => sb.append(" src=\"").append(s).append('"'): Unit
                                case _         => ()
                        end if
                        if i.alt.isDefined then
                            sb.append(" alt=\"").append(i.alt.get).append('"'): Unit
                    case _ => ()
                end match
                sb.append('>'): Unit
                elem.children.foreach(child => renderNode(child, sb))
                // Input value as text child
                elem match
                    case ti: UI.TextInput =>
                        ti.value match
                            case Maybe.Present(s: String)        => appendEscaped(sb, s)
                            case Maybe.Present(sr: SignalRef[?]) =>
                                // unsafe: asInstanceOf for union type resolution
                                val v = Sync.Unsafe.evalOrThrow(sr.asInstanceOf[Signal[String]].current)
                                appendEscaped(sb, v)
                            case _ => ()
                    case _ => ()
                end match
                sb.append("</").append(tag).append('>'): Unit

            case UI.Reactive(signal) =>
                val resolved = Sync.Unsafe.evalOrThrow(signal.current)
                renderNode(resolved, sb)

            // unsafe: asInstanceOf for erased type parameter
            case fe: UI.Foreach[?] @unchecked =>
                val items  = Sync.Unsafe.evalOrThrow(fe.signal.current)
                val render = fe.render.asInstanceOf[(Int, Any) => UI]
                val size   = items.size
                @tailrec def loop(i: Int): Unit =
                    if i < size then
                        renderNode(render(i, items(i)), sb)
                        loop(i + 1)
                loop(0)

            case UI.Fragment(children) =>
                children.foreach(child => renderNode(child, sb))
    end renderNode

    /** Map element type to HTML tag name. */
    private def elemToHtml(elem: UI.Element): String = elem match
        case _: UI.Div         => "div"
        case _: UI.Span        => "span"
        case _: UI.P           => "p"
        case _: UI.Section     => "section"
        case _: UI.Main        => "main"
        case _: UI.Header      => "header"
        case _: UI.Footer      => "footer"
        case _: UI.Pre         => "pre"
        case _: UI.Code        => "code"
        case _: UI.Ul          => "ul"
        case _: UI.Ol          => "ol"
        case _: UI.Table       => "table"
        case _: UI.H1          => "h1"
        case _: UI.H2          => "h2"
        case _: UI.H3          => "h3"
        case _: UI.H4          => "h4"
        case _: UI.H5          => "h5"
        case _: UI.H6          => "h6"
        case _: UI.Nav         => "nav"
        case _: UI.Li          => "li"
        case _: UI.Tr          => "tr"
        case _: UI.Form        => "form"
        case _: UI.Textarea    => "textarea"
        case _: UI.Select      => "select"
        case _: UI.Hr          => "hr"
        case _: UI.Br          => "br"
        case _: UI.Td          => "td"
        case _: UI.Th          => "th"
        case _: UI.Label       => "label"
        case _: UI.Opt         => "option"
        case _: UI.Button      => "button"
        case _: UI.Checkbox    => "input"
        case _: UI.Radio       => "input"
        case _: UI.Input       => "input"
        case _: UI.Password    => "input"
        case _: UI.Email       => "input"
        case _: UI.Tel         => "input"
        case _: UI.UrlInput    => "input"
        case _: UI.Search      => "input"
        case _: UI.NumberInput => "input"
        case _: UI.DateInput   => "input"
        case _: UI.TimeInput   => "input"
        case _: UI.ColorInput  => "input"
        case _: UI.RangeInput  => "input"
        case _: UI.FileInput   => "input"
        case _: UI.HiddenInput => "input"
        case _: UI.Anchor      => "a"
        case _: UI.Img         => "img"

    private def appendEscaped(sb: StringBuilder, text: String): Unit =
        // unsafe: while for char iteration
        var i = 0
        while i < text.length do
            text.charAt(i) match
                case '<'  => sb.append("&lt;")
                case '>'  => sb.append("&gt;")
                case '&'  => sb.append("&amp;")
                case '"'  => sb.append("&quot;")
                case '\'' => sb.append("&#39;")
                case ch   => sb.append(ch)
            end match
            i += 1
        end while
    end appendEscaped

end JavaFxBackend
 */
