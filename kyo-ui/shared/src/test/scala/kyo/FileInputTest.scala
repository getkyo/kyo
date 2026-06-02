package kyo

import kyo.Browser.*
import kyo.UI.*
import kyo.UI.Ast.*
import scala.language.implicitConversions

class FileInputTest extends UITest:

    "file type" in run {
        withUI(UI.div(UI.fileInput.id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "type", "file").andThen(succeed)
        }
    }

    "file accept csv" in run {
        withUI(UI.div(UI.fileInput.accept(FileAccept.Extension(".csv")).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", ".csv").andThen(succeed)
        }
    }

    "file accept multiple" in run {
        withUI(UI.div(UI.fileInput.accept(FileAccept.Image(ImageExt.Jpeg), FileAccept.Image(ImageExt.Png)).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", ".jpg,.png").andThen(succeed)
        }
    }

    "file disabled" in run {
        withUI(UI.div(UI.fileInput.disabled(true).id("f"))) {
            Browser.assertDisabled(Selector.id("f")).andThen(succeed)
        }
    }

    "file disabled signal toggle" in run {
        val app: UI < Async =
            for disabled <- Signal.initRef(true)
            yield UI.div(
                disabled.map(d => UI.fileInput.id("f").disabled(d)),
                UI.button("Enable").id("en").onClick(disabled.set(false))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("f"))
                _ <- Browser.click(Selector.id("en"))
                _ <- Browser.assertEnabled(Selector.id("f"))
            yield succeed
        }
    }

    "file focus" in run {
        withUI(UI.div(UI.fileInput.id("f"))) {
            for
                _ <- Browser.click(Selector.id("f"))
                _ <- Browser.assertVisible(Selector.id("f"))
            yield succeed
        }
    }

    "file onFocus fires" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.fileInput.id("f").onFocus(ref.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("f"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "file onBlur fires" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.fileInput.id("f").onBlur(ref.set(true)),
                UI.button("Other").id("b"),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("f"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "file accept AnyImage" in run {
        withUI(UI.div(UI.fileInput.accept(FileAccept.AnyImage).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", "image/*").andThen(succeed)
        }
    }

    "file accept AnyVideo" in run {
        withUI(UI.div(UI.fileInput.accept(FileAccept.AnyVideo).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", "video/*").andThen(succeed)
        }
    }

    "file accept AnyAudio" in run {
        withUI(UI.div(UI.fileInput.accept(FileAccept.AnyAudio).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", "audio/*").andThen(succeed)
        }
    }

    "file accept Pdf" in run {
        withUI(UI.div(UI.fileInput.accept(FileAccept.Pdf).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", "application/pdf").andThen(succeed)
        }
    }

    "file accept Image Png" in run {
        withUI(UI.div(UI.fileInput.accept(FileAccept.Image(ImageExt.Png)).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", ".png").andThen(succeed)
        }
    }

    "file accept Image Jpeg" in run {
        withUI(UI.div(UI.fileInput.accept(FileAccept.Image(ImageExt.Jpeg)).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", ".jpg").andThen(succeed)
        }
    }

    "file accept MediaType" in run {
        withUI(UI.div(UI.fileInput.accept(FileAccept.MediaType("application/msword")).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", "application/msword").andThen(succeed)
        }
    }

    "file accept raw string does not compile" in {
        assertDoesNotCompile("""UI.fileInput.accept(".csv": String)""")
    }

end FileInputTest
