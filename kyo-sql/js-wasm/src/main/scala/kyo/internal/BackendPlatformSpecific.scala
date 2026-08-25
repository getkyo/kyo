package kyo.internal

import kyo.db.Backend
import kyo.stats.internal.JSServiceLoaderRegistry

/** The JS and Wasm half of [[kyo.db.Backend]]'s companion: the one entry those platforms need and the others do not. */
private[kyo] trait BackendPlatformSpecific:

    /** Registers `backend` so runtime discovery can find it on JS and Wasm, which read no services file when the program runs.
      *
      * Call it from the initializer of an `@JSExportTopLevel` value in the backend's own artifact. Nothing in a program references such a
      * value, and linker dead-code elimination drops an initializer nothing references, which is what the export annotation prevents.
      *
      * Only runtime discovery goes through here. A literal URL resolves against the compile classpath, which reads the services entry, so a
      * backend that skips this call still opens for a literal URL and is invisible to a computed one.
      */
    def register(backend: Backend): Unit =
        // Adds `backend` to the in-memory service registry JS and Wasm use in place of a classpath scan, keyed on the
        // Backend class name, which is the key runtime discovery reads back under. Called from a backend's
        // @JSExportTopLevel initializer, since nothing else references it and the linker would drop it.
        JSServiceLoaderRegistry.register(classOf[Backend], backend)

end BackendPlatformSpecific
