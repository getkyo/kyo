package kyo.internal

import kyo.AllowUnsafe
import kyo.AtomicRef
import kyo.Chunk
import kyo.db.Backend

/** The backends handed to [[kyo.db.Backend.register]] on the JVM and Scala Native, the runtime-discovery counterpart a services scan cannot
  * always supply.
  *
  * On the JVM `META-INF/services/kyo.db.Backend` already reaches every backend on the classpath, so registering is an optional extra a program
  * only needs for a backend it did not put on the services path. On Scala Native it is load bearing for the multi-backend case: the linker
  * embeds only ONE `META-INF/services/kyo.db.Backend` file when several jars declare the service, because Scala Native neither concatenates the
  * per-jar files nor implements `getResources`, so a program that opens more than one flavor by computed URL registers the flavors the single
  * embedded file leaves out. JS and Wasm do not write here: they register into the kyo-core `JSServiceLoaderRegistry` their `java.util`
  * `ServiceLoader` shim reads, so on those two the holder stays empty and the union in discovery adds nothing.
  *
  * [[kyo.internal.SqlBackendDiscovery.factories]] unions this holder with the services scan and dedupes by class, so a registered backend is
  * discoverable exactly as a services-declared one is, and a backend reached both ways appears once.
  */
private[kyo] object SqlBackendRegistrations:

    // Unsafe: module-load cell, read and written from plain startup code (a JS module initializer,
    // Backend.register) where no Kyo runtime exists yet, so the unsafe atomic tier is the only one reachable.
    import AllowUnsafe.embrace.danger

    // A lock-free cell rather than `synchronized`, which kyo forbids: registration is a rare startup event and
    // discovery a rare read, so a compare-and-set retry loop is both correct across threads and cheaper than a monitor.
    private val cell = AtomicRef.Unsafe.init(Chunk.empty[Backend])

    /** Adds `backend` to the registered set. Called by [[kyo.db.Backend.register]]; the discovery union dedupes, so registering a backend the
      * services scan also reaches, or registering the same one twice, does not double it.
      */
    def register(backend: Backend): Unit =
        @scala.annotation.tailrec
        def loop(): Unit =
            val current = cell.get()
            if !cell.compareAndSet(current, current.append(backend)) then loop()
        end loop
        loop()
    end register

    /** The backends registered so far, in registration order. Read live rather than memoized, so a registration that lands after discovery has
      * already run is still seen by the next resolution.
      */
    def all: Chunk[Backend] = cell.get()

end SqlBackendRegistrations
