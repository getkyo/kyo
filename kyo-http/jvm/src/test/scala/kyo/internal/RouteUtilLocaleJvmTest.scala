package kyo.internal

import java.util.Locale
import kyo.*
import kyo.internal.server.*

/** The default locale is a JVM-wide setting, so only the JVM can show that a reader folds header names by it: under Turkish, `I`
  * lowercases to a dotless `ı`. Scala.js and Scala Native have no settable default locale.
  */
class RouteUtilLocaleJvmTest extends kyo.BaseHttpTest:

    private def underLocale[A](locale: Locale)(body: => A): A =
        val saved = Locale.getDefault
        Locale.setDefault(locale)
        try body
        finally Locale.setDefault(saved)
    end underLocale

    "the buffered part reader recognizes uppercase header names under the Turkish locale" in {
        val route = HttpRoute.postRaw("upload").request(_.bodyMultipart)
        val body  =
            "--b\r\nCONTENT-DISPOSITION: form-data; name=\"file\"; filename=\"test.txt\"\r\nCONTENT-TYPE: text/plain\r\n\r\nhello\r\n--b--\r\n"
        val bytes   = Span.fromUnsafe(body.getBytes("UTF-8"))
        val headers = HttpHeaders.empty.add("Content-Type", "multipart/form-data; boundary=b")

        underLocale(Locale.forLanguageTag("tr-TR")) {
            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, headers, bytes) match
                case Result.Success(request) =>
                    val parts = request.fields.dict("body").asInstanceOf[Seq[HttpRequest.Part]]
                    assert(parts.size == 1, s"the part was dropped: its header names were not recognized, parts=$parts")
                    assert(parts(0).name == "file")
                    assert(parts(0).filename == Present("test.txt"))
                    assert(parts(0).contentType == Present("text/plain"))
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }
    }

end RouteUtilLocaleJvmTest
