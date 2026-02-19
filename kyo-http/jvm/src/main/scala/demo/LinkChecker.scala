package demo

import kyo.*

/** Link checker - client-only demo (no server).
  *
  * Fetches a web page, extracts all href links, then checks each link in parallel using HEAD requests. Reports status for each link.
  * Demonstrates pure HttpClient usage, concurrent fan-out with Async.parallel, client filters, and withConfig for timeouts.
  *
  * Usage: Run with a URL as argument (defaults to https://www.scala-lang.org)
  */
object LinkChecker extends KyoApp:

    case class LinkResult(url: String, status: Int, ok: Boolean) derives Schema

    /** Extract href values from HTML using a simple regex. */
    def extractLinks(html: String, baseUrl: String): Seq[String] =
        val pattern = """href=["']([^"']+)["']""".r
        pattern.findAllMatchIn(html).map(_.group(1)).toSeq.distinct
            .map { link =>
                if link.startsWith("http://") || link.startsWith("https://") then link
                else if link.startsWith("//") then "https:" + link
                else if link.startsWith("/") then
                    val uri = new java.net.URI(baseUrl)
                    s"${uri.getScheme}://${uri.getHost}$link"
                else if link.startsWith("#") || link.startsWith("mailto:") || link.startsWith("javascript:") then ""
                else baseUrl.stripSuffix("/") + "/" + link
            }
            .filter(_.startsWith("http"))
            .distinct
    end extractLinks

    /** Check a single link by sending a HEAD request. */
    def checkLink(url: String): LinkResult < (Async & Abort[HttpError]) =
        HttpClient.withConfig(_.timeout(10.seconds).followRedirects(true)) {
            HttpClient.send(HttpRequest.head(url)).map { resp =>
                LinkResult(url, resp.status.code, !resp.status.isError)
            }
        }

    run {
        val targetUrl = "https://www.scala-lang.org"

        for
            _ <- Console.printLine(s"Fetching $targetUrl...")
            html <- HttpClient.withConfig(_.timeout(15.seconds)) {
                HttpClient.send(HttpRequest.get(targetUrl)).map(_.bodyText)
            }
            links = extractLinks(html, targetUrl)
            _ <- Console.printLine(s"Found ${links.size} links. Checking...")
            results <- Async.foreach(links, links.size) { url =>
                Abort.run[HttpError](checkLink(url)).map {
                    case kyo.Result.Success(r) => r
                    case kyo.Result.Failure(e) => LinkResult(url, 0, false)
                    case kyo.Result.Panic(e)   => LinkResult(url, 0, false)
                }
            }
            sorted = results.sortBy(r => (!r.ok, r.url))
            _ <- Kyo.foreach(sorted) { r =>
                val symbol = if r.ok then "OK" else "FAIL"
                Console.printLine(s"  [$symbol] ${r.status} ${r.url}")
            }
            okCount   = results.count(_.ok)
            failCount = results.size - okCount
            _ <- Console.printLine(s"\nDone: $okCount ok, $failCount failed out of ${results.size} links")
        yield ()
        end for
    }
end LinkChecker
