package demo
import kyo.*
import kyo.UIDsl.*
import scala.language.implicitConversions

object CounterDemo extends KyoApp:

    run {
        for
            count <- Signal.initRef(0)
        yield
            val ui: UI = div(
                button("-").onClick(count.getAndUpdate(_ - 1).unit),
                span(count.map(_.toString)),
                button("+").onClick(count.getAndUpdate(_ + 1).unit)
            )
            val output = TuiBackend.renderToString(ui, 40, 10)
            val path   = java.nio.file.Paths.get("kyo-ui/counter-dump.txt")
            java.nio.file.Files.writeString(path, output)
            java.lang.System.err.println(s"Counter dumped to ${path.toAbsolutePath}")
            val layoutDump = TuiBackend.renderLayoutDump(ui, 40, 10)
            val layoutPath = java.nio.file.Paths.get("kyo-ui/counter-layout.txt")
            java.nio.file.Files.writeString(layoutPath, layoutDump)
            java.lang.System.err.println(s"Layout dumped to ${layoutPath.toAbsolutePath}")
    }

end CounterDemo
