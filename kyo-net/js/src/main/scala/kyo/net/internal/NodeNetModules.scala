package kyo.net.internal

import kyo.*
import kyo.net.NetBackendUnavailableException
import kyo.net.NetException
import kyo.scheduler.IOPromise
import scala.scalajs.js
import scala.util.control.NonFatal

/** Node's `net`, `tls`, `fs` and `crypto` modules for [[JsTransport]], loaded with a dynamic `import()`.
  *
  * A static `@JSImport` of a `node:` module is hoisted and resolved when the bundle loads, so a browser page that merely links kyo-net would
  * fail to load at all. Every transport operation that needs these modules already returns a fiber, so it can wait for a dynamic import
  * instead: [[afterLoad]] runs the operation at once when the modules are loaded, and otherwise after the one import all operations share.
  * The operation receives the modules and passes them to the synchronous helpers it reaches (the PEM reads, the certificate hash, the TLS
  * upgrade after the socket is detached), so no helper reads shared state that a later load could change under it.
  *
  * An import that fails, on a host that has no such modules, fails the operation with [[NetBackendUnavailableException]], the failure a
  * host without a usable backend reports. Backend selection already rules such a host out ([[kyo.net.internal.backend.NodeBackend]] probes
  * for a Node-like host), so this is the channel for a host that claims to be Node-like and still cannot import its modules.
  *
  * JS runs one thread, so the two fields below are read and written by one event loop.
  */
private[net] object NodeNetModules:

    /** The loaded modules. Each is the module's `default` export when it has one, the CommonJS-shaped object Node builtins provide. */
    final class Modules(val net: js.Dynamic, val tls: js.Dynamic, val fs: js.Dynamic, val crypto: js.Dynamic)

    private var modules: Modules             = null
    private var loading: js.Promise[Modules] = null

    /** Starts the import if no import is loaded or in flight, and returns the one in flight. A failed import is forgotten, so a later operation
      * tries again.
      */
    def load(): js.Promise[Modules] =
        if loading == null then
            val all = js.Promise.all[js.Any](js.Array(
                js.`import`[js.Dynamic]("node:net"),
                js.`import`[js.Dynamic]("node:tls"),
                js.`import`[js.Dynamic]("node:fs"),
                js.`import`[js.Dynamic]("node:crypto")
            ))
            loading = all.`then`[Modules](
                { (namespaces: js.Array[js.Any]) =>
                    modules = new Modules(exports(namespaces(0)), exports(namespaces(1)), exports(namespaces(2)), exports(namespaces(3)))
                    modules
                }: js.Function1[js.Array[js.Any], Modules],
                js.defined({ (error: scala.Any) =>
                    loading = null
                    js.Promise.reject(error)
                }: js.Function1[scala.Any, js.Thenable[Modules]])
            )
        end if
        loading
    end load

    /** Runs `operation` now when the modules are loaded, and otherwise once they are.
      *
      * The fiber returned before the load completes becomes the operation's fiber. Interrupting it before then settles it and the operation
      * never runs, so no socket is opened for a caller that has gone; interrupting it after reaches the operation's fiber.
      */
    def afterLoad[A](operation: Modules => Fiber.Unsafe[A, Abort[NetException]])(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[A, Abort[NetException]] =
        if modules != null then operation(modules)
        else
            val promise = new IOPromise[NetException, A]
            discard(load().`then`[Unit](
                { (loadedModules: Modules) =>
                    if !promise.done() then
                        // A throw here would reject a JS promise nothing observes and leave the fiber pending forever, so it settles the
                        // fiber instead, as the throw would have reached the caller had the modules already been loaded.
                        try
                            // Fiber.Unsafe[A, S] is an opaque alias over the same IOPromise runtime object (see JsTransport.connectSocket), so
                            // the operation's fiber is linked through this erased-boundary cast.
                            promise.becomeDiscard(operation(loadedModules).asInstanceOf[IOPromise[NetException, A]])
                        catch
                            case error: Throwable if NonFatal(error) => promise.completeDiscard(Result.panic(error))
                        end try
                }: js.Function1[Modules, Unit],
                js.defined({ (error: scala.Any) =>
                    promise.completeDiscard(Result.fail(NetBackendUnavailableException(Present("node"), describe(error))))
                }: js.Function1[scala.Any, Unit])
            ))
            promise.asInstanceOf[Fiber.Unsafe[A, Abort[NetException]]]
        end if
    end afterLoad

    /** Forgets the loaded modules, so a test can exercise the first load. */
    private[net] def forgetForTesting(): Unit =
        modules = null
        loading = null

    private def exports(namespace: js.Any): js.Dynamic =
        val dynamic = namespace.asInstanceOf[js.Dynamic]
        val default = dynamic.selectDynamic("default")
        if js.isUndefined(default) || default == null then dynamic else default
    end exports

    private def describe(error: scala.Any): String =
        error match
            case e: Throwable => s"importing Node's net modules failed: ${e.getMessage}"
            case other        => s"importing Node's net modules failed: ${js.Dynamic.global.String(other.asInstanceOf[js.Any])}"

end NodeNetModules
