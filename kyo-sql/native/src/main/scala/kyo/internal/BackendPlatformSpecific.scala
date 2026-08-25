package kyo.internal

import kyo.db.Backend

/** The Scala Native half of [[kyo.db.Backend]]'s companion.
  *
  * A single backend is discovered at run time from the `META-INF/services/kyo.db.Backend` entry the linker embeds (also enlisted through
  * `nativeConfig.withServiceProviders`). Scala Native embeds only ONE such file when several jars declare the service and does not concatenate
  * them, so a program that resolves more than one flavor by computed URL cannot rely on the services scan alone. [[register]] is that program's
  * other half: it makes each additional backend discoverable, the same call JS and Wasm use for the same reason.
  */
private[kyo] trait BackendPlatformSpecific:

    /** Registers `backend` so runtime discovery finds it. On Scala Native this is how a program that opens more than one flavor by computed URL
      * reaches the flavors the single embedded services file leaves out.
      */
    def register(backend: Backend): Unit =
        SqlBackendRegistrations.register(backend)

end BackendPlatformSpecific
