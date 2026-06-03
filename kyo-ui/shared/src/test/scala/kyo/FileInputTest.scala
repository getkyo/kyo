package kyo

import kyo.Browser.*
import kyo.UI.*
import kyo.UI.Ast.*
import scala.language.implicitConversions

class FileInputTest extends UITest:

    "file type" in {
        withUI(UI.div(UI.fileInput.id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "type", "file").unit
        }
    }

    "file accept csv" in {
        withUI(UI.div(UI.fileInput.accept(FileAccept.Extension(".csv")).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", ".csv").unit
        }
    }

    "file accept multiple" in {
        withUI(UI.div(UI.fileInput.accept(FileAccept.Image(ImageExt.Jpeg), FileAccept.Image(ImageExt.Png)).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", ".jpg,.png").unit
        }
    }

    "file disabled" in {
        withUI(UI.div(UI.fileInput.disabled(true).id("f"))) {
            Browser.assertDisabled(Selector.id("f")).unit
        }
    }

    "file disabled signal toggle" in {
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
            yield ()
        }
    }

    "file focus" in {
        withUI(UI.div(UI.fileInput.id("f"))) {
            for
                _ <- Browser.click(Selector.id("f"))
                _ <- Browser.assertVisible(Selector.id("f"))
            yield ()
        }
    }

    "file onFocus fires" in {
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
            yield ()
        }
    }

    "file onBlur fires" in {
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
            yield ()
        }
    }

    "file accept AnyImage" in {
        withUI(UI.div(UI.fileInput.accept(FileAccept.AnyImage).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", "image/*").unit
        }
    }

    "file accept AnyVideo" in {
        withUI(UI.div(UI.fileInput.accept(FileAccept.AnyVideo).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", "video/*").unit
        }
    }

    "file accept AnyAudio" in {
        withUI(UI.div(UI.fileInput.accept(FileAccept.AnyAudio).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", "audio/*").unit
        }
    }

    "file accept Pdf" in {
        withUI(UI.div(UI.fileInput.accept(FileAccept.Pdf).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", "application/pdf").unit
        }
    }

    "file accept Image Png" in {
        withUI(UI.div(UI.fileInput.accept(FileAccept.Image(ImageExt.Png)).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", ".png").unit
        }
    }

    "file accept Image Jpeg" in {
        withUI(UI.div(UI.fileInput.accept(FileAccept.Image(ImageExt.Jpeg)).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", ".jpg").unit
        }
    }

    "file accept MediaType" in {
        withUI(UI.div(UI.fileInput.accept(FileAccept.MediaType("application/msword")).id("f"))) {
            Browser.assertAttribute(Selector.id("f"), "accept", "application/msword").unit
        }
    }

    "file accept raw string does not compile" in {
        typeCheckFailure("""UI.fileInput.accept(".csv": String)""")
    }

end FileInputTest
