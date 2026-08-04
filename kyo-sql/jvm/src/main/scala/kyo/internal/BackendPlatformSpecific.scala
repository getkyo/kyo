package kyo.internal

import kyo.db.Backend

/** The JVM half of [[kyo.db.Backend]]'s companion.
  *
  * `ServiceLoader` reads every artifact's `META-INF/services/kyo.db.Backend` entry when the program runs, so shipping the entry is all runtime
  * discovery needs and [[register]] is optional here. It exists for the case a services scan cannot cover: a backend an application composes
  * without putting on the services path, made discoverable by registering the instance.
  */
private[kyo] trait BackendPlatformSpecific:

    /** Registers `backend` so runtime discovery finds it, in addition to whatever `META-INF/services/kyo.db.Backend` declares. Optional on the
      * JVM, where the services scan already reaches classpath backends.
      */
    def register(backend: Backend): Unit =
        SqlBackendRegistrations.register(backend)

end BackendPlatformSpecific
