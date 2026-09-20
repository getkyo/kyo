package kyo

import kyo.*

/** A cross-platform test-support server implementing TypeSafe AI's System One wire (`POST /v1/systemone`),
  * in the style of [[TestCompletionServer]].
  *
  * Binds on an ephemeral port within a `Scope` and exposes its base URL so a test can point
  * `DeciderConfig.apiUrl` at it. Every request's raw body is captured, so a test asserts the outgoing
  * request shape end to end, and the reply is the next scripted response: a JSON body, a non-2xx status
  * with a body and optional headers, or a request the server never answers (a client-side timeout
  * fixture). With nothing scripted it answers the request's questions with fixed answers, so a test
  * that only cares about the request shape needs no script.
  */
final class TestDeciderServer private (
    scripts: AtomicRef[Chunk[TestDeciderServer.Scripted]],
    received: AtomicRef[Chunk[String]],
    val baseUrl: String
):

    /** Enqueues a JSON response body, returned by the next call. */
    def enqueueBody(body: String)(using Frame): Unit < Async =
        scripts.updateAndGet(_.append(TestDeciderServer.Scripted.Body(body))).unit

    /** Enqueues a non-2xx status with a body and response headers, returned by the next call. */
    def enqueueStatus(code: Int, body: String, headers: Seq[(String, String)] = Seq.empty)(using Frame): Unit < Async =
        scripts.updateAndGet(_.append(TestDeciderServer.Scripted.Status(code, body, headers))).unit

    /** Enqueues a request the server never answers, driving a client-side timeout. */
    def enqueueNeverRespond(using Frame): Unit < Async =
        scripts.updateAndGet(_.append(TestDeciderServer.Scripted.Never)).unit

    /** The raw request bodies the server received, in order. */
    def captured(using Frame): Chunk[String] < Async =
        received.get

end TestDeciderServer

object TestDeciderServer:

    enum Scripted derives CanEqual:
        case Body(json: String)
        case Status(code: Int, json: String, headers: Seq[(String, String)])
        case Never
    end Scripted

    /** Binds the server on an ephemeral port within the enclosing `Scope` and runs `f` with the handle. */
    def run[A, S](f: TestDeciderServer => A < S)(using Frame): A < (S & Async & Scope & Abort[HttpBindException]) =
        // Every leaf that scripts a decider needs a decider to script, which is a server on a port. A browser page has
        // no port to bind, so the leaf has nothing to run rather than something to fail. Same pre-flight as
        // TestCompletionServer's, which this fixture otherwise follows.
        if kyo.internal.Platform.isBrowser then
            Sync.defer(throw new kyo.test.TestCancelled("this test scripts a decider server, and this host is a browser page"))
        else bindHere(f)

    private def bindHere[A, S](f: TestDeciderServer => A < S)(using Frame): A < (S & Async & Scope & Abort[HttpBindException]) =
        for
            scripts  <- AtomicRef.init(Chunk.empty[Scripted])
            received <- AtomicRef.init(Chunk.empty[String])
            result   <- HttpServer.initWith(HttpServerConfig.default)(route(scripts, received)) { server =>
                f(new TestDeciderServer(scripts, received, s"http://127.0.0.1:${server.port}/v1"))
            }
        yield result

    private def popNext(scripts: AtomicRef[Chunk[Scripted]])(using Frame): Maybe[Scripted] < Async =
        scripts.getAndUpdate(_.drop(1)).map(_.headMaybe)

    private def route(scripts: AtomicRef[Chunk[Scripted]], received: AtomicRef[Chunk[String]])(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.postRaw("v1/systemone").request(_.bodyText).response(_.bodyText).handler { req =>
            received.getAndUpdate(_.append(req.fields.body)).andThen {
                popNext(scripts).map {
                    case Present(Scripted.Status(code, body, headers)) =>
                        headers.foldLeft(HttpResponse(HttpStatus(code)))((r, h) => r.addHeader(h._1, h._2)).addField("body", body)
                    case Present(Scripted.Never)   => Latch.init(1).map(_.await).andThen(HttpResponse.ok(""))
                    case Present(Scripted.Body(b)) => HttpResponse.ok(b)
                    case Absent                    => defaultAnswer(req.fields.body)
                }
            }
        }

    /** Answers each question in the request with a fixed, decodable answer of its kind. A request the
      * fixture cannot read is answered with a 400 naming the fixture, so a test bug reads as one rather
      * than as a decoder failure.
      */
    private[kyo] def defaultAnswer(requestBody: String)(using Frame): HttpResponse["body" ~ String] =
        Json.decode[Req](requestBody) match
            case Result.Success(req) =>
                HttpResponse.ok(s"""{"model":"jev-1.13.0","answers":{${answersFor(req)}},"usage":{"input_tokens":10,"output_tokens":2}}""")
            case other =>
                HttpResponse(HttpStatus(400)).addField("body", s"""{"detail":"TestDeciderServer could not read the request: $other"}""")

    private[kyo] case class Q(`type`: String, criteria: Maybe[Structure.Value] = Absent) derives Schema
    private[kyo] case class Req(questions: OrderedDict[String, Q]) derives Schema

    // The probabilities are spelled as literals, never interpolated Doubles: Scala.js renders 1.0 as "1",
    // so an interpolated Double would give the fixture a different wire shape per platform.
    private def answersFor(req: Req): String =
        def peak(i: Int): String = if i == 0 then "1.0" else "0.0"
        req.questions.toChunk.map { (id, q) =>
            val answer = q.`type` match
                case "noul"   => """{"type":"noul","noul":0.9}"""
                case "choice" =>
                    val keys = q.criteria match
                        case Present(Structure.Value.Record(fields)) => fields.map(_._1)
                        case _                                       => Chunk.empty
                    val probabilities = keys.zipWithIndex.map((k, i) => s""""$k":${peak(i)}""").mkString(",")
                    s"""{"type":"choice","choice":"${keys.headMaybe.getOrElse(
                            ""
                        )}","confidence":1.0,"probabilities":{$probabilities}}"""
                case _ =>
                    val n = q.criteria match
                        case Present(Structure.Value.Sequence(levels)) => levels.size
                        case _                                         => 0
                    val probabilities = (0 until n).map(i => s""""$i":${peak(i)}""").mkString(",")
                    s"""{"type":"score","score":0.0,"confidence":1.0,"legend":{},"probabilities":{$probabilities}}"""
            s""""$id":$answer"""
        }.mkString(",")
    end answersFor

end TestDeciderServer
