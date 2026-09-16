package kyo.net.internal

import kyo.*
import kyo.internal.Platform
import kyo.net.Connection
import kyo.net.Listener
import kyo.net.NetBackendUnavailableException
import kyo.net.NetConfig
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Transport
import kyo.net.TransportCapabilities
import kyo.net.internal.backend.IoBackendPlatform
import kyo.scheduler.IOPromise
import scala.scalajs.js
import scala.util.control.NonFatal

/** The process transport on Scala.js: it selects and loads its backend the first time an operation needs one.
  *
  * Every backend a Scala.js host can run needs a Node-like host: the posix transport with its drivers and the koffi layer it loads natives
  * through, and Node's own transport. Together they are most of kyo-net, and a page runs none of them. So the backend registry is reached only
  * inside `js.dynamicImport`, which the linker emits as a module a host fetches when the import runs. A Node-like host fetches it on its first
  * operation; a page never does, and its operations fail with [[NetBackendUnavailableException]] without loading anything. A link that cannot
  * split (WasmGC, `NoModule`) calls the registry directly, so the backends are part of its one output, as they must be there.
  *
  * Every operation already returns a fiber, so each one waits for the single load they share and then runs on the backend, the way
  * [[NodeNetModules.afterLoad]] waits for Node's own modules. A load that fails is forgotten, so a later operation tries again.
  *
  * `capabilities` is the one member that answers synchronously, and on a Node-like host its answer belongs to whichever backend selection
  * picks, which exists only once the load completes. `NetPlatform.loadedTransport` waits for that; reading `capabilities` on a Node-like host
  * before the load completes is a programming error and throws `IllegalStateException` saying so.
  *
  * JS runs one thread, so the fields below are read and written by one event loop.
  */
final private[net] class DeferredTransport extends Transport:

    private var backend: Transport             = null
    private var loading: js.Promise[Transport] = null

    /** Completes with this transport once its backend is loaded, so `capabilities` can answer. */
    private[net] def loaded(using AllowUnsafe, Frame): Fiber.Unsafe[Transport, Abort[NetException]] =
        afterLoad(_ => Fiber.Unsafe.fromResult(Result.succeed(this)))

    def connect(host: String, port: Int, connectTimeout: Duration, config: NetConfig)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] =
        afterLoad(_.connect(host, port, connectTimeout, config))

    def connectTls(host: String, port: Int, tls: NetTlsConfig, connectTimeout: Duration, config: NetConfig)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] =
        afterLoad(_.connectTls(host, port, tls, connectTimeout, config))

    def connectUnix(path: String, connectTimeout: Duration, config: NetConfig)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] =
        afterLoad(_.connectUnix(path, connectTimeout, config))

    def stdio(channelCapacity: Int, readChunkSize: Int)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection, Abort[NetException]] =
        afterLoad(_.stdio(channelCapacity, readChunkSize))

    def listen(host: String, port: Int, backlog: Int, config: NetConfig)(
        handler: Connection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[NetException]] =
        afterLoad(_.listen(host, port, backlog, config)(handler))

    def listenTls(host: String, port: Int, backlog: Int, tls: NetTlsConfig, config: NetConfig)(
        handler: Connection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[NetException]] =
        afterLoad(_.listenTls(host, port, backlog, tls, config)(handler))

    def listenUnix(path: String, backlog: Int, config: NetConfig)(
        handler: Connection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[NetException]] =
        afterLoad(_.listenUnix(path, backlog, config)(handler))

    def upgradeToTls(conn: Connection, tls: NetTlsConfig, channelCapacity: Int)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] =
        afterLoad(_.upgradeToTls(conn, tls, channelCapacity))

    private[net] def capabilities: TransportCapabilities =
        if backend != null then backend.capabilities
        else if !Platform.isNodeLike then TransportCapabilities(Set.empty, unixSockets = false)
        else
            throw new IllegalStateException(
                "the transport's backend is not loaded yet, so its capabilities are unknown; read them through NetPlatform.loadedTransport"
            )

    /** Runs `operation` on the backend now when it is loaded, and otherwise once it is. Interrupting the returned fiber before the load
      * completes settles it, and `operation` never runs.
      */
    private def afterLoad[A](operation: Transport => Fiber.Unsafe[A, Abort[NetException]])(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[A, Abort[NetException]] =
        if backend != null then operation(backend)
        else
            val promise = new IOPromise[NetException, A]
            discard(load().`then`[Unit](
                { (loadedBackend: Transport) =>
                    if !promise.done() then
                        // A throw here would reject a JS promise nothing observes and leave the fiber pending forever, so it settles the
                        // fiber instead, as the throw would have reached the caller had the backend already been loaded.
                        try
                            // Fiber.Unsafe[A, S] is an opaque alias over the same IOPromise runtime object, so the operation's fiber is linked
                            // through this erased-boundary cast.
                            promise.becomeDiscard(operation(loadedBackend).asInstanceOf[IOPromise[NetException, A]])
                        catch
                            case error: Throwable if NonFatal(error) => promise.completeDiscard(Result.panic(error))
                        end try
                }: js.Function1[Transport, Unit],
                js.defined({ (error: scala.Any) =>
                    promise.completeDiscard(Result.fail(unavailable(error)))
                }: js.Function1[scala.Any, Unit])
            ))
            promise.asInstanceOf[Fiber.Unsafe[A, Abort[NetException]]]
        end if
    end afterLoad

    /** Starts the load if none is loaded or in flight, and returns the one in flight. */
    private def load()(using AllowUnsafe, Frame): js.Promise[Transport] =
        if loading == null then
            val started: js.Promise[Transport] =
                if !Platform.isNodeLike then js.Promise.reject(noBackendOnThisHost)
                else
                    Platform.linkTimeIf(Platform.canSplitModules) {
                        js.dynamicImport(IoBackendPlatform.transport())
                    } {
                        try js.Promise.resolve[Transport](IoBackendPlatform.transport())
                        catch case error: Throwable if NonFatal(error) => js.Promise.reject(error)
                    }
            loading = started.`then`[Transport](
                { (selected: Transport) =>
                    backend = selected
                    selected
                }: js.Function1[Transport, Transport],
                js.defined({ (error: scala.Any) =>
                    loading = null
                    js.Promise.reject(error)
                }: js.Function1[scala.Any, js.Thenable[Transport]])
            )
        end if
        loading
    end load

    /** The forced backend, when `-Dkyo.net.backend` names one, as selection reads it. */
    private def forced: Maybe[String] = Maybe(kyo.net.backend()).filter(_.nonEmpty)

    private def noBackendOnThisHost(using Frame): NetBackendUnavailableException =
        NetBackendUnavailableException(
            forced,
            s"every I/O backend needs a Node-like host (Node, Bun or Deno) for its sockets; this host is ${Platform.host}"
        )

    /** What a failed load becomes on an operation's channel. Selection fails with its own [[NetBackendUnavailableException]], which passes
      * through; anything else, such as a module the host could not fetch, is the reason no backend is available.
      */
    private def unavailable(error: scala.Any)(using Frame): NetException =
        error match
            case e: NetException => e
            case e: Throwable    => NetBackendUnavailableException(forced, e)
            case other           => NetBackendUnavailableException(forced, js.JavaScriptException(other))

end DeferredTransport
