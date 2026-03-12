package demo

import kyo.*

object ExperimentScreenshot extends KyoApp:

    private def settle(using Frame): Unit < Browser =
        Browser.runJavaScript("return new Promise(r => setTimeout(r, 100))").unit

    private def shot(outDir: java.nio.file.Path, name: String)(using Frame): Unit < (Browser & Sync) =
        for
            img <- Browser.screenshot(1280, 900)
            _   <- img.writeFileBinary(s"$outDir/$name.png")
        yield ()

    private def initialAndToggle(outDir: java.nio.file.Path)(using Frame): Unit < (Browser & Sync) =
        for
            _ <- settle
            _ <- shot(outDir, "exp-01-initial")
            _ <- Console.printLine("1. Initial render done")

            stats <- Browser.innerText(".stats")
            _     <- Console.printLine(s"   Stats: $stats")

            _      <- Browser.click(".todo-item:nth-child(1) input[type=checkbox]")
            _      <- settle
            _      <- shot(outDir, "exp-02-todo-toggled")
            stats2 <- Browser.innerText(".stats")
            _      <- Console.printLine(s"2. After toggle: $stats2")
        yield ()

    private def addAndDelete(outDir: java.nio.file.Path)(using Frame): Unit < (Browser & Sync) =
        for
            _     <- Browser.fill(".new-todo", "Deploy app")
            _     <- Browser.click(".add-btn")
            _     <- settle
            _     <- shot(outDir, "exp-03-todo-added")
            count <- Browser.runJavaScript("return document.querySelectorAll('.todo-item').length.toString()")
            _     <- Console.printLine(s"3. Todo count after add: $count")

            _      <- Browser.click(".todo-item:nth-child(2) .delete-btn")
            _      <- settle
            _      <- shot(outDir, "exp-04-todo-deleted")
            count2 <- Browser.runJavaScript("return document.querySelectorAll('.todo-item').length.toString()")
            _      <- Console.printLine(s"4. Todo count after delete: $count2")
        yield ()

    private def tabsAndClear(outDir: java.nio.file.Path)(using Frame): Unit < (Browser & Sync) =
        for
            _          <- Browser.click(".tab-btn:nth-child(2)")
            _          <- settle
            _          <- shot(outDir, "exp-05-tab-b")
            tabContent <- Browser.innerText(".tab-content")
            _          <- Console.printLine(s"5. Tab B content: $tabContent")

            _           <- Browser.click(".tab-btn:nth-child(3)")
            _           <- settle
            _           <- shot(outDir, "exp-06-tab-c")
            tabContent2 <- Browser.innerText(".tab-content")
            _           <- Console.printLine(s"6. Tab C content: $tabContent2")

            hasClearBtn <- Browser.runJavaScript("return document.querySelector('.clear-btn') !== null ? 'true' : 'false'")
            _           <- Console.printLine(s"7. Clear btn visible: $hasClearBtn")
            _           <- Browser.click(".clear-btn")
            _           <- settle
            _           <- shot(outDir, "exp-07-cleared")
            count3      <- Browser.runJavaScript("return document.querySelectorAll('.todo-item').length.toString()")
            _           <- Console.printLine(s"   After clear: $count3 todos")

            tableRows <- Browser.runJavaScript("return document.querySelectorAll('.dynamic-table tr').length.toString()")
            _         <- Console.printLine(s"8. Table rows (incl header): $tableRows")
            _         <- shot(outDir, "exp-08-final")
        yield ()

    run {
        val html   = java.nio.file.Paths.get("../experiment.html").toAbsolutePath.normalize
        val outDir = java.nio.file.Paths.get("../screenshots").toAbsolutePath.normalize
        val _      = java.nio.file.Files.createDirectories(outDir)

        Browser.run(30.seconds) {
            for
                _ <- Browser.goto(s"file://$html")
                _ <- initialAndToggle(outDir)
                _ <- addAndDelete(outDir)
                _ <- tabsAndClear(outDir)
            yield ()
        }
    }
end ExperimentScreenshot
