package demo

import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.*
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.stage.Stage

/** Programmatic interaction with live JavaFX scene graph.
  *
  * Mirrors the Browser API pattern but for JavaFX nodes. All operations run on the FX application thread via Platform.runLater +
  * CountDownLatch.
  */
object JavaFxInteraction:

    /** Run a block on the FX thread and wait for completion. */
    def runOnFxSync[A](f: => A): A =
        var result: A        = null.asInstanceOf[A]
        var error: Throwable = null
        val latch            = new java.util.concurrent.CountDownLatch(1)
        Platform.runLater { () =>
            try result = f
            catch case t: Throwable => error = t
            finally latch.countDown()
        }
        latch.await()
        if error != null then throw error
        result
    end runOnFxSync

    /** Get the current stage's scene root. */
    private def root: Region =
        val windows = javafx.stage.Window.getWindows
        if windows.isEmpty then throw new RuntimeException("No JavaFX windows open")
        val stage = windows.get(0).asInstanceOf[Stage]
        stage.getScene.getRoot.asInstanceOf[Region]
    end root

    /** Lookup a single node by CSS selector (e.g. ".button", "#myId"). */
    def lookup(selector: String): Node =
        runOnFxSync {
            val node = root.lookup(selector)
            if node == null then throw new RuntimeException(s"Node not found: $selector")
            node
        }

    /** Lookup all nodes matching a CSS selector. */
    def lookupAll(selector: String): List[Node] =
        runOnFxSync {
            import scala.jdk.CollectionConverters.*
            root.lookupAll(selector).asScala.toList
        }

    /** Click a node by firing a MouseEvent (matches setOnMouseClicked handler). */
    def click(selector: String): Unit =
        runOnFxSync {
            val node = root.lookup(selector)
            if node == null then throw new RuntimeException(s"Node not found: $selector")
            val event = new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0,
                0,
                0,
                0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                null
            )
            node.fireEvent(event)
        }

    /** Click the Nth node matching a selector (0-indexed). */
    def clickNth(selector: String, index: Int): Unit =
        runOnFxSync {
            import scala.jdk.CollectionConverters.*
            val nodes = root.lookupAll(selector).asScala.toList
            if index >= nodes.size then
                throw new RuntimeException(s"Index $index out of range, only ${nodes.size} nodes match $selector")
            val event = new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0,
                0,
                0,
                0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                null
            )
            nodes(index).fireEvent(event)
        }

    /** Type text into a TextField or TextArea found by CSS selector. */
    def fillText(selector: String, text: String): Unit =
        runOnFxSync {
            val node = root.lookup(selector)
            if node == null then throw new RuntimeException(s"Text control not found: $selector")
            node match
                case tf: TextField => tf.setText(text)
                case ta: TextArea  => ta.setText(text)
                case other         => throw new RuntimeException(s"Node $selector is not a text control: ${other.getClass}")
            end match
        }

    /** Set checkbox checked state. */
    def setChecked(selector: String, checked: Boolean): Unit =
        runOnFxSync {
            val node = root.lookup(selector)
            if node == null then throw new RuntimeException(s"CheckBox not found: $selector")
            node match
                case cb: CheckBox => cb.setSelected(checked)
                case other        => throw new RuntimeException(s"Node $selector is not a CheckBox: ${other.getClass}")
        }

    /** Select an option in a ComboBox by value string. */
    def selectOption(selector: String, value: String): Unit =
        runOnFxSync {
            val node = root.lookup(selector)
            if node == null then throw new RuntimeException(s"ComboBox not found: $selector")
            node match
                case cb: ComboBox[?] =>
                    cb.asInstanceOf[ComboBox[String]].setValue(value)
                case other => throw new RuntimeException(s"Node $selector is not a ComboBox: ${other.getClass}")
            end match
        }

    /** Get the text content of a node. For containers (HBox, VBox), recursively collects text from child Labels. */
    def getText(selector: String): String =
        runOnFxSync {
            val node = root.lookup(selector)
            if node == null then throw new RuntimeException(s"Node not found: $selector")
            collectText(node)
        }

    /** Recursively collect text from a node and its children. */
    private def collectText(node: Node): String =
        node match
            case l: Labeled    => Option(l.getText).getOrElse("")
            case tf: TextField => tf.getText
            case ta: TextArea  => ta.getText
            case p: Parent =>
                import scala.jdk.CollectionConverters.*
                p.getChildrenUnmodifiable.asScala.map(collectText).filter(_.nonEmpty).mkString("")
            case _ => ""

    /** Check if a node exists. */
    def exists(selector: String): Boolean =
        runOnFxSync {
            root.lookup(selector) != null
        }

    /** Check if a node is visible. */
    def isVisible(selector: String): Boolean =
        runOnFxSync {
            val node = root.lookup(selector)
            node != null && node.isVisible
        }

    /** Count nodes matching a selector. */
    def count(selector: String): Int =
        runOnFxSync {
            import scala.jdk.CollectionConverters.*
            root.lookupAll(selector).size()
        }

    /** Wait for the FX thread to process pending updates. */
    def waitForUpdates(millis: Long = 200): Unit =
        Thread.sleep(millis)
        // Drain any pending FX events
        runOnFxSync { () }
    end waitForUpdates

    /** Take a screenshot of the current JavaFX window and save to the given path. */
    def screenshot(outPath: java.nio.file.Path): Unit =
        runOnFxSync {
            val r = root
            r.applyCss()
            val prefH = r.prefHeight(r.getScene.getWidth)
            val fullH = math.max(prefH, r.getScene.getHeight)
            r.resize(r.getScene.getWidth, fullH)
            r.layout()
            val params = new javafx.scene.SnapshotParameters()
            params.setFill(javafx.scene.paint.Color.TRANSPARENT)
            val img    = r.snapshot(params, null)
            val w      = img.getWidth.toInt
            val h      = img.getHeight.toInt
            val reader = img.getPixelReader
            val bImg   = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            for y <- 0 until h; x <- 0 until w do
                bImg.setRGB(x, y, reader.getArgb(x, y))
            val _ = javax.imageio.ImageIO.write(bImg, "png", outPath.toFile)
        }

    /** Dump the scene tree for debugging. */
    def dumpTree(): String =
        runOnFxSync {
            kyo.JavaFxBackend.dumpTree(root)
        }

end JavaFxInteraction
