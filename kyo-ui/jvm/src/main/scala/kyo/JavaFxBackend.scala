package kyo

import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button as JButton
import javafx.scene.control.ComboBox
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label as JLabel
import javafx.scene.control.ScrollPane
import javafx.scene.control.Separator
import javafx.scene.control.TextArea as JTextArea
import javafx.scene.control.TextField
import javafx.scene.image.ImageView
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.shape.Rectangle
import javafx.stage.Stage
import kyo.Style.Prop.*
import kyo.UI.AST.*
import scala.jdk.CollectionConverters.*

private enum HostOS derives CanEqual:
    case MacOS, Windows, Linux, Other

private object HostOS:
    val current: HostOS =
        val name = java.lang.System.getProperty("os.name", "").toLowerCase
        if name.contains("mac") then HostOS.MacOS
        else if name.contains("win") then HostOS.Windows
        else if name.contains("linux") || name.contains("nux") then HostOS.Linux
        else HostOS.Other
        end if
    end current

    /** Font family to use when the default system font doesn't support italic in JavaFX. */
    def italicFallbackFamily: String = current match
        case HostOS.MacOS   => "Helvetica"
        case HostOS.Windows => "Arial"
        case _              => "SansSerif"
end HostOS

class JavaFxBackend(
    title: String = "Kyo",
    width: Double = 800,
    height: Double = 600
) extends UIBackend:

    def render(ui: UI)(using Frame): UISession < (Async & Scope) =
        for
            rendered <- Signal.initRef[UI](UI.empty)
            updates  <- Channel.init[() => Unit](1024)
            fiber <- Fiber.init {
                for
                    _    <- startToolkit(updates)
                    root <- build(ui, updates, rendered)
                    _    <- showWindow(root, updates)
                    _    <- rendered.set(ui)
                    _    <- Async.never
                yield ()
            }
        yield UISession(fiber, rendered)

    private def startToolkit(updates: Channel[() => Unit])(using Frame): Unit < Async =
        val promise = Fiber.Promise.init[Nothing, Unit]
        val started = new java.util.concurrent.CountDownLatch(1)
        val thread = new Thread(() =>
            try
                Platform.startup(() =>
                    started.countDown()
                    drainLoop(updates)
                )
            catch
                case _: IllegalStateException =>
                    // Toolkit already initialized — start drain loop on FX thread
                    Platform.runLater(() => drainLoop(updates))
                    started.countDown()
        )
        thread.setDaemon(true)
        thread.start()
        started.await()
    end startToolkit

    private def drainLoop(updates: Channel[() => Unit])(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        val timer = new javafx.animation.AnimationTimer:
            def handle(now: Long): Unit =
                var continue = true
                while continue do
                    Sync.Unsafe.evalOrThrow(updates.poll) match
                        case Absent      => continue = false
                        case Present(fn) => fn()
                end while
            end handle
        timer.start()
    end drainLoop

    private def showWindow(root: Node, updates: Channel[() => Unit])(using Frame): Unit < Async =
        val done = Fiber.Promise.init[Nothing, Unit]
        runOnFx(updates) {
            val stage = new Stage()
            stage.setTitle(title)
            val scroll = new ScrollPane(root)
            scroll.setFitToWidth(true)
            scroll.setStyle("-fx-background-color: transparent;")
            val scene = new Scene(scroll, width, height)
            scene.setFill(javafx.scene.paint.Color.web("#f4f4f5"))
            stage.setScene(scene)
            stage.show()
        }
    end showWindow

    private def runOnFx(updates: Channel[() => Unit])(fn: => Unit)(using Frame): Unit < Async =
        Abort.recover[Closed](_ => Abort.panic(new IllegalStateException("JavaFxBackend update channel was closed")))(
            updates.put(() => fn)
        )

    private def build(ui: UI, updates: Channel[() => Unit], rendered: SignalRef[UI], parentIsRow: Boolean = false)(using
        Frame
    ): Node < (Async & Scope) =
        // Dynamic containers (foreach, reactive, fragment) should match the parent's direction.
        // In the web, these render items directly into the parent with no wrapper.
        // In JavaFX we need a container for dynamic updates, so we match the parent's layout.
        def makeContainer(): Pane = if parentIsRow then new HBox() else new VBox()

        ui match
            case Text(value) =>
                new JLabel(value): Node

            case rt @ ReactiveText(signal) =>
                val label = new JLabel("")
                subscribe(signal) { v =>
                    runOnFx(updates)(label.setText(v))
                        .andThen(rendered.set(rt).unit)
                }.map(_ => label: Node)

            case ReactiveNode(signal) =>
                val container = makeContainer()
                subscribeUI(container, signal, updates, rendered).map(_ => container: Node)

            case fi: ForeachIndexed[?] =>
                val container = makeContainer()
                subscribeForeach(container, fi, updates, rendered).map(_ => container: Node)

            case fk: ForeachKeyed[?] =>
                val container = makeContainer()
                subscribeKeyed(container, fk, updates, rendered).map(_ => container: Node)

            case Fragment(children) =>
                buildChildren(children, updates, rendered, parentIsRow).map { nodes =>
                    val box = makeContainer()
                    box.getChildren.addAll(nodes.toSeq.asJava)
                    box: Node
                }

            case elem: Element =>
                buildElement(elem, updates, rendered)
        end match
    end build

    private def buildElement(elem: Element, updates: Channel[() => Unit], rendered: SignalRef[UI])(using
        Frame
    ): Node < (Async & Scope) =
        val node = createNode(elem)
        for
            _ <- applyCommon(node, elem, elem.common, updates, rendered)
            _ <- applySpecific(node, elem, updates, rendered)
            _ <- addChildren(node, elem, updates, rendered)
        yield
            val styled = applyStyleProps(node, elem.common.uiStyle)
            applyLayoutDefaults(styled)
            styled
        end for
    end buildElement

    private def addChildren(node: Node, elem: Element, updates: Channel[() => Unit], rendered: SignalRef[UI])(using
        Frame
    ): Unit < (Async & Scope) =
        if elem.children.isEmpty then ()
        else
            node match
                case label: JLabel =>
                    val text = extractText(elem.children)
                    if text.nonEmpty then label.setText(text)
                    subscribeReactiveText(label, elem.children, updates, rendered, _.setText(_))
                case btn: JButton =>
                    val text = extractText(elem.children)
                    if text.nonEmpty then btn.setText(text)
                    subscribeReactiveText(btn, elem.children, updates, rendered, (b, t) => b.asInstanceOf[JButton].setText(t))
                case link: Hyperlink =>
                    val text = extractText(elem.children)
                    if text.nonEmpty then link.setText(text)
                    subscribeReactiveText(link, elem.children, updates, rendered, (l, t) => l.asInstanceOf[Hyperlink].setText(t))
                case grid: GridPane =>
                    // HTML tables distribute columns to fill available width by default.
                    // Always apply column grow to match this behavior.
                    applyTableColumnGrow(grid, elem.children)
                    grid.setMaxWidth(Double.MaxValue)
                    javafx.scene.layout.HBox.setHgrow(grid, Priority.ALWAYS)
                    buildTableGrid(grid, elem.children, updates, rendered)
                case pane: Pane =>
                    // Add list markers for ol/ul — matches HTML default behavior
                    // where ol items get "1. 2. 3." and ul items get "•" bullets.
                    val isOl = elem.isInstanceOf[Ol]
                    val isUl = elem.isInstanceOf[Ul]
                    if isOl || isUl then
                        pane.setPadding(new Insets(0, 0, 0, 20)) // HTML default list indentation
                    end if

                    val isRow = elem.common.uiStyle.props.exists {
                        case Style.Prop.FlexDirectionProp(Style.FlexDirection.row) => true
                        case _                                                     => false
                    }
                    if isRow then
                        buildChildren(elem.children, updates, rendered, parentIsRow = true).map { nodes =>
                            pane.getChildren.addAll(nodes.toSeq.asJava): Unit
                        }
                    else
                        buildChildren(elem.children, updates, rendered).map { nodes =>
                            if isOl || isUl then
                                // Prepend markers to Li nodes
                                var idx = 0
                                nodes.foreach { child =>
                                    child match
                                        case hbox: HBox if hbox.getStyleClass.contains("li") =>
                                            idx += 1
                                            val marker = new JLabel(if isOl then s"$idx." else "\u2022")
                                            marker.setMinWidth(20)
                                            hbox.getChildren.add(0, marker)
                                        case _ => ()
                                    end match
                                }
                            end if
                            pane.getChildren.addAll(nodes.toSeq.asJava): Unit
                        }
                    end if
                case _ => ()
    end addChildren

    private def createNode(elem: Element): Node =
        val (node, cls) = elem match
            case _: Div     => (new VBox(), "div")
            case _: P       => (new VBox(), "p")
            case _: Span    => (new HBox(), "span")
            case _: Ul      => (new VBox(), "ul")
            case _: Ol      => (new VBox(), "ol")
            case _: Li      => (new HBox(), "li")
            case _: Nav     => (new HBox(), "nav")
            case _: Header  => (new VBox(), "header")
            case _: Footer  => (new VBox(), "footer")
            case _: Section => (new VBox(), "section")
            case _: Main    => (new VBox(), "main")
            case _: Pre     => (new VBox(), "pre")
            case _: Code    => (new VBox(), "code")
            case _: Table   => (new GridPane(), "table")
            case _: Tr      => (new HBox(), "tr")
            case _: Td      => (new VBox(), "td")
            case _: Th      => val v = new VBox(); v.setStyle("-fx-font-weight: bold;"); (v, "th")
            case _: H1      => val l = new JLabel(); l.setStyle("-fx-font-size: 32; -fx-font-weight: bold;"); (l, "h1")
            case _: H2      => val l = new JLabel(); l.setStyle("-fx-font-size: 24; -fx-font-weight: bold;"); (l, "h2")
            case _: H3      => val l = new JLabel(); l.setStyle("-fx-font-size: 18; -fx-font-weight: bold;"); (l, "h3")
            case _: H4      => val l = new JLabel(); l.setStyle("-fx-font-size: 16; -fx-font-weight: bold;"); (l, "h4")
            case _: H5      => val l = new JLabel(); l.setStyle("-fx-font-size: 13; -fx-font-weight: bold;"); (l, "h5")
            case _: H6      => val l = new JLabel(); l.setStyle("-fx-font-size: 11; -fx-font-weight: bold;"); (l, "h6")
            case _: Hr      => (new Separator(), "hr")
            case _: Br      => (new JLabel(""), "br")
            case _: Button  => (new JButton(), "button")
            case _: Anchor =>
                val l = new Hyperlink(); l.setStyle("-fx-text-fill: inherit; -fx-underline: false; -fx-border-color: transparent;");
                (l, "a")
            case _: Form   => (new VBox(), "form")
            case _: Select => (new ComboBox[String](), "select")
            case _: Option => (new JLabel(), "option")
            case i: Input if i.typ.contains("checkbox") =>
                (new javafx.scene.control.CheckBox(), "input")
            case _: Input => (new TextField(), "input")
            case _: Textarea =>
                val ta = new JTextArea()
                ta.setPrefRowCount(2) // Match HTML default textarea height
                (ta, "textarea")
            case _: Label => (new JLabel(), "label")
            case i: Img =>
                val iv = if i.src.startsWith("data:") then
                    // Decode data URI: data:[mediatype];base64,<data>
                    val commaIdx = i.src.indexOf(',')
                    if commaIdx >= 0 then
                        val b64   = i.src.substring(commaIdx + 1)
                        val bytes = java.util.Base64.getDecoder.decode(b64)
                        new ImageView(new javafx.scene.image.Image(new java.io.ByteArrayInputStream(bytes)))
                    else new ImageView()
                    end if
                else
                    new ImageView(i.src)
                // Wrap in StackPane so Region-based style props (width, height, bg, rounded) work
                val wrapper = new StackPane(iv)
                // Bind ImageView size to wrapper size
                iv.fitWidthProperty().bind(wrapper.widthProperty())
                iv.fitHeightProperty().bind(wrapper.heightProperty())
                iv.setPreserveRatio(true)
                (wrapper, "img")
        node.getStyleClass.add(cls)
        node
    end createNode

    private def applyCommon(
        node: Node,
        ui: UI,
        c: CommonAttrs,
        updates: Channel[() => Unit],
        rendered: SignalRef[UI]
    )(using Frame): Unit < (Async & Scope) =
        for
            _ <- applyClasses(node, c.classes, updates, rendered, ui)
            _ <- c.dynamicClassName.fold(noop)(sig =>
                subscribe(sig) { v =>
                    runOnFx(updates) {
                        node.getStyleClass.clear()
                        v.split("\\s+").foreach(cls => node.getStyleClass.add(cls))
                    }.andThen(rendered.set(ui).unit)
                }
            )
            _ <- c.identifier.fold(noop) { v =>
                node.setId(v); noop
            }
            _ <-
                val uiCss =
                    if c.uiStyle.isEmpty then ""
                    else internal.FxCssStyleRenderer.render(c.uiStyle)
                c.style.fold {
                    if uiCss.nonEmpty then node.setStyle(uiCss)
                    noop
                } {
                    case s: String =>
                        val fxCss = webCssToFxCss(s)
                        node.setStyle(if uiCss.nonEmpty then s"$fxCss $uiCss" else fxCss); noop
                    case sig: Signal[?] =>
                        subscribe(sig.asInstanceOf[Signal[String]]) { v =>
                            val fxCss = webCssToFxCss(v)
                            runOnFx(updates)(node.setStyle(if uiCss.nonEmpty then s"$fxCss $uiCss" else fxCss))
                                .andThen(rendered.set(ui).unit)
                        }
                }
            _ <- c.hidden.fold(noop) {
                case b: Boolean =>
                    node.setVisible(!b); node.setManaged(!b); noop
                case sig: Signal[?] =>
                    subscribe(sig.asInstanceOf[Signal[Boolean]]) { v =>
                        runOnFx(updates) { node.setVisible(!v); node.setManaged(!v) }
                            .andThen(rendered.set(ui).unit)
                    }
            }
            _ <- c.onClick.fold(noop) { action =>
                node.setOnMouseClicked(_ => runHandler(action, updates)); noop
            }
            _ <- c.onKeyDown.fold(noop) { f =>
                node.setOnKeyPressed { e =>
                    runHandler(f(KeyEvent(e.getText, e.isControlDown, e.isAltDown, e.isShiftDown, e.isMetaDown)), updates)
                }; noop
            }
            _ <- c.onKeyUp.fold(noop) { f =>
                node.setOnKeyReleased { e =>
                    runHandler(f(KeyEvent(e.getText, e.isControlDown, e.isAltDown, e.isShiftDown, e.isMetaDown)), updates)
                }; noop
            }
            _ <- c.onFocus.fold(noop) { action =>
                node.focusedProperty().addListener((_, _, focused) =>
                    if focused then runHandler(action, updates)
                ); noop
            }
            _ <- c.onBlur.fold(noop) { action =>
                node.focusedProperty().addListener((_, _, focused) =>
                    if !focused then runHandler(action, updates)
                ); noop
            }
        yield ()
    end applyCommon

    private val noop: Unit < (Async & Scope) = ()

    private def applyClasses(
        node: Node,
        classes: Chunk[(String, Maybe[Signal[Boolean]])],
        updates: Channel[() => Unit],
        rendered: SignalRef[UI],
        ui: UI
    )(using Frame): Unit < (Async & Scope) =
        Kyo.foreach(classes) { (name, maybeSig) =>
            maybeSig match
                case Absent =>
                    node.getStyleClass.add(name); noop
                case Present(sig) =>
                    subscribe(sig) { v =>
                        runOnFx(updates) {
                            if v then
                                if !node.getStyleClass.contains(name) then
                                    discard(node.getStyleClass.add(name))
                            else
                                discard(node.getStyleClass.remove(name))
                        }.andThen(rendered.set(ui).unit)
                    }
        }.unit

    private def applySpecific(
        node: Node,
        elem: Element,
        updates: Channel[() => Unit],
        rendered: SignalRef[UI]
    )(using Frame): Unit < (Async & Scope) =
        elem match
            case i: Input =>
                node match
                    case cb: javafx.scene.control.CheckBox =>
                        for
                            _ <- i.disabled.fold(noop)(v => applyDisabled(node, v, updates, rendered, elem))
                            _ <- i.checked.fold(noop) {
                                case b: Boolean => cb.setSelected(b); noop
                                case sig: Signal[?] =>
                                    val typedSig = sig.asInstanceOf[Signal[Boolean]]
                                    subscribe(typedSig) { v =>
                                        runOnFx(updates)(cb.setSelected(v))
                                            .andThen(rendered.set(elem).unit)
                                    }
                            }
                            _ <- i.onInput.fold(noop) { f =>
                                cb.selectedProperty().addListener((_, _, newVal) =>
                                    runHandler(f(newVal.toString), updates)
                                ); noop
                            }
                        yield ()
                    case tf: TextField =>
                        i.placeholder.foreach(v => tf.setPromptText(v))
                        for
                            _ <- i.disabled.fold(noop)(v => applyDisabled(node, v, updates, rendered, elem))
                            _ <- i.value.fold(noop) {
                                case s: String => tf.setText(s); noop
                                case ref: SignalRef[?] =>
                                    val typedRef = ref.asInstanceOf[SignalRef[String]]
                                    for _ <- subscribe(typedRef) { v =>
                                            runOnFx(updates)(tf.setText(v))
                                                .andThen(rendered.set(elem).unit)
                                        }
                                    yield tf.textProperty().addListener((_, _, newVal) =>
                                        runHandler(typedRef.set(newVal), updates)
                                    )
                            }
                            _ <- i.onInput.fold(noop) { f =>
                                tf.textProperty().addListener((_, _, newVal) =>
                                    runHandler(f(newVal), updates)
                                ); noop
                            }
                        yield ()
                        end for
                    case _ => noop
                end match

            case t: Textarea =>
                val ta = node.asInstanceOf[JTextArea]
                t.placeholder.foreach(v => ta.setPromptText(v))
                for
                    _ <- t.disabled.fold(noop)(v => applyDisabled(node, v, updates, rendered, elem))
                    _ <- t.value.fold(noop) {
                        case s: String => ta.setText(s); noop
                        case ref: SignalRef[?] =>
                            val typedRef = ref.asInstanceOf[SignalRef[String]]
                            for _ <- subscribe(typedRef) { v =>
                                    runOnFx(updates)(ta.setText(v))
                                        .andThen(rendered.set(elem).unit)
                                }
                            yield ta.textProperty().addListener((_, _, newVal) =>
                                runHandler(typedRef.set(newVal), updates)
                            )
                    }
                    _ <- t.onInput.fold(noop) { f =>
                        ta.textProperty().addListener((_, _, newVal) =>
                            runHandler(f(newVal), updates)
                        ); noop
                    }
                yield ()
                end for

            case b: Button =>
                val btn = node.asInstanceOf[JButton]
                b.disabled.fold(noop)(v => applyDisabled(node, v, updates, rendered, elem))

            case anchor: Anchor =>
                val link = node.asInstanceOf[Hyperlink]
                anchor.href.fold(noop) {
                    case s: String => link.setUserData(s); noop
                    case sig: Signal[?] =>
                        subscribe(sig.asInstanceOf[Signal[String]]) { v =>
                            runOnFx(updates)(link.setUserData(v))
                                .andThen(rendered.set(elem).unit)
                        }
                }

            case f: Form =>
                f.onSubmit.fold(noop) { action =>
                    node.setOnKeyPressed { e =>
                        if e.getCode.eq(javafx.scene.input.KeyCode.ENTER) then
                            runHandler(action, updates)
                    }; noop
                }

            case s: Select =>
                val cb = node.asInstanceOf[ComboBox[String]]
                for
                    _ <- s.disabled.fold(noop)(v => applyDisabled(node, v, updates, rendered, elem))
                    _ <- s.value.fold(noop) {
                        case str: String => cb.setValue(str); noop
                        case ref: SignalRef[?] =>
                            val typedRef = ref.asInstanceOf[SignalRef[String]]
                            for _ <- subscribe(typedRef) { v =>
                                    runOnFx(updates)(cb.setValue(v))
                                        .andThen(rendered.set(elem).unit)
                                }
                            yield cb.valueProperty().addListener((_, _, newVal) =>
                                runHandler(typedRef.set(newVal), updates)
                            )
                    }
                    _ <- s.onChange.fold(noop) { f =>
                        cb.valueProperty().addListener((_, _, newVal) =>
                            if newVal != null then runHandler(f(newVal), updates)
                        ); noop
                    }
                yield ()
                end for

            case o: Option =>
                ()

            case l: Label =>
                ()

            case td: Td =>
                td.colspan.foreach { v =>
                    GridPane.setColumnSpan(node, v)
                }
                td.rowspan.foreach { v =>
                    GridPane.setRowSpan(node, v)
                }

            case th: Th =>
                th.colspan.foreach { v =>
                    GridPane.setColumnSpan(node, v)
                }
                th.rowspan.foreach { v =>
                    GridPane.setRowSpan(node, v)
                }

            case _: Img => ()

            case _ => ()
    end applySpecific

    private def applyDisabled(
        node: Node,
        v: Boolean | Signal[Boolean],
        updates: Channel[() => Unit],
        rendered: SignalRef[UI],
        ui: UI
    )(using Frame): Unit < (Async & Scope) =
        v match
            case b: Boolean =>
                node.setDisable(b); noop
            case sig: Signal[?] =>
                subscribe(sig.asInstanceOf[Signal[Boolean]]) { value =>
                    runOnFx(updates)(node.setDisable(value))
                        .andThen(rendered.set(ui).unit)
                }

    private def subscribeUI(
        container: Pane,
        signal: Signal[UI],
        updates: Channel[() => Unit],
        rendered: SignalRef[UI]
    )(using Frame): Unit < (Async & Scope) =
        for
            ref <- AtomicRef.init[Maybe[Fiber[Unit, Scope]]](Absent)
            _ <- subscribe(signal) { ui =>
                for
                    _ <- interruptPrev(ref)
                    fiber <- Fiber.initUnscoped {
                        for node <- build(ui, updates, rendered)
                        yield runOnFx(updates) {
                            container.getChildren.clear()
                            discard(container.getChildren.add(node))
                        }
                    }
                    _ <- ref.set(Present(fiber))
                    _ <- rendered.set(ui)
                yield ()
            }
        yield ()

    private def subscribeForeach(
        container: Pane,
        fi: ForeachIndexed[?],
        updates: Channel[() => Unit],
        rendered: SignalRef[UI]
    )(using Frame): Unit < (Async & Scope) =
        for
            ref <- AtomicRef.init[Maybe[Fiber[Unit, Scope]]](Absent)
            _ <- subscribe(fi.signal.asInstanceOf[Signal[Chunk[Any]]]) { items =>
                for
                    _ <- interruptPrev(ref)
                    fiber <- Fiber.initUnscoped {
                        val render = fi.render.asInstanceOf[(Int, Any) => UI]
                        Kyo.foreach(items.zipWithIndex) { (item, idx) =>
                            build(render(idx, item), updates, rendered)
                        }.map { nodes =>
                            runOnFx(updates) {
                                container.getChildren.clear()
                                discard(container.getChildren.addAll(nodes.toSeq.asJava))
                            }
                        }
                    }
                    _ <- ref.set(Present(fiber))
                    _ <- rendered.set(fi)
                yield ()
            }
        yield ()

    private def subscribeKeyed(
        container: Pane,
        fk: ForeachKeyed[?],
        updates: Channel[() => Unit],
        rendered: SignalRef[UI]
    )(using Frame): Unit < (Async & Scope) =
        val signal = fk.signal.asInstanceOf[Signal[Chunk[Any]]]
        val key    = fk.key.asInstanceOf[Any => String]
        val render = fk.render.asInstanceOf[(Int, Any) => UI]
        for
            nodeMap <- AtomicRef.init(Map.empty[String, Node])
            _ <- subscribe(signal) { items =>
                for
                    oldMap <- nodeMap.get
                    result <- Kyo.foreach(items.zipWithIndex) { (item, idx) =>
                        val k = key(item)
                        oldMap.get(k) match
                            case scala.Some(existing) =>
                                (k, existing): (String, Node) < (Async & Scope)
                            case scala.None =>
                                for node <- build(render(idx, item), updates, rendered)
                                yield (k, node)
                        end match
                    }
                    newMap = result.toSeq.toMap
                    _ <- runOnFx(updates) {
                        container.getChildren.clear()
                        result.foreach((_, node) => container.getChildren.add(node))
                    }
                    _ <- nodeMap.set(newMap)
                    _ <- rendered.set(fk)
                yield ()
            }
        yield ()
        end for
    end subscribeKeyed

    private def interruptPrev(ref: AtomicRef[Maybe[Fiber[Unit, Scope]]])(using Frame): Unit < (Async & Scope) =
        ref.get.map {
            case Present(f) => f.interrupt.unit
            case Absent     => ()
        }

    private def subscribe[A](signal: Signal[A])(f: A => Unit < (Async & Scope))(using Frame, Tag[Emit[Chunk[A]]]): Unit < (Async & Scope) =
        for
            fiber <- Fiber.initUnscoped(signal.streamChanges.foreach(f))
            _     <- Scope.ensure(fiber.interrupt.unit)
        yield ()

    private def runHandler(action: Unit < Async, updates: Channel[() => Unit])(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(action)))
    end runHandler

    private def buildChildren(
        children: Chunk[UI],
        updates: Channel[() => Unit],
        rendered: SignalRef[UI],
        parentIsRow: Boolean = false
    )(using
        Frame
    ): Chunk[Node] < (Async & Scope) =
        Kyo.foreach(children)(build(_, updates, rendered, parentIsRow))

    private def buildChildrenWithLayout(
        pane: Pane,
        children: Chunk[UI],
        updates: Channel[() => Unit],
        rendered: SignalRef[UI]
    )(using Frame): Unit < (Async & Scope) =
        // In the web, flex-direction: column makes ALL children stack vertically
        // as separate flex items, regardless of whether they're "inline" elements.
        // Match that behavior by adding all children directly to the VBox.
        buildChildren(children, updates, rendered).map { nodes =>
            pane.getChildren.addAll(nodes.toSeq.asJava): Unit
        }
    end buildChildrenWithLayout

    /** Build cells for a single table row, returning (node, colIdx, rowIdx) tuples. */
    private def buildTableRowCells(
        elem: Element,
        rowIdx: Int,
        updates: Channel[() => Unit],
        rendered: SignalRef[UI]
    )(using Frame): Chunk[(Node, Int, Int)] < (Async & Scope) =
        var colIdx = 0
        Kyo.foreach(elem.children) { cell =>
            val currentCol = colIdx
            val span = cell match
                case td: Td => td.colspan.getOrElse(1)
                case th: Th => th.colspan.getOrElse(1)
                case _      => 1
            colIdx += span
            build(cell, updates, rendered).map(node => (node, currentCol, rowIdx))
        }
    end buildTableRowCells

    /** Place pre-built cells into a GridPane on the FX thread. */
    private def placeTableCells(grid: GridPane, cells: Chunk[(Node, Int, Int)], updates: Channel[() => Unit])(using
        Frame
    ): Unit < Async =
        runOnFx(updates) {
            cells.foreach { (node, col, row) =>
                grid.add(node, col, row)
                if node.getStyleClass.contains("th") then
                    GridPane.setHalignment(node, javafx.geometry.HPos.CENTER)
            }
        }

    private def buildTableGrid(
        grid: GridPane,
        rows: Chunk[UI],
        updates: Channel[() => Unit],
        rendered: SignalRef[UI]
    )(using Frame): Unit < (Async & Scope) =
        // Check if any child is a ForeachIndexed (reactive table rows)
        val hasForeach = rows.exists(_.isInstanceOf[ForeachIndexed[?]])
        if !hasForeach then
            // Static-only: build all cells then place on FX thread
            Kyo.foreach(rows.zipWithIndex) { (row, rowIdx) =>
                row match
                    case elem: Element => buildTableRowCells(elem, rowIdx, updates, rendered)
                    case other =>
                        build(other, updates, rendered).map(node => Chunk((node, 0, rowIdx)))
            }.map(chunks => placeTableCells(grid, chunks.flatten, updates))
        else
            // Has ForeachIndexed children: subscribe and rebuild grid on changes.
            val foreaches = rows.collect { case fi: ForeachIndexed[?] => fi }
            val fi        = foreaches.head
            val signal    = fi.signal.asInstanceOf[Signal[Chunk[Any]]]
            val renderFn  = fi.render.asInstanceOf[(Int, Any) => UI]
            for
                ref <- AtomicRef.init[Maybe[Fiber[Unit, Scope]]](Absent)
                _ <- subscribe(signal) { items =>
                    // Expand: replace ForeachIndexed with rendered row elements
                    val expandedRows: Chunk[UI] = rows.flatMap {
                        case _: ForeachIndexed[?] =>
                            items.zipWithIndex.map((item, idx) => renderFn(idx, item))
                        case other => Chunk(other)
                    }
                    for
                        _ <- interruptPrev(ref)
                        fiber <- Fiber.initUnscoped {
                            Kyo.foreach(expandedRows.zipWithIndex) { (row, rowIdx) =>
                                row match
                                    case elem: Element => buildTableRowCells(elem, rowIdx, updates, rendered)
                                    case other =>
                                        build(other, updates, rendered).map(node => Chunk((node, 0, rowIdx)))
                            }.map { chunks =>
                                runOnFx(updates) {
                                    grid.getChildren.clear()
                                    chunks.flatten.foreach { (node, col, row) =>
                                        grid.add(node, col, row)
                                        if node.getStyleClass.contains("th") then
                                            GridPane.setHalignment(node, javafx.geometry.HPos.CENTER)
                                    }
                                }
                            }
                        }
                        _ <- ref.set(Present(fiber))
                        _ <- rendered.set(fi)
                    yield ()
                    end for
                }
            yield ()
            end for
        end if
    end buildTableGrid

    /** When a table has an explicit width, distribute columns equally to fill it. This matches HTML behavior where
      * `<table style="width:100%">` distributes space across columns. Column count is from the first tr's children in the UI tree
      * (synchronous, no async dependency).
      */
    private def applyTableColumnGrow(grid: GridPane, rows: Chunk[UI]): Unit =
        import javafx.scene.layout.ColumnConstraints
        // Count columns from the first Element row's children
        val numCols = rows.collectFirst { case elem: Element => elem.children.size }.getOrElse(0)
        if numCols > 0 then
            val pct = 100.0 / numCols
            for _ <- 0 until numCols do
                val cc = new ColumnConstraints()
                cc.setPercentWidth(pct)
                grid.getColumnConstraints.add(cc)
            end for
        end if
    end applyTableColumnGrow

    private def extractText(children: Chunk[UI]): String =
        children.flatMap {
            case Text(v) => Chunk(v)
            case _       => Chunk.empty
        }.toSeq.mkString

    private def subscribeReactiveText[N <: Node](
        node: N,
        children: Chunk[UI],
        updates: Channel[() => Unit],
        rendered: SignalRef[UI],
        setText: (N, String) => Unit
    )(using Frame): Unit < (Async & Scope) =
        val reactiveTexts = children.collect { case rt: ReactiveText => rt }
        if reactiveTexts.isEmpty then ()
        else
            Kyo.foreach(reactiveTexts) { rt =>
                subscribe(rt.signal) { v =>
                    runOnFx(updates)(setText(node, v))
                        .andThen(rendered.set(rt).unit)
                }
            }.unit
        end if
    end subscribeReactiveText

    // Apply Style props that can't be expressed as FxCSS strings.
    // The FxCssStyleRenderer handles most props via node.setStyle(css).
    // This method handles the remaining props via JavaFX Java APIs.
    private def applyStyleProps(node: Node, style: Style): Node =
        style.transform(node) { (n, prop) =>
            prop match
                case Style.Prop.FlexDirectionProp(dir) =>
                    (n, dir) match
                        case (vbox: VBox, Style.FlexDirection.row) =>
                            val hbox = new HBox()
                            hbox.setStyle(vbox.getStyle)
                            hbox.getStyleClass.addAll(vbox.getStyleClass)
                            hbox.getChildren.addAll(vbox.getChildren)
                            // CSS flex-row default: items are vertically centered
                            hbox.setAlignment(Pos.CENTER_LEFT)
                            hbox
                        case (hbox: HBox, Style.FlexDirection.column) =>
                            val vbox = new VBox()
                            vbox.setStyle(hbox.getStyle)
                            vbox.getStyleClass.addAll(hbox.getStyleClass)
                            vbox.getChildren.addAll(hbox.getChildren)
                            vbox
                        case _ => n
                case Style.Prop.Margin(t, r, b, l) =>
                    val hasAutoH = l == Style.Size.Auto || r == Style.Size.Auto
                    if hasAutoH then
                        // margin auto = center horizontally, like CSS "margin: 0 auto"
                        val wrapper = new StackPane(n)
                        StackPane.setAlignment(n, Pos.TOP_CENTER)
                        val insets = new Insets(sizeToPixels(t), 0, sizeToPixels(b), 0)
                        VBox.setMargin(wrapper, insets)
                        HBox.setMargin(wrapper, insets)
                        wrapper
                    else
                        val insets = new Insets(sizeToPixels(t), sizeToPixels(r), sizeToPixels(b), sizeToPixels(l))
                        VBox.setMargin(n, insets)
                        HBox.setMargin(n, insets)
                        n
                    end if
                case Style.Prop.Align(align) =>
                    n match
                        case hbox: HBox =>
                            // align-items in a row = cross-axis (vertical) alignment
                            if align != Style.Alignment.stretch then
                                hbox.setFillHeight(false)
                            hbox.setAlignment(align match
                                case Style.Alignment.start    => Pos.TOP_LEFT
                                case Style.Alignment.center   => Pos.CENTER_LEFT
                                case Style.Alignment.end      => Pos.BOTTOM_LEFT
                                case Style.Alignment.stretch  => Pos.CENTER_LEFT
                                case Style.Alignment.baseline => Pos.BASELINE_LEFT)
                        case vbox: VBox =>
                            // align-items in a column = cross-axis (horizontal) alignment
                            vbox.setAlignment(align match
                                case Style.Alignment.start    => Pos.TOP_LEFT
                                case Style.Alignment.center   => Pos.TOP_CENTER
                                case Style.Alignment.end      => Pos.TOP_RIGHT
                                case Style.Alignment.stretch  => Pos.TOP_LEFT
                                case Style.Alignment.baseline => Pos.TOP_LEFT)
                        case _ => ()
                    end match
                    n
                case Style.Prop.Justify(justify) =>
                    // Use spacer Regions instead of setAlignment for justify-content.
                    // HBox.setAlignment(Pos.CENTER) can cause VBox children to collapse to zero width.
                    // Spacers are more reliable and match CSS justify-content behavior.
                    n match
                        case pane: Pane =>
                            applyJustifyWithSpacers(pane, justify, pane.isInstanceOf[HBox])
                        case _ => ()
                    end match
                    n
                case Style.Prop.LineHeightProp(v) =>
                    // CSS line-height is a multiplier (e.g. 2.0 = double spacing).
                    // JavaFX setLineSpacing takes extra pixels between lines.
                    // Approximate: extra = fontSize * (multiplier - 1)
                    def applyLineHeight(label: JLabel): Unit =
                        val fontSize = if label.getFont != null then label.getFont.getSize else 14.0
                        val extraPx  = fontSize * (v - 1.0)
                        label.setLineSpacing(extraPx)
                        label.setWrapText(true)
                    end applyLineHeight
                    n match
                        case label: JLabel => applyLineHeight(label)
                        case p: Pane =>
                            forEachLabeled(p) { labeled =>
                                labeled match
                                    case l: JLabel => applyLineHeight(l)
                                    case _         => ()
                            }
                        case _ => ()
                    end match
                    n
                case Style.Prop.LetterSpacingProp(v) =>
                    val px = sizeToPixels(v)
                    n match
                        case label: JLabel =>
                            val s = label.getStyle
                            label.setStyle(s + s" -fx-font: inherit; -fx-label-padding: 0 ${px / 2} 0 ${px / 2};")
                        case p: Pane =>
                            // Letter spacing has no direct JavaFX equivalent.
                            // We insert spaces proportional to the spacing value as an approximation.
                            forEachLabeled(p) { labeled =>
                                val text = labeled.getText
                                if text != null && text.nonEmpty && px > 0 then
                                    labeled.setText(text.map(_.toString).mkString(" " * Math.max(1, (px / 4).toInt)))
                            }
                        case _ => ()
                    end match
                    n
                case Style.Prop.TextTransformProp(transform) =>
                    n match
                        case label: JLabel =>
                            val text = label.getText
                            if text != null then label.setText(transformText(text, transform))
                        case btn: JButton =>
                            val text = btn.getText
                            if text != null then btn.setText(transformText(text, transform))
                        case p: Pane =>
                            forEachLabeled(p) { labeled =>
                                val text = labeled.getText
                                if text != null then labeled.setText(transformText(text, transform))
                            }
                        case _ => ()
                    end match
                    n
                case Style.Prop.OverflowProp(overflow) =>
                    overflow match
                        case Style.Overflow.hidden =>
                            n match
                                case region: Region =>
                                    // Find Height prop in the style to enforce as maxHeight for clipping
                                    val heightPx = style.props.collectFirst {
                                        case Style.Prop.Height(v) => sizeToPixels(v)
                                    }
                                    val maxHeightPx = style.props.collectFirst {
                                        case Style.Prop.MaxHeight(v) => sizeToPixels(v)
                                    }
                                    (heightPx orElse maxHeightPx).foreach { h =>
                                        region.setMaxHeight(h)
                                        region.setMinHeight(Region.USE_PREF_SIZE)
                                        region.setPrefHeight(h)
                                    }
                                    val clip = new Rectangle()
                                    clip.widthProperty().bind(region.widthProperty())
                                    clip.heightProperty().bind(region.heightProperty())
                                    region.setClip(clip)
                                case _ => ()
                            end match
                            n
                        case Style.Overflow.scroll | Style.Overflow.auto =>
                            val scroll = new ScrollPane(n)
                            scroll.setFitToWidth(true)
                            if overflow == Style.Overflow.scroll then
                                scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS)
                                scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS)
                            else
                                scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED)
                                scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED)
                            end if
                            scroll
                        case _ => n
                case Style.Prop.TextColor(c) =>
                    // JavaFX doesn't inherit -fx-text-fill from parent panes.
                    // Propagate text color to all child text nodes.
                    val color = c.css
                    n match
                        case p: Pane =>
                            forEachLabeled(p) { labeled =>
                                val s = labeled.getStyle
                                if !s.contains("-fx-text-fill") || s.contains("-fx-text-fill: inherit") then
                                    val updated = s.replace("-fx-text-fill: inherit;", "")
                                    labeled.setStyle(updated + s" -fx-text-fill: $color;")
                                    labeled.setTextFill(javafx.scene.paint.Color.web(color))
                                end if
                            }
                        case _ => ()
                    end match
                    n
                case Style.Prop.TextAlignProp(align) =>
                    // -fx-text-alignment CSS doesn't propagate to child Labels in Panes.
                    // Apply via Java API on child text nodes.
                    val jfxAlign = align match
                        case Style.TextAlign.left    => javafx.scene.text.TextAlignment.LEFT
                        case Style.TextAlign.center  => javafx.scene.text.TextAlignment.CENTER
                        case Style.TextAlign.right   => javafx.scene.text.TextAlignment.RIGHT
                        case Style.TextAlign.justify => javafx.scene.text.TextAlignment.JUSTIFY
                    n match
                        case label: JLabel =>
                            label.setTextAlignment(jfxAlign)
                            // Also set alignment on the label itself for proper positioning
                            label.setMaxWidth(Double.MaxValue)
                            align match
                                case Style.TextAlign.center => label.setAlignment(Pos.CENTER)
                                case Style.TextAlign.right  => label.setAlignment(Pos.CENTER_RIGHT)
                                case _                      => ()
                            end match
                        case p: Pane =>
                            // Set alignment on the container to position children
                            p match
                                case vbox: VBox =>
                                    align match
                                        case Style.TextAlign.center => vbox.setAlignment(Pos.TOP_CENTER)
                                        case Style.TextAlign.right  => vbox.setAlignment(Pos.TOP_RIGHT)
                                        case _                      => ()
                                case _ => ()
                            end match
                            // Also propagate to child labels for their internal text alignment
                            forEachLabeled(p) { labeled =>
                                labeled.setTextAlignment(jfxAlign)
                                labeled.setMaxWidth(Double.MaxValue)
                                align match
                                    case Style.TextAlign.center => labeled.setAlignment(Pos.CENTER)
                                    case Style.TextAlign.right  => labeled.setAlignment(Pos.CENTER_RIGHT)
                                    case _                      => ()
                                end match
                            }
                        case _ => ()
                    end match
                    n
                case Style.Prop.FontStyleProp(fs) =>
                    // -fx-font-style CSS doesn't propagate to child Labels in Panes.
                    // Apply via Java Font API. Some system fonts (e.g. macOS .AppleSystemUIFont)
                    // don't have an italic variant, so fall back to "System" logical font.
                    val cssPart = s" -fx-font-style: ${if fs == Style.FontStyle.italic then "italic" else "normal"};"
                    def applyFontStyle(labeled: javafx.scene.control.Labeled): Unit =
                        labeled.setStyle(labeled.getStyle + cssPart)
                        val font = labeled.getFont
                        val weight = if font.getStyle.toLowerCase.contains("bold") then javafx.scene.text.FontWeight.BOLD
                        else javafx.scene.text.FontWeight.NORMAL
                        val posture = if fs == Style.FontStyle.italic then javafx.scene.text.FontPosture.ITALIC
                        else javafx.scene.text.FontPosture.REGULAR
                        // Some platform default fonts don't render italic glyphs in JavaFX
                        // even though Font reports "Italic" style. Detect when the current font
                        // is the default and substitute a platform-appropriate font with real italic.
                        val isDefault = font.getFamily == javafx.scene.text.Font.getDefault.getFamily
                        val family =
                            if (posture eq javafx.scene.text.FontPosture.ITALIC) && isDefault then
                                HostOS.italicFallbackFamily
                            else font.getFamily
                        labeled.setFont(javafx.scene.text.Font.font(family, weight, posture, font.getSize))
                    end applyFontStyle
                    n match
                        case label: JLabel => applyFontStyle(label)
                        case p: Pane       => forEachLabeled(p)(applyFontStyle)
                        case _             => ()
                    end match
                    n
                case Style.Prop.TextDecorationProp(dec) =>
                    // -fx-underline/-fx-strikethrough CSS doesn't propagate to child Labels in Panes.
                    n match
                        case p: Pane =>
                            dec match
                                case Style.TextDecoration.underline =>
                                    forEachLabeled(p)(_.setUnderline(true))
                                case Style.TextDecoration.strikethrough =>
                                    forEachLabeled(p) { labeled =>
                                        labeled.setStyle(labeled.getStyle + " -fx-strikethrough: true;")
                                    }
                                case Style.TextDecoration.none =>
                                    forEachLabeled(p) { labeled =>
                                        labeled.setUnderline(false)
                                        labeled.setStyle(labeled.getStyle + " -fx-strikethrough: false;")
                                    }
                        case _ => ()
                    end match
                    n
                case Style.Prop.WrapTextProp(v) =>
                    // -fx-wrap-text CSS may not take effect on Labels inside Panes.
                    n match
                        case label: JLabel => label.setWrapText(v)
                        case p: Pane =>
                            forEachLabeled(p) { labeled =>
                                labeled.setWrapText(v)
                            }
                        case _ => ()
                    end match
                    n
                case Style.Prop.Width(v) =>
                    // Set both prefWidth and maxWidth so applyLayoutDefaults doesn't
                    // override with MAX_VALUE (VBox fillWidth behavior).
                    // For percentage widths, we need to bind to parent width.
                    n match
                        case r: Region =>
                            v match
                                case Style.Size.Pct(pct) =>
                                    // Bind to parent width when available
                                    r.parentProperty().addListener { (_, _, parent) =>
                                        parent match
                                            case pr: Region =>
                                                val binding = pr.widthProperty().multiply(pct / 100.0)
                                                r.prefWidthProperty().bind(binding)
                                                r.maxWidthProperty().bind(binding)
                                            case _ => ()
                                    }
                                case _ =>
                                    val px = sizeToPixels(v)
                                    r.setPrefWidth(px)
                                    r.setMaxWidth(px)
                        case _ => ()
                    end match
                    n
                case Style.Prop.MaxWidth(v) =>
                    n match
                        case r: Region => r.setMaxWidth(sizeToPixels(v))
                        case _         => ()
                    n
                case Style.Prop.MaxHeight(v) =>
                    n match
                        case r: Region => r.setMaxHeight(sizeToPixels(v))
                        case _         => ()
                    n
                case _ => n
        }
    end applyStyleProps

    // Apply browser-like layout defaults.
    // In CSS, flex-direction: column with default align-items: stretch makes all children
    // fill the container width. In JavaFX, fillWidth only works if children have
    // maxWidth != USE_COMPUTED_SIZE. Set maxWidth to MAX_VALUE on direct children
    // when the default stretch alignment is active.
    private def applyLayoutDefaults(node: Node): Unit =
        // Suppress default border when border-radius is set without explicit border
        node match
            case r: Region =>
                val s = r.getStyle
                if s != null && s.contains("-fx-border-radius") && !s.contains("-fx-border-style") && !s.contains("-fx-border-color") then
                    r.setStyle(s + " -fx-border-style: none;")
            case _ => ()
        end match
        // VBox-specific: stretch children to full width
        node match
            case vbox: VBox =>
                vbox.setFillWidth(true)
                // Only stretch children if alignment is default (TOP_LEFT) or stretch-compatible.
                // Non-stretch alignments (TOP_CENTER, TOP_RIGHT) mean children should NOT fill width.
                val align     = vbox.getAlignment
                val isStretch = (align eq Pos.TOP_LEFT) || (align eq null)
                if isStretch then
                    vbox.getChildren.forEach {
                        case r: Region =>
                            // Don't override explicit maxWidth set by style props
                            if r.getMaxWidth < 0 then // USE_COMPUTED_SIZE = -1
                                r.setMaxWidth(Double.MaxValue)
                        case _ => ()
                    }
                end if
            case _ => ()
        end match
    end applyLayoutDefaults

    // Convert common web CSS properties to JavaFX CSS equivalents.
    // This is needed when raw CSS strings are passed via .style(signal).
    private val webToFxReplacements = Seq(
        "background-color:" -> "-fx-background-color:",
        "font-size:"        -> "-fx-font-size:",
        "font-weight:"      -> "-fx-font-weight:",
        "font-style:"       -> "-fx-font-style:",
        "border-color:"     -> "-fx-border-color:",
        "border-width:"     -> "-fx-border-width:",
        "border-style:"     -> "-fx-border-style:",
        "padding:"          -> "-fx-padding:"
    )
    private def webCssToFxCss(css: String): String =
        // Apply longer replacements first to avoid partial matches
        var result = css
        for (web, fx) <- webToFxReplacements do
            result = result.replace(web, fx)
        // Handle standalone "color:" (not part of other properties)
        result = result.replaceAll("(?<![\\w-])color:", "-fx-text-fill:")
        result
    end webCssToFxCss

    private def sizeToPixels(s: Style.Size): Double = s match
        case Style.Size.Px(v)  => v
        case Style.Size.Em(v)  => v * 16
        case Style.Size.Pct(v) => v
        case Style.Size.Auto   => 0

    private def applyJustifyWithSpacers(pane: Pane, justify: Style.Justification, isRow: Boolean): Unit =
        import javafx.scene.layout.Priority
        def makeSpacer(): Region =
            val spacer = new Region()
            if isRow then HBox.setHgrow(spacer, Priority.ALWAYS)
            else VBox.setVgrow(spacer, Priority.ALWAYS)
            spacer
        end makeSpacer

        justify match
            case Style.Justification.start  => () // default, no change
            case Style.Justification.center =>
                // Add growing spacer before and after all children
                pane.getChildren.add(0, makeSpacer()): Unit
                pane.getChildren.add(makeSpacer()): Unit
            case Style.Justification.end =>
                // Add growing spacer before all children
                pane.getChildren.add(0, makeSpacer()): Unit
            case Style.Justification.spaceBetween =>
                // Add growing spacers between each pair of children
                val count = pane.getChildren.size()
                var i     = count - 1
                while i > 0 do
                    pane.getChildren.add(i, makeSpacer())
                    i -= 1
            case Style.Justification.spaceAround =>
                // Equal space around each item: spacer before each + spacer at end
                val count = pane.getChildren.size()
                var i     = count - 1
                while i >= 0 do
                    pane.getChildren.add(i, makeSpacer())
                    i -= 1
                pane.getChildren.add(makeSpacer()): Unit
            case Style.Justification.spaceEvenly =>
                // Equal space between all items and edges (same as spaceAround with equal spacers)
                val count = pane.getChildren.size()
                var i     = count - 1
                while i >= 0 do
                    pane.getChildren.add(i, makeSpacer())
                    i -= 1
                pane.getChildren.add(makeSpacer()): Unit
        end match
    end applyJustifyWithSpacers

    private def forEachLabeled(parent: Pane)(f: javafx.scene.control.Labeled => Unit): Unit =
        parent.getChildren.forEach {
            case labeled: javafx.scene.control.Labeled => f(labeled)
            case child: Pane                           => forEachLabeled(child)(f)
            case _                                     => ()
        }

    private def transformText(text: String, transform: Style.TextTransform): String =
        transform match
            case Style.TextTransform.uppercase => text.toUpperCase
            case Style.TextTransform.lowercase => text.toLowerCase
            case Style.TextTransform.capitalize =>
                text.split("\\s+").map(w => if w.nonEmpty then s"${w(0).toUpper}${w.substring(1)}" else w).mkString(" ")
            case Style.TextTransform.none => text

