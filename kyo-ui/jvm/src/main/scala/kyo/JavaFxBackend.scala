package kyo

import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button as JButton
import javafx.scene.control.ComboBox
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label as JLabel
import javafx.scene.control.Separator
import javafx.scene.control.TextArea as JTextArea
import javafx.scene.control.TextField
import javafx.scene.image.ImageView
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Stage
import kyo.UI.AST.*
import scala.jdk.CollectionConverters.*

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
            Platform.startup(() =>
                started.countDown()
                drainLoop(updates)
            )
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
            val scene = new Scene(root.asInstanceOf[javafx.scene.Parent], width, height)
            stage.setScene(scene)
            stage.show()
        }
    end showWindow

    private def runOnFx(updates: Channel[() => Unit])(fn: => Unit)(using Frame): Unit < Async =
        Abort.recover[Closed](_ => Abort.panic(new IllegalStateException("JavaFxBackend update channel was closed")))(
            updates.put(() => fn)
        )

    private def build(ui: UI, updates: Channel[() => Unit], rendered: SignalRef[UI])(using Frame): Node < (Async & Scope) =
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
                val container = new VBox()
                subscribeUI(container, signal, updates, rendered).map(_ => container: Node)

            case fi: ForeachIndexed[?] =>
                val container = new VBox()
                subscribeForeach(container, fi, updates, rendered).map(_ => container: Node)

            case fk: ForeachKeyed[?] =>
                val container = new VBox()
                subscribeKeyed(container, fk, updates, rendered).map(_ => container: Node)

            case Fragment(children) =>
                buildChildren(children, updates, rendered).map { nodes =>
                    val box = new VBox()
                    box.getChildren.addAll(nodes.toSeq.asJava)
                    box: Node
                }

            case elem: Element =>
                buildElement(elem, updates, rendered)
    end build

    private def buildElement(elem: Element, updates: Channel[() => Unit], rendered: SignalRef[UI])(using
        Frame
    ): Node < (Async & Scope) =
        val node = createNode(elem)
        for
            _ <- applyCommon(node, elem, elem.common, updates, rendered)
            _ <- applySpecific(node, elem, updates, rendered)
            _ <- addChildren(node, elem, updates, rendered)
        yield node: Node
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
                case btn: JButton =>
                    val text = extractText(elem.children)
                    if text.nonEmpty then btn.setText(text)
                case link: Hyperlink =>
                    val text = extractText(elem.children)
                    if text.nonEmpty then link.setText(text)
                case grid: GridPane =>
                    buildTableGrid(grid, elem.children, updates, rendered)
                case pane: Pane =>
                    buildChildrenWithLayout(pane, elem.children, updates, rendered)
                case _ => ()
    end addChildren

    private def createNode(elem: Element): Node =
        val (node, cls) = elem match
            case _: Div      => (new VBox(), "div")
            case _: P        => (new VBox(), "p")
            case _: Span     => (new HBox(), "span")
            case _: Ul       => (new VBox(), "ul")
            case _: Ol       => (new VBox(), "ol")
            case _: Li       => (new HBox(), "li")
            case _: Nav      => (new HBox(), "nav")
            case _: Header   => (new VBox(), "header")
            case _: Footer   => (new VBox(), "footer")
            case _: Section  => (new VBox(), "section")
            case _: Main     => (new VBox(), "main")
            case _: Pre      => (new VBox(), "pre")
            case _: Code     => (new JLabel(), "code")
            case _: Table    => (new GridPane(), "table")
            case _: Tr       => (new HBox(), "tr")
            case _: Td       => (new VBox(), "td")
            case _: Th       => (new VBox(), "th")
            case _: H1       => (new JLabel(), "h1")
            case _: H2       => (new JLabel(), "h2")
            case _: H3       => (new JLabel(), "h3")
            case _: H4       => (new JLabel(), "h4")
            case _: H5       => (new JLabel(), "h5")
            case _: H6       => (new JLabel(), "h6")
            case _: Hr       => (new Separator(), "hr")
            case _: Br       => (new JLabel(""), "br")
            case _: Button   => (new JButton(), "button")
            case _: Anchor   => (new Hyperlink(), "a")
            case _: Form     => (new VBox(), "form")
            case _: Select   => (new ComboBox[String](), "select")
            case _: Option   => (new JLabel(), "option")
            case _: Input    => (new TextField(), "input")
            case _: Textarea => (new JTextArea(), "textarea")
            case _: Label    => (new JLabel(), "label")
            case i: Img      => (new ImageView(i.src), "img")
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
                        node.setStyle(if uiCss.nonEmpty then s"$s $uiCss" else s); noop
                    case sig: Signal[?] =>
                        subscribe(sig.asInstanceOf[Signal[String]]) { v =>
                            runOnFx(updates)(node.setStyle(if uiCss.nonEmpty then s"$v $uiCss" else v))
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
                val tf = node.asInstanceOf[TextField]
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

    private def buildChildren(children: Chunk[UI], updates: Channel[() => Unit], rendered: SignalRef[UI])(using
        Frame
    ): Chunk[Node] < (Async & Scope) =
        Kyo.foreach(children)(build(_, updates, rendered))

    private def buildChildrenWithLayout(
        pane: Pane,
        children: Chunk[UI],
        updates: Channel[() => Unit],
        rendered: SignalRef[UI]
    )(using Frame): Unit < (Async & Scope) =
        // Group consecutive inline children into HBoxes, block children standalone
        val groups = groupByDisplay(children)
        Kyo.foreach(groups) { group =>
            group match
                case Left(inlineChildren) =>
                    // Consecutive inline children → wrap in HBox
                    Kyo.foreach(inlineChildren) { child =>
                        build(child, updates, rendered)
                    }.map { nodes =>
                        val hbox = new HBox()
                        hbox.getChildren.addAll(nodes.toSeq.asJava): Unit
                        pane.getChildren.add(hbox): Unit
                    }
                case Right(blockChild) =>
                    // Block child → add directly
                    build(blockChild, updates, rendered).map { node =>
                        pane.getChildren.add(node): Unit
                    }
        }.unit
    end buildChildrenWithLayout

    // Groups children: Left = consecutive inline, Right = single block
    private def groupByDisplay(children: Chunk[UI]): Chunk[Either[Chunk[UI], UI]] =
        var result  = Chunk.empty[Either[Chunk[UI], UI]]
        var inlines = Chunk.empty[UI]
        children.foreach { child =>
            if isBlockLevel(child) then
                if inlines.nonEmpty then
                    result = result.append(Left(inlines))
                    inlines = Chunk.empty
                result = result.append(Right(child))
            else
                inlines = inlines.append(child)
        }
        if inlines.nonEmpty then
            result = result.append(Left(inlines))
        result
    end groupByDisplay

    private def isBlockLevel(ui: UI): Boolean =
        ui match
            case _: Div | _: P | _: Section | _: Main | _: Header | _: Footer | _: Nav |
                _: Ul | _: Ol | _: Li | _: Pre | _: Form | _: Table |
                _: H1 | _: H2 | _: H3 | _: H4 | _: H5 | _: H6 | _: Hr => true
            case Fragment(_)          => true
            case ReactiveNode(_)      => true
            case _: ForeachIndexed[?] => true
            case _: ForeachKeyed[?]   => true
            case _                    => false

    private def buildTableGrid(
        grid: GridPane,
        rows: Chunk[UI],
        updates: Channel[() => Unit],
        rendered: SignalRef[UI]
    )(using Frame): Unit < (Async & Scope) =
        Kyo.foreach(rows.zipWithIndex) { (row, rowIdx) =>
            row match
                case elem: Element =>
                    Kyo.foreach(elem.children.zipWithIndex) { (cell, colIdx) =>
                        build(cell, updates, rendered).map { node =>
                            grid.add(node, colIdx, rowIdx)
                        }
                    }.unit
                case other =>
                    build(other, updates, rendered).map { node =>
                        grid.add(node, 0, rowIdx)
                    }
        }.unit

    private def extractText(children: Chunk[UI]): String =
        children.flatMap {
            case Text(v) => Chunk(v)
            case _       => Chunk.empty
        }.toSeq.mkString

end JavaFxBackend
