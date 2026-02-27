package demo

import kyo.*

object DemoScreenshot extends KyoApp:

    run {
        val demoHtml = java.nio.file.Paths.get("../demo.html").toAbsolutePath.normalize
        val outDir   = java.nio.file.Paths.get("../screenshots").toAbsolutePath.normalize
        for
            _ = java.nio.file.Files.createDirectories(outDir)
            _ <- Browser.run(30.seconds) {
                for
                    _ <- Browser.goto(s"file://$demoHtml")
                    _ <- Browser.runJavaScript("return new Promise(r => setTimeout(r, 1000))")

                    // Click + and capture console
                    logs <- Browser.runJavaScript("""
                        var logs = [];
                        var origLog = console.log;
                        var origErr = console.error;
                        console.log = function() { logs.push(Array.from(arguments).join(' ')); origLog.apply(console, arguments); };
                        console.error = function() { logs.push('ERROR: ' + Array.from(arguments).join(' ')); origErr.apply(console, arguments); };
                        document.querySelectorAll('.counter-btn')[1].click();
                        return new Promise(r => setTimeout(() => r(JSON.stringify(logs)), 500));
                    """)
                    _ <- Console.printLine(s"Console after click: $logs")

                    cv <- Browser.innerText(".counter-value")
                    _  <- Console.printLine(s"Counter: $cv")
                yield ()
            }
        yield ()
        end for
    }
end DemoScreenshot
