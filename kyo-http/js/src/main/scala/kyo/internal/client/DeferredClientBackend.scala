package kyo.internal.client

import kyo.*
import kyo.internal.Platform
import kyo.scheduler.IOPromise
import scala.scalajs.js
import scala.util.control.NonFatal

/** The client backend on Scala.js: it builds the backend this host offers the first time a request needs one.
  *
  * A Node-like host sends over a socket, through [[HttpClientBackend]] on the process transport; every other host sends through the page's
  * own `fetch` and `WebSocket`, through [[FetchClientBackend]]. Each host runs one and never the other, so each is reached only inside
  * `js.dynamicImport`, which the linker emits as a module a host fetches when the import runs: a page never fetches the socket client, and a
  * Node program never fetches the fetch client. A link that cannot split (WasmGC, `NoModule`) builds the backend directly, so both are part of
  * its one output.
  *
  * Every request, WebSocket and raw connection already runs in `Async`, so each waits for the one load they share and then runs on the
  * backend. A load that fails is forgotten, so a later request tries again, and the request it failed reports the server it could not reach,
  * as a backend does for a server that does not answer.
  *
  * Closing needs no backend. A client closed before anything loaded its backend is closed at once, and a request made on it afterwards loads
  * the backend closed, so the refusal it gets is that backend's own. A client closed while its backend is loading closes the backend when it
  * arrives, and the close completes then.
  *
  * JS runs one thread, so the fields below are read and written by one event loop.
  */
final private[kyo] class DeferredClientBackend(
    maxConnectionsPerHost: Int,
    idleConnectionTimeout: Duration,
    defaultTlsConfig: HttpTlsConfig,
    transportConfig: HttpTransportConfig
)(using AllowUnsafe, Frame) extends ClientBackend:

    private var backend: ClientBackend                  = null
    private var loading: js.Promise[ClientBackend]      = null
    private var pendingClose: Maybe[Duration]           = Absent
    private var closing: Maybe[Fiber.Unsafe[Unit, Any]] = Absent

    def sendWithConfig[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        val target = PolicyClientBackend.resolved(request, config).url
        loaded(target).map(_.sendWithConfig(route, request, config)(f))
    end sendWithConfig

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
        loaded(url).map(_.connectWebSocket(url, headers, config, connectTimeout, clientFilter, autoFilters)(f))

    def connectRaw(
        url: HttpUrl,
        method: HttpMethod,
        body: Span[Byte],
        headers: HttpHeaders,
        connectTimeout: Duration
    )(using Frame): HttpRawConnection < (Async & Abort[HttpException] & Scope) =
        loaded(url).map(_.connectRaw(url, method, body, headers, connectTimeout))

    def closeFiber(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        if backend != null then backend.closeFiber(gracePeriod)
        else
            pendingClose = Present(gracePeriod)
            val done = Promise.Unsafe.init[Unit, Any]()
            if loading == null then done.completeUnitDiscard()
            else
                discard(loading.`then`[Unit](
                    { (_: ClientBackend) =>
                        closing match
                            case Present(fiber) => done.becomeDiscard(fiber.safe)
                            case Absent         => done.completeUnitDiscard()
                    }: js.Function1[ClientBackend, Unit],
                    js.defined({ (_: scala.Any) => done.completeUnitDiscard() }: js.Function1[scala.Any, Unit])
                ))
            end if
            done
        end if
    end closeFiber

    def isPoolClosed(using AllowUnsafe): Boolean =
        if backend != null then backend.isPoolClosed else pendingClose.isDefined

    /** The backend, loading it first when it is not loaded. A failed load fails with the server `target` names, as unreachable. */
    private def loaded(target: HttpUrl)(using Frame): ClientBackend < (Async & Abort[HttpException]) =
        if backend != null then backend
        else
            Sync.Unsafe.defer {
                val promise = new IOPromise[HttpException, ClientBackend]
                discard(load().`then`[Unit](
                    { (arrived: ClientBackend) => promise.completeDiscard(Result.succeed(arrived)) }: js.Function1[ClientBackend, Unit],
                    js.defined({ (error: scala.Any) =>
                        promise.completeDiscard(Result.fail(HttpConnectException(target.host, target.port, cause(error))))
                    }: js.Function1[scala.Any, Unit])
                ))
                promise.asInstanceOf[Fiber.Unsafe[ClientBackend, Abort[HttpException]]].safe.get
            }

    /** Starts the load if none is loaded or in flight, and returns the one in flight. */
    private def load(): js.Promise[ClientBackend] =
        if loading == null then
            val started: js.Promise[ClientBackend] =
                if Platform.isNodeLike then
                    Platform.linkTimeIf(Platform.canSplitModules) {
                        js.dynamicImport(socketBackend())
                    } {
                        attempt(socketBackend())
                    }
                else
                    Platform.linkTimeIf(Platform.canSplitModules) {
                        js.dynamicImport(fetchBackend())
                    } {
                        attempt(fetchBackend())
                    }
            loading = started.`then`[ClientBackend](
                { (arrived: ClientBackend) =>
                    backend = arrived
                    pendingClose.foreach(grace => closing = Present(arrived.closeFiber(grace)))
                    arrived
                }: js.Function1[ClientBackend, ClientBackend],
                js.defined({ (error: scala.Any) =>
                    loading = null
                    js.Promise.reject(error)
                }: js.Function1[scala.Any, js.Thenable[ClientBackend]])
            )
        end if
        loading
    end load

    private def socketBackend(): ClientBackend =
        HttpClientBackend.init(
            kyo.net.NetPlatform.transport,
            maxConnectionsPerHost,
            idleConnectionTimeout,
            defaultTlsConfig,
            transportConfig
        )

    private def fetchBackend(): ClientBackend = new FetchClientBackend

    private def attempt(build: => ClientBackend): js.Promise[ClientBackend] =
        try js.Promise.resolve[ClientBackend](build)
        catch case error: Throwable if NonFatal(error) => js.Promise.reject(error)

    private def cause(error: scala.Any): Throwable =
        error match
            case e: Throwable => e
            case other        => js.JavaScriptException(other)

end DeferredClientBackend