end JavaFxBackend

object JavaFxBackend:

    /** Debug utility: produces a readable tree dump of the JavaFX node hierarchy. Shows node type, inline style, dimensions, text content,
      * text-fill, font, and children. Call from screenshot code or tests to diagnose rendering issues.
      */
    def dumpTree(node: Node, indent: Int = 0): String =
        val sb  = new StringBuilder
        val pad = "  " * indent

        val typeName = node.getClass.getSimpleName
        sb.append(s"$pad$typeName")

        // Style classes
        val classes = node.getStyleClass
        if classes != null && !classes.isEmpty then
            sb.append(s" cls=[${classes.toArray.mkString(",")}]")

        // Inline style
        val style = node.getStyle
        if style != null && style.nonEmpty then
            sb.append(s" style=\"$style\"")

        // Labeled-specific: text, textFill, font
        node match
            case labeled: javafx.scene.control.Labeled =>
                val text = labeled.getText
                if text != null && text.nonEmpty then
                    sb.append(s" text=\"${text.take(40)}\"")
                val fill = labeled.getTextFill
                if fill != null then
                    sb.append(s" textFill=$fill")
                val font = labeled.getFont
                if font != null then
                    sb.append(s" font=${font.getFamily}/${font.getStyle}/${font.getSize}")
            case _ => ()
        end match

        // Region-specific: size, padding
        node match
            case r: Region =>
                val w = r.getWidth; val h = r.getHeight
                if w > 0 || h > 0 then sb.append(s" size=${w}x$h")
                val maxW = r.getMaxWidth
                if maxW >= 0 && maxW < Double.MaxValue then sb.append(s" maxW=$maxW")
                else if maxW == Double.MaxValue then sb.append(s" maxW=MAX")
            case _ => ()
        end match

        // Container alignment
        node match
            case vbox: VBox =>
                sb.append(s" align=${vbox.getAlignment} fillW=${vbox.isFillWidth}")
            case hbox: HBox =>
                sb.append(s" align=${hbox.getAlignment}")
            case _ => ()
        end match

        sb.append("\n")

        // Recurse into children
        node match
            case parent: javafx.scene.Parent =>
                parent.getChildrenUnmodifiable.forEach { child =>
                    sb.append(dumpTree(child, indent + 1))
                }
            case _ => ()
        end match

        sb.toString
    end dumpTree

end JavaFxBackend
