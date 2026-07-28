package kyo

import kyo.*
import kyo.ai.*

/** A cross-platform test-support server implementing the OpenAI and Anthropic completion wire protocols.
  *
  * Built on kyo-http's server, it binds on an OS-assigned ephemeral port within a `Scope` (clean teardown,
  * no blocking) and exposes its base URL so a test can point `Config.apiUrl` at it. It serves
  * `POST /v1/chat/completions` (OpenAI) and `POST /v1/messages` (Anthropic), each reading the raw request
  * JSON, capturing it (so a test can assert the outgoing request DTO shape end to end), and returning the
  * next scripted response a test enqueued. A scripted response is a non-streaming JSON body, an SSE
  * sequence (each chunk emitted as `data: <chunk>`, terminated by `data: [DONE]`) when the request carries
  * `"stream":true`, a non-2xx status with a body, an SSE sequence that stalls after its chunks with no
  * terminator (the streaming deadline fixture), or a request the server never answers (a client-side
  * timeout fixture). Scripting is deterministic and per-test: a test enqueues the exact response it wants
  * before the client call.
  *
  * For an opt-in live path, `proxyToOpenAI` forwards to the real OpenAI endpoint when both
  * `KYO_LLM_LIVE_TESTS` and `OPENAI_API_KEY` are present (read via `kyo.System`); the live tests are
  * skipped by default so the suite stays deterministic and key-free.
  */
final class TestCompletionServer private (
    scripts: AtomicRef[Chunk[TestCompletionServer.Scripted]],
    received: AtomicRef[Chunk[TestCompletionServer.Captured]],
    val baseUrl: String
):

    /** Enqueues a non-streaming JSON response body, returned by the next completion call. */
    def enqueueBody(body: String)(using Frame): Unit < Async =
        scripts.updateAndGet(_.append(TestCompletionServer.Scripted.Body(body))).unit

    /** Enqueues an SSE response (the chunks emitted as `data: <chunk>`, terminated by `data: [DONE]`). */
    def enqueueStream(chunks: Chunk[String])(using Frame): Unit < Async =
        scripts.updateAndGet(_.append(TestCompletionServer.Scripted.Sse(chunks))).unit

    /** Enqueues a non-2xx status response with the given body, returned by the next completion call (the
      * non-streaming route only); drives the client-side status classification and retry paths.
      */
    def enqueueStatus(code: Int, body: String)(using Frame): Unit < Async =
        scripts.updateAndGet(_.append(TestCompletionServer.Scripted.Status(code, body))).unit

    /** Enqueues a request the server never answers: the handler blocks on a latch that is never released,
      * so the connection stays open until the client's own timeout fires. Drives a client-side timeout.
      */
    def enqueueNeverRespond(using Frame): Unit < Async =
        scripts.updateAndGet(_.append(TestCompletionServer.Scripted.Never)).unit

    /** Enqueues a streaming response that emits `chunks` and then stalls with no terminator, so the
      * connection stays open while production stops; drives the streaming deadline.
      */
    def enqueueStreamStall(chunks: Chunk[String])(using Frame): Unit < Async =
        scripts.updateAndGet(_.append(TestCompletionServer.Scripted.SseStall(chunks))).unit

    /** The requests the server received, in order, for asserting the outgoing request DTO shape. */
    def captured(using Frame): Chunk[TestCompletionServer.Captured] < Async =
        received.get

end TestCompletionServer

