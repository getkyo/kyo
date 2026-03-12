package demo
import kyo.*
import kyo.UIDsl.*
import scala.language.implicitConversions

/** Headless single-frame dump — no TTY needed. Renders DemoUI to a file. */
object TuiDumpFrame extends KyoApp:

    run {
        for
            ui <- DemoUI.build
        yield
            val output = TuiBackend.renderToString(ui, 80, 50)
            val path   = java.nio.file.Paths.get("kyo-ui/tui-frame-dump.txt")
            java.nio.file.Files.writeString(path, output)
            java.lang.System.err.println(s"Frame dumped to ${path.toAbsolutePath}")
            // Dump layout tree
            val layoutDump = TuiBackend.renderLayoutDump(ui, 80, 50)
            // Also dump raw ANSI for color debugging
            val rawAnsi = TuiBackend.renderToRawAnsi(ui, 80, 50)
            val rawPath = java.nio.file.Paths.get("kyo-ui/tui-raw-ansi.txt")
            java.nio.file.Files.writeString(rawPath, rawAnsi)
            java.lang.System.err.println(s"Raw ANSI dumped to ${rawPath.toAbsolutePath}")
            val layoutPath = java.nio.file.Paths.get("kyo-ui/tui-layout-dump.txt")
            java.nio.file.Files.writeString(layoutPath, layoutDump)
            java.lang.System.err.println(s"Layout dumped to ${layoutPath.toAbsolutePath}")
    }
end TuiDumpFrame
