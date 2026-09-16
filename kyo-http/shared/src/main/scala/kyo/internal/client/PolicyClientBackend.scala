package kyo.internal.client

import kyo.*
import kyo.net.internal.util.GrowableByteBuffer

/** The part of sending a request that does not depend on how the bytes leave the process.
  *
  * A request resolves against the configured base URL, then passes the retry schedule, the request timeout, the redirect chain and the
  * client and route filters, and only then reaches the wire. Every one of those is the same whether a socket carries the request or the
  * browser's `fetch` does, so a backend states them once here and implements [[dispatchWith]], which is the step that differs.
  *
  * @see
  *   [[kyo.internal.client.HttpClientBackend]] the socket implementation of `dispatchWith`
  */
abstract private[kyo] class PolicyClientBackend extends ClientBackend:

    /** Sends one request, after every policy above has been applied, and hands the response to `f`. */
    protected def dispatchWith[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException])

    def sendWithConfig[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        retryWith(route, PolicyClientBackend.resolved(request, config), config)(f)

    private def retryWith[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        config.retrySchedule match
            case Present(schedule) =>
                def loop(remaining: Schedule): A < (Async & Abort[HttpException]) =
                    timeoutWith(route, request, config) { res =>
                        if !config.retryOn(res.status) then f(res)
                        else
                            Clock.nowWith { now =>
                                remaining.next(now) match
                                    case Present((delay, nextSchedule)) =>
                                        Async.delay(delay)(loop(nextSchedule))
                                    case Absent => f(res)
                            }
                    }
                loop(schedule)
            case Absent =>
                timeoutWith(route, request, config)(f)
    end retryWith

    private def timeoutWith[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        // Apply redirect-aware callback wrapping
        if config.followRedirects then
            def loop(req: HttpRequest[In], count: Int, chain: Chunk[String]): A < (Async & Abort[HttpException]) =
                val inner = filteringWith(route, req, config) { res =>
                    if !res.status.isRedirect then f(res)
                    else if count >= config.maxRedirects then
                        Abort.fail(HttpRedirectLoopException(count, req.method.name, req.url.baseUrl, chain))
                    else
                        res.headers.get("Location") match
                            case Present(location) =>
                                HttpUrl.parse(location) match
                                    case Result.Success(newUrl) =>
                                        // Preserve original host/port/scheme for relative redirects
                                        val resolved =
                                            if newUrl.host.nonEmpty then newUrl
                                            else newUrl.copy(scheme = req.url.scheme, host = req.url.host, port = req.url.port)
                                        // The Location value is peer-controlled and its host and path both reach the ASCII request
                                        // serializer. Rejecting an unencodable target here, before the redirect is followed, keeps the
                                        // failure typed and spends no name resolution or connection on a target that cannot be sent.
                                        PolicyClientBackend.nonAsciiRedirect(resolved) match
                                            case Present(field) => Abort.fail(HttpNonAsciiException(field))
                                            case Absent         =>
                                                // A redirect target is named by the ORIGIN, not by the caller, so the Location value is
                                                // attacker-chosen whenever the origin is malicious, compromised, or merely open. Carrying
                                                // the caller's credentials to whatever authority it names hands them to a third party, and
                                                // an https to http Location additionally puts them on the wire in cleartext. Credentials
                                                // are therefore scoped to the origin that received them (RFC 6454 section 4: the scheme,
                                                // host and port triple) and dropped whenever a hop leaves it.
                                                val crossOrigin = !HttpClientBackend.sameOrigin(req.url, resolved)
                                                val nextHeaders =
                                                    if crossOrigin then HttpClientBackend.stripCredentials(req.headers)
                                                    else req.headers
                                                // RFC 9110 section 15.4.4: 303 See Other requires changing method to GET
                                                val nextReq =
                                                    if res.status == HttpStatus.SeeOther then
                                                        req.copy(url = resolved, method = HttpMethod.GET, headers = nextHeaders)
                                                    else req.copy(url = resolved, headers = nextHeaders)
                                                // Debug rather than warn: a service redirecting an authenticated request to a CDN or a
                                                // sibling port is ordinary traffic, so this is not on its own a fault. It is logged
                                                // because the visible consequence, a 401 from the target, is otherwise hard to explain.
                                                val announce =
                                                    if crossOrigin && nextHeaders.size != req.headers.size then
                                                        Log.debug(
                                                            s"dropping credential headers on redirect leaving origin ${req.url.baseUrl} for ${resolved.baseUrl}"
                                                        )
                                                    else Kyo.unit
                                                announce.andThen(loop(nextReq, count + 1, chain.append(location)))
                                        end match
                                    case Result.Failure(err) =>
                                        Abort.fail(err)
                            case Absent => f(res)
                }
                if config.timeout == Duration.Infinity then inner
                else
                    Async.timeoutWithError(
                        config.timeout,
                        Result.Failure(HttpTimeoutException(config.timeout, req.method.name, req.url.baseUrl))
                    )(inner)
                end if
            end loop
            loop(request, 0, Chunk.empty)
        else
            val inner = filteringWith(route, request, config)(f)
            if config.timeout == Duration.Infinity then inner
            else
                Async.timeoutWithError(
                    config.timeout,
                    Result.Failure(HttpTimeoutException(config.timeout, request.method.name, request.url.baseUrl))
                )(inner)
            end if

    /** Applies the client and route filters around the dispatch, which is where a request is last seen before the wire. */
    private def filteringWith[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        // Client-side filters (e.g. basicAuth, bearerAuth) are Passthrough, they transform the request
        // and forward next's result unchanged.
        // Auto-discovered filters (e.g. W3C trace context from kyo-stats-otlp) are composed first.
        val autoFilter =
            if config.autoFilters then HttpFilter.Factory.composedClient
            else HttpFilter.noop
        val clientFilter = autoFilter.andThen(config.clientFilter)
        val routeFilter  = route.filter
        if (clientFilter eq HttpFilter.noop) && (routeFilter eq HttpFilter.noop) then
            // Fast path: no filters configured, dispatch directly without a filter closure
            dispatchWith(route, request, config)(f)
        else
            val filter = clientFilter.andThen(routeFilter)
                .asInstanceOf[HttpFilter[Any, In, Out, Out, Nothing]]
            filter[In, Out, HttpException, Any](
                request,
                (filteredReq: HttpRequest[In]) =>
                    dispatchWith(route, filteredReq, config)(f)
                        .asInstanceOf[HttpResponse[Out] < (Async & Abort[HttpException | HttpResponse.Halt])]
            ).asInstanceOf[A < (Async & Abort[HttpException])]
        end if
    end filteringWith
end PolicyClientBackend

private[kyo] object PolicyClientBackend:

    /** `request` resolved against the configured base URL: a request with no scheme of its own takes the base's scheme, host and port. */
    def resolved[In](request: HttpRequest[In], config: HttpClientConfig): HttpRequest[In] =
        config.baseUrl match
            case Present(base) if request.url.scheme.isEmpty =>
                request.copy(url = HttpUrl(base.scheme, base.host, base.port, request.url.path, request.url.rawQuery))
            case _ => request

    /** The failure for the first request-line element or header field the serializer must refuse, or `Absent` when it can write them all.
      *
      * Testing here, before the first byte reaches the connection buffer, is what turns a serializer precondition breach deep in an unsafe
      * write into a typed failure the caller can match on. Two rules meet at this one point:
      *
      *   - A control character has no place in the path, the Host value or a header field. A CR or an LF there ends the request line or the
      *     header line early, so the server reads one line as two and sees a request the caller never made (request smuggling, RFC 9112
      *     section 11.2). The path is peer-reachable through a redirect `Location`, and a header value through any proxy that echoes one.
      *   - The request line is ASCII, since kyo-http percent-encodes no path and punycodes no host. A header *value* has no such limit: it
      *     may carry obs-text (RFC 9110 section 5.5) and goes out as its UTF-8 octets.
      */
    def unsendableField(path: String, hostHeader: String, headers: HttpHeaders)(using Frame): Maybe[HttpException] =
        if !HttpHeaders.isControlFree(path) then Present(HttpInvalidFieldException("the request path"))
        else if !GrowableByteBuffer.isAscii(path) then Present(HttpNonAsciiException("the request path"))
        else if !HttpHeaders.isControlFree(hostHeader) then Present(HttpInvalidFieldException("the Host header"))
        else if !GrowableByteBuffer.isAscii(hostHeader) then Present(HttpNonAsciiException("the Host header"))
        else headers.invalidField.map(HttpInvalidFieldException(_))

    /** Names the part of a redirect target that cannot be sent, or `Absent` when the target is sendable. */
    def nonAsciiRedirect(url: HttpUrl): Maybe[String] =
        if !GrowableByteBuffer.isAscii(url.host) then Present("the redirect host")
        else if !GrowableByteBuffer.isAscii(url.path) then Present("the redirect path")
        else Absent

end PolicyClientBackend
