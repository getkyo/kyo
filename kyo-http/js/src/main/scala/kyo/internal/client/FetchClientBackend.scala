package kyo.internal.client

import kyo.*
import kyo.internal.PlatformJs
import kyo.internal.server.RouteUtil
import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/** The client a browser page gets, over the page's own `fetch`.
  *
  * A page has no socket, so the socket backend's whole layer (connect, HTTP/1 framing, the connection pool) has no counterpart here: the
  * browser owns the connection, its reuse, TLS, the framing and the redirect chain, and hands back a response. What kyo keeps is the part
  * above the wire, which [[PolicyClientBackend]] carries: the base URL, the retry schedule, the request timeout and the filters.
  *
  * Redirects are the browser's here. `fetch` with `redirect: "manual"` answers a cross-origin redirect with an opaque response that carries
  * neither status nor `Location`, so kyo's own redirect chain cannot run in a page; the browser follows them instead and returns the final
  * response, which is why a 3xx never reaches the policy layer's redirect step.
  *
  * What a page cannot do at all fails with [[kyo.HttpUnsupportedOnHostException]] rather than silently doing something else: a unix socket,
  * a raw connection, a trust store of kyo's own, and the header names the fetch specification reserves for the browser, which it drops from
  * a request without saying so.
  */
final private[kyo] class FetchClientBackend extends PolicyClientBackend:

    import FetchClientBackend.*

    private var closedFlag = false

    override protected def dispatchWith[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        refusedConfig(config) match
            case Present(ex) => Abort.fail(ex)
            // A closed client sends nothing more, as it does over a socket, where closing shuts the pool and closes every
            // connection a later request would open.
            case Absent if closedFlag => Abort.fail(HttpConnectionClosedException())
            case Absent =>
                RouteUtil.multipartBoundaryForRequest(route, request).map { boundary =>
                    RouteUtil.encodeRequestWithBoundary(route, request, boundary)(
                        onEmpty = (path, headers) => send(route, request, path, headers, Absent, config.maxResponseLength)(f),
                        onBuffered =
                            (path, headers, body) => send(route, request, path, headers, Present(body), config.maxResponseLength)(f),
                        onStreaming = (path, headers, _) =>
                            // A page can stream a request body only where `duplex: "half"` is available, and only over HTTP/2 in some
                            // browsers. Until that is probed and mapped, refusing says so rather than buffering a stream the caller
                            // meant to keep open.
                            Abort.fail(HttpUnsupportedOnHostException("A streamed request body"))
                    )
                }

    /** The parts of a request's configuration a page cannot honor.
      *
      * The redirect settings are here because `fetch` offers no way to keep them. `redirect: "manual"` answers a redirect with an opaque
      * response carrying neither the status nor the `Location`, so a program that turned redirects off to read the 3xx itself would be
      * handed nothing to read, and the chain length is the browser's own rather than the one the config names.
      */
    private def refusedConfig(config: HttpClientConfig)(using Frame): Maybe[HttpException] =
        if config.tls != HttpTlsConfig.default then Present(HttpUnsupportedOnHostException("A TLS configuration of its own"))
        else if !config.followRedirects then
            Present(HttpUnsupportedOnHostException("Leaving a redirect for the program, which the browser follows itself,"))
        else if config.maxRedirects != defaultMaxRedirects then
            Present(HttpUnsupportedOnHostException("A redirect limit of its own, which the browser keeps for itself,"))
        else Absent

    private def send[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        path: String,
        headers: HttpHeaders,
        body: Maybe[Span[Byte]],
        maxResponseLength: Int
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        request.url.unixSocket match
            case Present(_) => Abort.fail(HttpUnsupportedOnHostException("A unix socket"))
            case Absent =>
                reservedHeader(headers) match
                    case Present(name) =>
                        Abort.fail(HttpUnsupportedOnHostException(s"The $name header, which the browser sets itself,"))
                    case Absent =>
                        val url = requestUrl(request.url, path)
                        fetch(request.url, url, request.method.name, headers, body).map { response =>
                            val code = status(response)
                            // A streaming route whose response is an error reads buffered, the same as over a socket: a service that
                            // answers a stream-typed endpoint with a small JSON error would otherwise leave that body trapped in a
                            // stream nobody drains, and it is the only diagnostic the caller has.
                            if RouteUtil.isStreamingResponse(route) && code < 400 then
                                RouteUtil.decodeStreamingResponseWith(
                                    route,
                                    HttpStatus(code),
                                    responseHeaders(response),
                                    bodyStream(response, request.url),
                                    route.method.name,
                                    request.url
                                )(f)
                            else
                                readBody(response, request.url).map { bytes =>
                                    // The cap the caller configured still holds in a page: the browser has the bytes, and this is
                                    // where a buffered body would otherwise be handed on past the size it agreed to take.
                                    if bytes.size > maxResponseLength then
                                        Abort.fail(HttpPayloadTooLargeException(bytes.size, maxResponseLength))
                                    else
                                        RouteUtil.decodeBufferedResponseWith(
                                            route,
                                            HttpStatus(code),
                                            responseHeaders(response),
                                            bytes,
                                            route.method.name,
                                            request.url
                                        )(f)
                                }
                            end if
                        }

    /** The URL to fetch: the request's own when it names a host, and the page-relative path when it does not. */
    private def requestUrl(url: HttpUrl, path: String): String =
        url.scheme match
            case Present(scheme) if url.host.nonEmpty =>
                val port          = url.port
                val isDefaultPort = if url.ssl then port == 443 else port == 80
                val authority     = if isDefaultPort then url.host else s"${url.host}:$port"
                s"$scheme://$authority$path"
            case _ => path

    private def fetch(target: HttpUrl, url: String, method: String, headers: HttpHeaders, body: Maybe[Span[Byte]])(using
        Frame
    ): js.Dynamic < (Async & Abort[HttpException]) =
        // Read from `globalThis`: a host that does not declare `fetch` throws a ReferenceError at a bare read, before any check.
        if PlatformJs.jsGlobal("fetch").isEmpty then Abort.fail(HttpUnsupportedOnHostException("fetch"))
        else
            Sync.defer {
                val init      = js.Dynamic.literal(method = method, redirect = "follow")
                val jsHeaders = js.Dynamic.literal()
                headers.foreach((name, value) => jsHeaders.updateDynamic(name)(value))
                init.updateDynamic("headers")(jsHeaders)
                body.foreach(span => init.updateDynamic("body")(new Uint8Array(span.toArray.toTypedArray.buffer)))
                // Called on `globalThis`, the receiver a page's `fetch` requires.
                js.Dynamic.global.globalThis.applyDynamic("fetch")(url, init).asInstanceOf[js.Promise[js.Dynamic]]
            }.map(promise => awaited(promise, target))

    /** Awaits a browser promise, with a rejection as this client's typed connect failure.
      *
      * A fetch rejects with a `TypeError` that names no cause: a refused connection, a DNS failure, a CORS rejection and a certificate the
      * browser will not trust all arrive the same way, by design, so the failure says what kyo knows rather than inventing a distinction.
      * The rejection reaches kyo as a panic, because a `Future` carries no typed failure for `Async.fromFuture` to keep, and it is turned
      * back into one here. A panic that is not the browser's rejection stays a panic: that would be a fault in this file.
      */
    private def awaited[A](promise: js.Promise[A], target: HttpUrl)(using Frame): A < (Async & Abort[HttpException]) =
        Abort.run[Throwable](Async.fromFuture(promise.toFuture)).map {
            case Result.Success(value)                   => value
            case Result.Failure(t)                       => Abort.fail(HttpConnectException(target.host, target.port, t))
            case Result.Panic(t: js.JavaScriptException) => Abort.fail(HttpConnectException(target.host, target.port, t))
            case Result.Panic(t)                         => Abort.panic(t)
        }

    /** The response body as it arrives, read through the `ReadableStream` the fetch specification gives it.
      *
      * A body the browser reports as absent (a 204, a `HEAD`, a response the page was handed from cache without one) has no reader to open,
      * and reads as an empty stream rather than a failure.
      */
    private def bodyStream(response: js.Dynamic, target: HttpUrl)(using Frame): Stream[Span[Byte], Async] =
        Stream[Span[Byte], Async] {
            val open: Maybe[js.Dynamic] < Sync =
                Sync.defer(if js.isUndefined(response.body) || response.body == null then Absent else Present(response.body.getReader()))
            open.map {
                case Absent => Kyo.unit
                case Present(reader) =>
                    Loop.foreach {
                        readChunk(reader, target).map {
                            case Present(span) => Emit.valueWith(Chunk(span))(Loop.continue)
                            case Absent        => Loop.done
                        }
                    }
            }
        }

    /** One read from the body's reader: the bytes it produced, or `Absent` once the body is complete.
      *
      * A stream carries no typed failure, so a body that stops mid-transfer arrives as a panic. That is louder than the alternative, which
      * is to end the stream and hand the caller a body it cannot tell from a complete one.
      */
    private def readChunk(reader: js.Dynamic, target: HttpUrl)(using Frame): Maybe[Span[Byte]] < Async =
        Sync.defer(reader.read().asInstanceOf[js.Promise[js.Dynamic]]).map { promise =>
            Abort.run[HttpException](awaited(promise, target)).map {
                case Result.Success(chunk) =>
                    if chunk.done.asInstanceOf[Boolean] then Absent
                    else
                        val bytes = chunk.value.asInstanceOf[Uint8Array]
                        // The chunk is a view over a buffer the browser owns and may reuse, and it rarely starts at its beginning, so the
                        // bytes are copied out of the view's own window rather than read from the buffer's start.
                        Present(Span.from(new Int8Array(bytes.buffer, bytes.byteOffset, bytes.length).toArray))
                case Result.Failure(e) => Abort.panic(e)
                case Result.Panic(t)   => Abort.panic(t)
            }
        }

    private def readBody(response: js.Dynamic, target: HttpUrl)(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        Sync.defer(response.arrayBuffer().asInstanceOf[js.Promise[ArrayBuffer]])
            .map(promise => awaited(promise, target))
            .map(buffer => Span.from(new Int8Array(buffer).toArray))

    /** Opens the page's own `WebSocket` and runs the session over it.
      *
      * The browser's constructor takes a URL and a subprotocol list and nothing else, so a handshake header of any kind, whether the caller
      * set it or a filter did, fails rather than being dropped on the way out. Ping frames are the browser's: it answers a peer's ping
      * itself and offers no way to send one, so a configured ping interval fails too instead of quietly never happening.
      */
    def connectWebSocket[A, S](
        url: HttpUrl,
        headers: HttpHeaders,
        config: HttpWebSocket.Config,
        connectTimeout: Duration,
        clientFilter: HttpFilter.Passthrough[Nothing],
        autoFilters: Boolean
    )(
        f: HttpWebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        if config.autoPingInterval.isDefined then Abort.fail(HttpUnsupportedOnHostException("A WebSocket ping interval"))
        else
            val autoFilter = if autoFilters then HttpFilter.Factory.composedClient else HttpFilter.noop
            val filter     = autoFilter.andThen(clientFilter)
            if filter.eq(HttpFilter.noop) then openSession(url, headers, config, connectTimeout)(f)
            else
                val request = HttpRequest(HttpMethod.GET, url, headers, Record.empty)
                Abort.run[HttpResponse.Halt] {
                    filter[Any, "body" ~ A, HttpException, S](
                        request,
                        (filtered: HttpRequest[Any]) =>
                            openSession(filtered.url, filtered.headers, config, connectTimeout)(f).map { result =>
                                HttpResponse(HttpStatus.SwitchingProtocols).addField("body", result)
                            }
                    ).map(_.fields.body)
                }.map {
                    case Result.Success(value) => value
                    case Result.Failure(halt) =>
                        Abort.fail(HttpStatusException(
                            halt.response.status,
                            HttpMethod.GET.name,
                            url.baseUrl,
                            halt.response.rawBody.getOrElse("")
                        ))
                    case Result.Panic(t) => Abort.panic(t)
                }
            end if

    private def openSession[A, S](
        url: HttpUrl,
        headers: HttpHeaders,
        config: HttpWebSocket.Config,
        connectTimeout: Duration
    )(
        f: HttpWebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        if headers.nonEmpty then
            Abort.fail(HttpUnsupportedOnHostException("A WebSocket carrying request headers, which the browser does not send,"))
        else
            // Both are read from `globalThis`: a host that does not declare one throws a ReferenceError at a bare read, before any check.
            socketUrl(url) match
                case Absent =>
                    Abort.fail(HttpUnsupportedOnHostException("A WebSocket URL missing its scheme or host, which resolves against a page's location,"))
                case Present(target) =>
                    PlatformJs.jsGlobal("WebSocket").toOption match
                        case None              => Abort.fail(HttpUnsupportedOnHostException("WebSocket"))
                        case Some(constructor) => runSession(constructor, target, url, config, connectTimeout)(f)

    /** A session over the host's `WebSocket` constructor, connected to `target`. */
    private def runSession[A, S](
        constructor: js.Dynamic,
        target: String,
        url: HttpUrl,
        config: HttpWebSocket.Config,
        connectTimeout: Duration
    )(
        f: HttpWebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        Channel.initUnscopedWith[HttpWebSocket.Payload](config.bufferSize) { inbound =>
            Channel.initUnscopedWith[HttpWebSocket.Payload](config.bufferSize) { outbound =>
                AtomicRef.initWith(Absent: Maybe[(Int, String)]) { closeReasonRef =>
                    Fiber.Promise.init[Unit, Any].map { peerClosedPromise =>
                        Fiber.Promise.init[Unit, Abort[HttpException]].map { openedPromise =>
                            val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                                closeReasonRef.set(Present((code, reason))).andThen(outbound.closeDiscard)
                            val ws = new HttpWebSocket(inbound, outbound, closeReasonRef, peerClosedPromise, closeFn)
                            connect(constructor, target, url, config, inbound, closeReasonRef, peerClosedPromise, openedPromise).map { socket =>
                                // The socket exists from here on, so its close is ensured from here on: a handshake the server
                                // refuses leaves the browser one to clean up just as a finished session does.
                                Sync.ensure(Sync.defer(closeSocket(socket, 1000, ""))) {
                                    awaitOpen(url, openedPromise, connectTimeout).andThen {
                                        Fiber.initUnscoped(writeLoop(socket, outbound, closeReasonRef)).map { writeFiber =>
                                            Sync.ensure(
                                                writeFiber.interrupt.unit
                                                    .andThen(inbound.closeDiscard)
                                                    .andThen(outbound.closeDiscard)
                                            ) {
                                                f(ws)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    /** Waits for the browser to report the connection open, for as long as the caller allows. */
    private def awaitOpen(url: HttpUrl, opened: Fiber.Promise[Unit, Abort[HttpException]], connectTimeout: Duration)(using
        Frame
    ): Unit < (Async & Abort[HttpException]) =
        if connectTimeout.isFinite then
            Abort.run[Timeout](Async.timeout(connectTimeout)(opened.get)).map {
                case Result.Success(_) => ()
                case Result.Failure(_) => Abort.fail(HttpConnectTimeoutException(url.host, url.port, connectTimeout))
                case Result.Panic(t)   => Abort.panic(t)
            }
        else opened.get

    /** Builds the browser socket and wires its events into the session's channels.
      *
      * Every handler below runs on the page's event loop, outside any kyo context, so each crossing is one unsafe evaluation. An inbound
      * message is handed to `putFiber` rather than `offer`: the browser has already delivered the bytes and cannot be pushed back on, so a
      * message that finds the buffer full waits its turn in arrival order instead of being dropped.
      */
    private def connect(
        constructor: js.Dynamic,
        target: String,
        url: HttpUrl,
        config: HttpWebSocket.Config,
        inbound: Channel[HttpWebSocket.Payload],
        closeReasonRef: AtomicRef[Maybe[(Int, String)]],
        peerClosedPromise: Fiber.Promise[Unit, Any],
        openedPromise: Fiber.Promise[Unit, Abort[HttpException]]
    )(using Frame): js.Dynamic < Sync =
        // Unsafe: the browser calls these handlers with no kyo context, so this is the one crossing point.
        Sync.Unsafe.defer {
            val protocols = js.Array(config.subprotocols*)
            val socket    = js.Dynamic.newInstance(constructor)(target, protocols)
            socket.binaryType = "arraybuffer"
            socket.onopen = { (_: js.Dynamic) =>
                discard(openedPromise.unsafe.complete(Result.succeed(())))
            }: js.Function1[js.Dynamic, Unit]
            socket.onmessage = { (event: js.Dynamic) =>
                payload(event.data, config.maxFrameSize) match
                    case Present(frame) => discard(inbound.unsafe.putFiber(frame))
                    // A frame past the configured limit ends the connection the way the protocol says to, rather than
                    // delivering bytes the caller asked not to receive.
                    case Absent => closeSocket(socket, 1009, "frame too large")
            }: js.Function1[js.Dynamic, Unit]
            socket.onerror = { (_: js.Dynamic) =>
                // The page is told a WebSocket failed and never why: the event carries no code, no status and no reason,
                // by design, so the failure says the connection did not come up and stops there.
                discard(openedPromise.unsafe.complete(Result.fail(HttpConnectException(url.host, url.port, WebSocketFailed))))
            }: js.Function1[js.Dynamic, Unit]
            socket.onclose = { (event: js.Dynamic) =>
                val code   = event.code.asInstanceOf[Int]
                val reason = event.reason.asInstanceOf[String]
                Sync.Unsafe.evalOrThrow {
                    // A close before the open event is a handshake the server refused: the browser reports the same 1006
                    // it reports for a connection that dropped later, so the failure is the same either way.
                    discard(openedPromise.unsafe.complete(Result.fail(HttpConnectException(url.host, url.port, WebSocketFailed))))
                    closeReasonRef.set(Present((code, reason)))
                        .andThen(inbound.closeDiscard)
                        .andThen(peerClosedPromise.completeUnit.unit)
                }
            }: js.Function1[js.Dynamic, Unit]
            socket
        }

    /** Drains the outbound channel to the browser socket, then closes it with whatever reason the session recorded. */
    private def writeLoop(
        socket: js.Dynamic,
        outbound: Channel[HttpWebSocket.Payload],
        closeReasonRef: AtomicRef[Maybe[(Int, String)]]
    )(using Frame): Unit < Async =
        Abort.run[Closed] {
            Loop.foreach {
                outbound.take.map { frame =>
                    Sync.defer(sendFrame(socket, frame)).andThen(Loop.continue)
                }
            }
        }.map { _ =>
            closeReasonRef.get.map {
                case Present((code, reason)) => Sync.defer(closeSocket(socket, code, reason))
                case Absent                  => Sync.defer(closeSocket(socket, 1000, ""))
            }.andThen(outbound.closeDiscard)
        }

    def connectRaw(
        url: HttpUrl,
        method: HttpMethod,
        body: Span[Byte],
        headers: HttpHeaders,
        connectTimeout: Duration
    )(using Frame): HttpRawConnection < (Async & Abort[HttpException] & Scope) =
        Abort.fail(HttpUnsupportedOnHostException("A raw connection"))

    def closeFiber(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        // A page holds no connection of its own: the browser owns every one of them and its own lifecycle, so closing is
        // the flag and nothing else, and the grace period has nothing to wait for.
        closedFlag = true
        val closePromise = Promise.Unsafe.init[Unit, Any]()
        closePromise.completeDiscard(Result.succeed(()))
        closePromise
    end closeFiber

    def isPoolClosed(using AllowUnsafe): Boolean = closedFlag

end FetchClientBackend

private[kyo] object FetchClientBackend:

    /** The header names the fetch specification reserves for the browser, which it drops from a request without saying so. */
    private val reserved = Set(
        "accept-charset",
        "accept-encoding",
        "access-control-request-headers",
        "access-control-request-method",
        "connection",
        "content-length",
        "cookie",
        "date",
        "dnt",
        "expect",
        "host",
        "keep-alive",
        "origin",
        "referer",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "via"
    )

    /** The first reserved header the request carries, or `Absent` when the browser will send them all. */
    private[client] def reservedHeader(headers: HttpHeaders): Maybe[String] =
        var found = Maybe.empty[String]
        headers.foreach { (name, _) =>
            if found.isEmpty then
                val lower = name.toLowerCase
                if reserved.contains(lower) || lower.startsWith("sec-") || lower.startsWith("proxy-") then found = Present(name)
        }
        found
    end reservedHeader

    private[client] def status(response: js.Dynamic): Int = response.status.asInstanceOf[Int]

    /** The redirect limit a caller who set none is carrying, which is the one the browser's own chain stands in for. */
    private val defaultMaxRedirects = HttpClientConfig().maxRedirects

    /** What a browser reports when a WebSocket fails: that it failed. The event carries no code, status or reason. */
    private[client] val WebSocketFailed = new RuntimeException("the browser reported a WebSocket failure and no reason for it")

    /** The `ws` or `wss` URL for a socket, whichever the page's own scheme implies when the caller named none.
      *
      * `Absent` when the URL is missing its scheme or host and the host has no page location to take them from, as a worker without one,
      * an edge runtime or an embedded engine may.
      */
    private[client] def socketUrl(url: HttpUrl): Maybe[String] =
        def page: Maybe[js.Dynamic] = Maybe.fromOption(PlatformJs.jsGlobal("location").toOption)
        val scheme =
            url.scheme match
                case Present(s) if s == "ws" || s == "wss" => Present(s)
                case Present(s) if s == "https"            => Present("wss")
                case Present(_)                            => Present("ws")
                case Absent => page.map(location => if location.protocol.asInstanceOf[String] == "https:" then "wss" else "ws")
        scheme.flatMap { scheme =>
            if url.host.isEmpty then page.map(location => s"$scheme://${location.host.asInstanceOf[String]}${url.pathWithQuery}")
            else
                val isDefaultPort = if scheme == "wss" then url.port == 443 else url.port == 80
                val authority     = if isDefaultPort then url.host else s"${url.host}:${url.port}"
                Present(s"$scheme://$authority${url.pathWithQuery}")
        }
    end socketUrl

    /** The frame a message event carries, or `Absent` when it is larger than the session allows.
      *
      * The limit counts bytes, and a text frame's characters are not its bytes: one character encodes to at most three of them, so a
      * message short enough that even the worst case fits is taken without encoding it, and only the rest is measured exactly.
      */
    private[client] def payload(data: Any, maxFrameSize: Int): Maybe[HttpWebSocket.Payload] =
        data match
            case text: String =>
                val fits =
                    if text.length <= maxFrameSize / 3 then true
                    else text.getBytes("UTF-8").length <= maxFrameSize
                if fits then Present(HttpWebSocket.Payload.Text(text)) else Absent
            case other =>
                val buffer = other.asInstanceOf[ArrayBuffer]
                if buffer.byteLength > maxFrameSize then Absent
                else Present(HttpWebSocket.Payload.Binary(Span.from(new Int8Array(buffer).toArray)))

    private[client] def sendFrame(socket: js.Dynamic, frame: HttpWebSocket.Payload): Unit =
        frame match
            case HttpWebSocket.Payload.Text(data)   => discard(socket.send(data))
            case HttpWebSocket.Payload.Binary(data) => discard(socket.send(new Uint8Array(data.toArray.toTypedArray.buffer)))

    /** Closes the browser socket, tolerating the states where closing is not allowed (still connecting, already closed). */
    private[client] def closeSocket(socket: js.Dynamic, code: Int, reason: String): Unit =
        try discard(socket.close(code, reason))
        catch case _: js.JavaScriptException => ()

    /** The response's headers, read through the `Headers` iteration the fetch specification defines. */
    private[client] def responseHeaders(response: js.Dynamic): HttpHeaders =
        var headers = HttpHeaders.empty
        discard(response.headers.forEach({ (value: String, name: String) =>
            headers = headers.add(name, value)
            ()
        }: js.Function2[String, String, Unit]))
        headers
    end responseHeaders

end FetchClientBackend
