package kyo

class DomBackendPlaywrightTest extends Test:

    private val demoHtml = java.nio.file.Paths.get("../demo.html").toAbsolutePath.normalize
    private val outDir   = java.nio.file.Paths.get("../screenshots").toAbsolutePath.normalize

    private def init(using Frame): Unit < (Browser & Abort[Nothing]) =
        for
            _ <- Browser.goto(s"file://$demoHtml")
            _ <- settle
        yield ()

    private def settle(using Frame): Unit < Browser =
        Browser.runJavaScript("return new Promise(r => setTimeout(r, 0))").unit

    private def screenshot(name: String)(using Frame): Unit < (Browser & Sync) =
        for
            img <- Browser.screenshot(1280, 720)
            _   <- img.writeFileBinary(s"$outDir/$name.png")
        yield ()

    locally {
        val _ = java.nio.file.Files.createDirectories(outDir)
    }

    "initial render" in run {
        Browser.run(30.seconds) {
            for
                _          <- init
                _          <- screenshot("01-initial-render")
                title      <- Browser.innerText("h1")
                hero       <- Browser.innerText(".hero h2")
                counterVal <- Browser.innerText(".counter-value")
            yield
                assert(title == "Kyo UI Demo")
                assert(hero == "Welcome to Kyo UI")
                assert(counterVal == "0")
        }
    }

    "counter" in run {
        Browser.run(30.seconds) {
            for
                _ <- init

                _ <- Browser.click(".counter-btn:nth-child(3)")
                _ <- settle
                _ <- Browser.click(".counter-btn:nth-child(3)")
                _ <- settle
                _ <- Browser.click(".counter-btn:nth-child(3)")
                _ <- settle

                val3 <- Browser.innerText(".counter-value")
                _    <- screenshot("02-counter-incremented")

                _ <- Browser.click(".counter-btn:nth-child(1)")
                _ <- settle

                val2 <- Browser.innerText(".counter-value")
            yield
                assert(val3 == "3")
                assert(val2 == "2")
        }
    }

    "todo list" in run {
        Browser.run(30.seconds) {
            for
                _ <- init

                _ <- Browser.fill(".todo-input input", "Buy groceries")
                _ <- Browser.click(".todo-input .submit")
                _ <- settle

                _ <- Browser.fill(".todo-input input", "Walk the dog")
                _ <- Browser.click(".todo-input .submit")
                _ <- settle

                _ <- Browser.fill(".todo-input input", "Write tests")
                _ <- Browser.click(".todo-input .submit")
                _ <- settle

                _ <- screenshot("03-todos-added")

                todoCount <- Browser.runJavaScript("return document.querySelectorAll('.todo-item').length.toString()")
                todo1     <- Browser.innerText(".todo-item:nth-child(1) span")

                _ <- Browser.click(".todo-item:nth-child(2) .delete-btn")
                _ <- settle

                _ <- screenshot("04-todo-deleted")

                remainingCount <- Browser.runJavaScript("return document.querySelectorAll('.todo-item').length.toString()")
                remaining1     <- Browser.innerText(".todo-item:nth-child(1) span")
                remaining2     <- Browser.innerText(".todo-item:nth-child(2) span")
            yield
                assert(todoCount == "3")
                assert(todo1 == "Buy groceries")
                assert(remainingCount == "2")
                assert(remaining1 == "Buy groceries")
                assert(remaining2 == "Write tests")
        }
    }

    "dark mode" in run {
        Browser.run(30.seconds) {
            for
                _ <- init

                hasDark <- Browser.runJavaScript("return document.querySelector('.app.dark') !== null ? 'true' : 'false'")

                _ <- Browser.click(".theme-toggle")
                _ <- settle

                hasDarkAfter <- Browser.runJavaScript("return document.querySelector('.app.dark') !== null ? 'true' : 'false'")
                _            <- screenshot("05-dark-mode")

                _ <- Browser.click(".theme-toggle")
                _ <- settle

                hasDarkBack <- Browser.runJavaScript("return document.querySelector('.app.dark') !== null ? 'true' : 'false'")
                _           <- screenshot("06-light-restored")
            yield
                assert(hasDark == "false")
                assert(hasDarkAfter == "true")
                assert(hasDarkBack == "false")
        }
    }

    "full workflow" in run {
        Browser.run(30.seconds) {
            for
                _ <- init

                _ <- Browser.click(".theme-toggle")
                _ <- settle

                _ <- Browser.click(".counter-btn:nth-child(3)")
                _ <- settle
                _ <- Browser.click(".counter-btn:nth-child(3)")
                _ <- settle
                _ <- Browser.click(".counter-btn:nth-child(3)")
                _ <- settle
                _ <- Browser.click(".counter-btn:nth-child(3)")
                _ <- settle
                _ <- Browser.click(".counter-btn:nth-child(3)")
                _ <- settle

                _ <- Browser.fill(".todo-input input", "Deploy to production")
                _ <- Browser.click(".todo-input .submit")
                _ <- settle

                _ <- Browser.fill(".todo-input input", "Write documentation")
                _ <- Browser.click(".todo-input .submit")
                _ <- settle

                _ <- screenshot("07-full-workflow")

                counterVal <- Browser.innerText(".counter-value")
                todoCount  <- Browser.runJavaScript("return document.querySelectorAll('.todo-item').length.toString()")
                isDark     <- Browser.runJavaScript("return document.querySelector('.app.dark') !== null ? 'true' : 'false'")
            yield
                assert(counterVal == "5")
                assert(todoCount == "2")
                assert(isDark == "true")
        }
    }

end DomBackendPlaywrightTest
