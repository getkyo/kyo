package demo

import kyo.*

/** Demo 2: Wikipedia article → offline kit (text + infobox PNG + full PDF).
  *
  * Navigates to a stable Wikipedia article and produces three on-disk artifacts:
  *   - `summary.txt`: readable content, trimmed
  *   - `infobox.png`: element screenshot of the right-hand infobox
  *   - `article.pdf`: full-page PDF
  *
  * The challenge is binary plumbing: `screenshotElement` returns an `Image` (PNG bytes), `pdf` returns raw `Span[Byte]`, `readableContent`
  * is a `String`. This demo wires all three to disk through `Path.writeBytes` / `Image.writeFileBinary` and renders the PNG inline via the
  * iTerm2 protocol for the live run.
  */
final class WikipediaKitDemo extends BrowserDemo[WikipediaKitDemo.KitResult]("wikipedia-kit"):

    import WikipediaKitDemo.KitResult

    private val articleUrl = "https://en.wikipedia.org/wiki/Kyoto"

    def flow(using Frame): KitResult < (Browser & Async & Scope & Abort[Throwable]) =
        for
            outputDir <- Path.tempDir("kyo-browser-wikipedia-kit-")

            _ <- step(1, s"Navigate to $articleUrl")
            _ <- Browser.goto(articleUrl)
            _ <- logState()
            _ <- snapshot()

            _       <- step(2, "Extract readable article content")
            content <- Browser.readableContent
            _       <- log(s"readable length = ${content.length} chars")

            _          <- step(3, "Screenshot the infobox element")
            infoboxImg <- Browser.screenshotElement(Browser.Selector.css("table.infobox"))
            _          <- log(s"infobox png  = ${infoboxImg.binary.size} bytes")

            _   <- step(4, "Render full-page PDF")
            pdf <- Browser.pdf
            _   <- log(s"pdf bytes    = ${pdf.size}")

            _ <- step(5, s"Write artifacts to ${outputDir.toString}/")
            _ <- (outputDir / "summary.txt").write(content.take(4000))
            _ <- infoboxImg.writeFileBinary(outputDir / "infobox.png")
            _ <- (outputDir / "article.pdf").writeBytes(pdf)
            _ <- log(s"wrote three artifacts to ${outputDir.toString}/")

            _        <- step(6, "Render infobox PNG inline to the terminal")
            rendered <- infoboxImg.renderToConsole(charsWidth = 40)
            _ <- rendered match
                case Present(s) => Console.printLine(s)
                case Absent     => Console.printLine(s"    (PNG: ${infoboxImg.binary.size} bytes; iTerm2/Kitty needed for inline render)")
        yield KitResult(content.length, infoboxImg.binary.size, pdf.size)
    end flow

    override def validate(result: KitResult): Maybe[String] =
        if result.contentLength < 500 then Present(s"readable content suspiciously short (${result.contentLength} chars)")
        else if result.infoboxBytes < 1000 then Present(s"infobox PNG too small (${result.infoboxBytes} bytes)")
        else if result.pdfBytes < 10_000 then Present(s"PDF too small (${result.pdfBytes} bytes)")
        else Absent

end WikipediaKitDemo

object WikipediaKitDemo:
    case class KitResult(contentLength: Int, infoboxBytes: Int, pdfBytes: Int) derives CanEqual

object WikipediaKitDemoApp extends KyoApp:
    run {
        (new WikipediaKitDemo).runDemo
    }
end WikipediaKitDemoApp
