package kyo

import kyo.Browser.*

/** `onFileSelect` delivers full per-file metadata (name/size/MIME/content) for all selected files, while the
  * legacy `onChange`-only path stays content-only for a single file.
  *
  * Drives the server-push transport in real Chrome. Files are injected by constructing a `DataTransfer` with real
  * `File` objects and assigning `input.files`, then dispatching a `change` event, the path a real file picker produces.
  */
class FileSelectScenarioItTest extends UITest:

    "file input renders multiple attribute" in {
        withUI(UI.div(UI.fileInput.id("f").multiple(true))) {
            Browser.assertAttribute(Selector.id("f"), "multiple", "").unit
        }
    }

    "onFileSelect declares the fileselect token" in {
        withUI(UI.div(UI.fileInput.id("f").onFileSelect(_ => Kyo.unit))) {
            Browser.assertAttributeSatisfies(Selector.id("f"), "data-kyo-ev", "has fileselect")(_.contains("fileselect")).unit
        }
    }

    "onFileSelect receives name, size, MIME type and content for every selected file" in {
        val app: UI < Async =
            for got <- Signal.initRef("")
            yield UI.div(
                UI.fileInput.id("f").multiple(true).onFileSelect(files =>
                    got.set(files.map(p => s"${p.name}:${p.size}:${p.mimeType}:${p.content}").mkString("|"))
                ),
                got.map(v => UI.span(v).id("out"))
            )
        withUI(app) {
            for
                _ <- Browser.evalDiscard(
                    "var inp=document.getElementById('f');var dt=new DataTransfer();dt.items.add(new File(['hello'],'a.txt',{type:'text/plain'}));dt.items.add(new File(['worldwide'],'b.csv',{type:'text/csv'}));inp.files=dt.files;inp.dispatchEvent(new Event('change',{bubbles:true}));"
                )
                _ <- Browser.assertText(Selector.id("out"), "a.txt:5:text/plain:hello|b.csv:9:text/csv:worldwide")
            yield ()
        }
    }

    "legacy onChange still receives content-only for a single file" in {
        val app: UI < Async =
            for got <- Signal.initRef("")
            yield UI.div(
                UI.fileInput.id("f").onChange(v => got.set(v)),
                got.map(v => UI.span(v).id("out"))
            )
        withUI(app) {
            for
                _ <- Browser.evalDiscard(
                    "var inp=document.getElementById('f');var dt=new DataTransfer();dt.items.add(new File(['legacy content'],'c.txt',{type:'text/plain'}));inp.files=dt.files;inp.dispatchEvent(new Event('change',{bubbles:true}));"
                )
                _ <- Browser.assertText(Selector.id("out"), "legacy content")
            yield ()
        }
    }

end FileSelectScenarioItTest
