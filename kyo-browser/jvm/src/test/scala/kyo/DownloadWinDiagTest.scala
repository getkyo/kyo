package kyo

import kyo.internal.BrowserTab

// Scratch diagnostic for the Windows download investigation: drives the current
// Page.setDownloadBehavior path with filesystem forensics, then the Browser-domain
// allowAndName variant, printing events and directory listings. Dev artifact,
// removed before the change ships.
class DownloadWinDiagTest extends BrowserTest:

    override def timeout = 90.seconds

    final private case class BrowserSetDownloadParams(
        behavior: String,
        downloadPath: Maybe[String] = Absent,
        browserContextId: Maybe[String] = Absent,
        eventsEnabled: Maybe[Boolean] = Absent
    ) derives Schema

    private def listDir(label: String, dir: String): Unit =
        val files = Option(new java.io.File(dir).listFiles()).map(_.toList).getOrElse(Nil)
        println(s"[diag] $label dir=$dir entries=${files.map(f => s"${f.getName}:${f.length}")}")

    private def scanTmpForDownloads(label: String): Unit =
        val tmp  = new java.io.File(java.lang.System.getProperty("java.io.tmpdir"))
        val hits = scala.collection.mutable.ListBuffer.empty[String]
        def walk(dir: java.io.File, depth: Int): Unit =
            if depth <= 3 then
                Option(dir.listFiles()).foreach(_.foreach { f =>
                    val name = f.getName
                    if name.endsWith(".crdownload") || name.contains("diag-") then
                        hits += s"${f.getAbsolutePath}:${f.length}"
                    if f.isDirectory && (name.startsWith("kyo-") || name.contains("chrome") || name.contains("Download")) then
                        walk(f, depth + 1)
                })
        walk(tmp, 0)
        println(s"[diag] $label tmp scan hits=${hits.toList}")
    end scanTmpForDownloads

    "diag A: page-domain allow with forensics" in {
        withBrowser {
            for
                tempPath <- Path.tempDir("kyo-diag-dl-a-")
                dir = tempPath.toString
                events <- AtomicRef.init(Chunk.empty[Browser.DownloadEvent])
                _      <- Browser.allowDownloads(dir)
                _ <- Browser.onDownload(ev => events.updateAndGet(_ :+ ev).unit) {
                    Browser.goto(page("<a id='dl' href='data:text/plain,diag-content-a' download='diag-a.txt'>dl</a>"))
                        .andThen(Browser.click(Browser.Selector.id("dl")))
                        .andThen(Async.delay(10.seconds)(Kyo.unit))
                }
                evs <- events.get
            yield
                println(s"[diag] A events=$evs")
                listDir("A toPath", dir)
                scanTmpForDownloads("A")
                assert(dir.nonEmpty)
        }
    }

    // Browser is opaquely Env[BrowserTab] & Async; the cast bridges the opaque boundary for
    // this scratch probe only.
    private def useTab(f: BrowserTab => Unit < (Async & Abort[Any])): Unit < (Browser & Abort[Any]) =
        Env.use[internal.BrowserTab](f).asInstanceOf[Unit < (Browser & Abort[Any])]

    "diag C: page-domain allow with native separators" in {
        withBrowser {
            for
                tempPath <- Path.tempDir("kyo-diag-dl-c-")
                dir = tempPath.toString.replace('/', java.io.File.separatorChar)
                _ <- Browser.allowDownloads(dir)
                _ <- Browser.goto(page("<a id='dl' href='data:text/plain,diag-content-c' download='diag-c.txt'>dl</a>"))
                _ <- Browser.click(Browser.Selector.id("dl"))
                _ <- Async.delay(10.seconds)(Kyo.unit)
            yield
                listDir("C toPath", dir)
                assert(dir.nonEmpty)
        }
    }

    "diag D: page-domain allow with resolved long-form native path" in {
        withBrowser {
            for
                tempPath <- Path.tempDir("kyo-diag-dl-d-")
                dir = new java.io.File(tempPath.toString).getCanonicalFile.getAbsolutePath
                _ <- Browser.allowDownloads(dir)
                _ <- Browser.goto(page("<a id='dl' href='data:text/plain,diag-content-d' download='diag-d.txt'>dl</a>"))
                _ <- Browser.click(Browser.Selector.id("dl"))
                _ <- Async.delay(10.seconds)(Kyo.unit)
            yield
                listDir("D toPath", dir)
                assert(dir.nonEmpty)
        }
    }

    "diag B: browser-domain allowAndName" in {
        withBrowser {
            for
                tempPath <- Path.tempDir("kyo-diag-dl-b-")
                dir = tempPath.toString
                _ <- useTab { tab =>
                    val params = BrowserSetDownloadParams("allowAndName", Present(dir), tab.browserContextId, Present(true))
                    tab.backend.sendUnit[BrowserSetDownloadParams]("Browser.setDownloadBehavior", params)
                }
                _ <- Browser.goto(page("<a id='dl' href='data:text/plain,diag-content-b' download='diag-b.txt'>dl</a>"))
                _ <- Browser.click(Browser.Selector.id("dl"))
                _ <- Async.delay(10.seconds)(Kyo.unit)
            yield
                listDir("B toPath", dir)
                scanTmpForDownloads("B")
                assert(dir.nonEmpty)
        }
    }
end DownloadWinDiagTest