object TestCompletionServer:

    /** A scripted response: a non-streaming JSON body, an SSE chunk sequence, a non-2xx status with a
      * body, or a request that never gets answered.
      */
    enum Scripted derives CanEqual:
        case Body(json: String)
        case Sse(chunks: Chunk[String])
        case Status(code: Int, json: String)
        case Never
        // Emits its chunks and then stalls without a terminator, the shape of a provider that stops
        // producing mid-stream while the connection stays open.
        case SseStall(chunks: Chunk[String])
    end Scripted

    /** A captured request: the path it hit and the raw request body. */
    case class Captured(path: String, body: String) derives CanEqual

    /** Whether the live/proxy path is enabled (both the flag and the key present, via kyo.System). */
    def liveEnabled(using Frame): Boolean < Sync =
        for
            flag <- System.env[String]("KYO_LLM_LIVE_TESTS")
            key  <- System.env[String]("OPENAI_API_KEY")
        yield flag.isDefined && key.isDefined

    /** Forwards the request body to the real OpenAI endpoint when `liveEnabled`, returning the raw
      * response. Returns the scripted fallback when the live path is not enabled.
      */
    def proxyToOpenAI(path: String, body: String, fallback: String)(using Frame): String < (Async & Abort[HttpException]) =
        liveEnabled.map {
            case false => fallback
            case true =>
                System.env[String]("OPENAI_API_KEY").map {
                    case Absent => fallback
                    case Present(key) =>
                        val url = s"https://api.openai.com/v1/${path.stripPrefix("v1/")}"
                        HttpClient.postText(
                            url,
                            body,
                            Seq("content-type" -> "application/json", "Authorization" -> s"Bearer $key")
                        )
                }
        }

    /** Binds a NON-STREAMING server (plain-JSON completion responses, read by the client's `postText` path)
      * on an ephemeral port within the enclosing `Scope` and runs `f` with the handle. Used by the
      * non-streaming completion/eval/thought tests.
      */
    def run[A, S](f: TestCompletionServer => A < S)(using Frame): A < (S & Async & Scope & Abort[HttpBindException]) =
        bind(streaming = false)(f)

    /** Binds a STREAMING server (SSE completion responses on both `/v1/chat/completions` and `/v1/messages`,
      * read by the client's `sendWith` SSE path) on an ephemeral port within the enclosing `Scope`. Used by
      * the streaming test.
      */
    def runStreaming[A, S](f: TestCompletionServer => A < S)(using Frame): A < (S & Async & Scope & Abort[HttpBindException]) =
        bind(streaming = true)(f)

    private def bind[A, S](streaming: Boolean)(f: TestCompletionServer => A < S)(using
        Frame
    ): A < (S & Async & Scope & Abort[HttpBindException]) =
        for
            scripts  <- AtomicRef.init(Chunk.empty[Scripted])
            received <- AtomicRef.init(Chunk.empty[Captured])
            handlers =
                if streaming then
                    Seq(sseRoute("v1/chat/completions", scripts, received), sseRoute("v1/messages", scripts, received))
                else
                    Seq(jsonRoute("v1/chat/completions", scripts, received), jsonRoute("v1/messages", scripts, received))
            result <- HttpServer.initWith(HttpServerConfig.default)(handlers*) { server =>
                val handle = new TestCompletionServer(scripts, received, s"http://127.0.0.1:${server.port}/v1")
                f(handle)
            }
        yield result

    // Atomically pops the next scripted response (getAndUpdate returns the pre-update chunk; the head of
    // that is the popped element). The scripting is single-producer/single-consumer per test, so one
    // getAndUpdate is a correct atomic pop.
    private def popNext(scripts: AtomicRef[Chunk[Scripted]])(using Frame): Maybe[Scripted] < Async =
        scripts.getAndUpdate(_.drop(1)).map(_.headMaybe)

    // The non-streaming endpoint: capture the request, then respond per the next scripted entry. A Never
    // entry blocks on a latch that is never released (an Async suspension, no thread blocked), driving a
    // client-side timeout; with no script queued it defaults to an empty-choices OpenAI body.
    private def jsonRoute(
        path: String,
        scripts: AtomicRef[Chunk[Scripted]],
        received: AtomicRef[Chunk[Captured]]
    )(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.postRaw(path).request(_.bodyText).response(_.bodyText).handler { req =>
            received.getAndUpdate(_.append(Captured(path, req.fields.body))).andThen {
                popNext(scripts).map {
                    case Present(Scripted.Status(code, body)) => HttpResponse(HttpStatus(code)).addField("body", body)
                    // A stall script reaching the non-streaming route means the test bound the wrong server;
                    // holding the connection surfaces that as the caller's timeout rather than a handler panic.
                    case Present(Scripted.Never) | Present(Scripted.SseStall(_)) =>
                        Latch.init(1).map(_.await).andThen(HttpResponse.ok(""))
                    case Present(Scripted.Body(b)) => HttpResponse.ok(b)
                    case Present(Scripted.Sse(cs)) => HttpResponse.ok(cs.headMaybe.getOrElse("""{"choices":[]}"""))
                    case Absent                    => HttpResponse.ok("""{"choices":[]}""")
                }
            }
        }

    // The streaming endpoint: capture the request, emit the next scripted SSE chunks as `data: <chunk>`
    // events, then the provider's terminator. OpenAI's chat-completions stream ends with `data: [DONE]`;
    // Anthropic's messages stream has no `[DONE]` and ends at a `message_stop` event, so the terminator is
    // chosen by path (the Anthropic parser would reject a `[DONE]` line).
    private def sseRoute(
        path: String,
        scripts: AtomicRef[Chunk[Scripted]],
        received: AtomicRef[Chunk[Captured]]
    )(using Frame): HttpHandler[?, ?, ?] =
        val terminator = if path.contains("messages") then """{"type":"message_stop"}""" else "[DONE]"
        HttpRoute.postRaw(path).request(_.bodyText).response(_.bodySseText).handler { req =>
            received.getAndUpdate(_.append(Captured(path, req.fields.body))).andThen {
                popNext(scripts).map {
                    case Present(Scripted.Status(code, body)) =>
                        HttpResponse(HttpStatus(code)).addField("body", Stream.init(Chunk(body)).map(HttpSseEvent(_)))
                    case Present(Scripted.Never) =>
                        Latch.init(1).map(_.await).andThen(HttpResponse.ok.addField("body", Stream.empty[HttpSseEvent[String]]))
                    case Present(Scripted.SseStall(cs)) =>
                        // The chunks reach the client, then production stops with no terminator: the
                        // stream stays open and the consumer waits forever without a deadline.
                        val events = Stream.init(cs).map(HttpSseEvent(_)).concat(
                            Stream.unwrap(Latch.init(1).map(_.await).andThen(Stream.empty[HttpSseEvent[String]]))
                        )
                        HttpResponse.ok.addField("body", events)
                    case other =>
                        val chunks = other match
                            case Present(Scripted.Sse(cs)) => cs.append(terminator)
                            case Present(Scripted.Body(b)) => Chunk(b, terminator)
                            case _                         => Chunk(terminator)
                        HttpResponse.ok.addField("body", Stream.init(chunks).map(HttpSseEvent(_)))
                }
            }
        }
    end sseRoute

end TestCompletionServer
