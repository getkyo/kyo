package kyo.ffi.internal

import kyo.ffi.FfiLoadError
import kyo.internal.PlatformJs
import scala.scalajs.js

/** Where a generated JS impl gets its dispatch table, and the one place that decides which transport backs it.
  *
  * A generated impl never names a transport. It asks for a bag of callables keyed by method name and calls
  * `facade.<name>(args)`, passing pointers as numbers, strings as JS strings and buffers as `Uint8Array`. Anything
  * answering in that shape can back a binding, so a second transport is a registration rather than a second
  * generator.
  *
  * On Node, koffi loads a real shared library. In a browser there is neither, so a module with a WebAssembly build
  * of its C registers a provider here. The choice is made by asking whether koffi is reachable, not by sniffing
  * for a browser, so a runtime that can load native code keeps doing so.
  */
object NativeFacade:

    /** Builds the dispatch table for `libraryId`. `fns` carries the exported C symbol, result type and argument
      * types; koffi consumes the type strings directly, and a WASM provider is handed the same descriptors.
      */
    def load(libraryId: String, fns: Seq[KoffiFn]): js.Dynamic =
        if preferred() == "koffi" || (preferred() != "wasm" && koffiAvailable()) then
            KoffiFacade.load(NativeLoader.jsResolve(libraryId), fns)
        else
            WasmFacadeRegistry.get(libraryId) match
                case Some(provider) => provider(fns)
                case None           =>
                    throw new FfiLoadError.Unsupported(
                        s"No way to reach native library '$libraryId' on this JS runtime. koffi is unavailable, " +
                            "which is expected in a browser, and no WebAssembly provider is registered for this " +
                            s"library. A module with a WebAssembly build registers one through " +
                            s"kyo.ffi.internal.WasmFacadeRegistry.register before the binding is first loaded; that " +
                            "registration is asynchronous because instantiating a WebAssembly module is, so it has " +
                            "to complete before anything touches the binding."
                    )
    end load

    /** An explicit transport choice, or the empty string to decide by what is available.
      *
      * `-Dkyo.ffi.js.transport=wasm` forces the WebAssembly path where koffi would otherwise win, which is how the
      * WASM transport runs under the same driver suites as the native one. Supported rather than a test hook: an
      * application that would rather not carry a native dependency can make the same choice.
      */
    private def preferred(): String =
        sys.props.getOrElse("kyo.ffi.js.transport", "")

    /** Whether koffi can be required in this runtime. A presence check on the require function and the module
      * itself rather than a browser heuristic: a bundler can leave a `require` shim in a browser bundle, and a
      * Node process can be missing the optional koffi dependency.
      *
      * Asks for the require function the same way [[KoffiFacade]] does, through `PlatformJs.moduleRequire`. A bare
      * `require` global is only half the answer: under the ESModule kind there is none, and the module resolves
      * one from `node:module`'s `createRequire` instead. Reading the global directly made this answer false on
      * every ESModule link, which is both kyo-ffi's own JS axis and the Wasm row of every JS project, so a runtime
      * that could load native code was turned away to the WebAssembly registry and failed there with
      * `FfiLoadError.Unsupported` while [[KoffiFacade.resolve]] on the same runtime would have found koffi.
      */
    private def koffiAvailable(): Boolean =
        try
            PlatformJs.moduleRequire.fold(false) { req =>
                val koffi = req.asInstanceOf[js.Function1[String, js.Dynamic]]("koffi")
                !js.isUndefined(koffi) && koffi != null
            }
        catch case _: Throwable => false

end NativeFacade

/** Providers that can back a binding with WebAssembly instead of a loaded shared library.
  *
  * Registration is separate from use because instantiating a WebAssembly module is asynchronous while a generated
  * companion builds its facade in a `val`: an application initializes the module first and registers the provider,
  * and the binding then loads synchronously. Keyed by the same library id the native transport resolves to a file.
  */
object WasmFacadeRegistry:

    private val providers = scala.collection.mutable.Map.empty[String, Seq[KoffiFn] => js.Dynamic]

    /** Registers `provider` as the WebAssembly backing for `libraryId`, replacing any previous one so that
      * re-initializing a module is not an error.
      */
    def register(libraryId: String, provider: Seq[KoffiFn] => js.Dynamic): Unit =
        providers.update(libraryId, provider)

    def get(libraryId: String): Option[Seq[KoffiFn] => js.Dynamic] = providers.get(libraryId)

    /** Whether a provider is registered. Lets a caller check before triggering a load that would otherwise fail. */
    def isRegistered(libraryId: String): Boolean = providers.contains(libraryId)

    /** Drops `libraryId`'s provider. For tests that need the unregistered state back. */
    def unregister(libraryId: String): Unit = providers.remove(libraryId).foreach(_ => ())

end WasmFacadeRegistry
