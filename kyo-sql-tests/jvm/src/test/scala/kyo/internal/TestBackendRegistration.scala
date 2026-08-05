package kyo.internal

import com.example.stub.StubBackend
import kyo.AllowUnsafe
import kyo.AtomicBoolean
import kyo.MysqlClient
import kyo.PostgresClient
import kyo.db.Backend

/** Registers the test program's backends for runtime discovery on the JVM and Scala Native, the platforms where a services scan cannot reach
  * every one: the stub carries no services entry (it is register-only), and Scala Native embeds a single `META-INF/services/kyo.db.Backend`
  * file so only one shipping backend is scanned. Idempotent, and `SqlBackendDiscovery.factories` dedupes by class regardless. On JS and Wasm
  * every backend already registers at module load, so that platform's copy of this is a no-op.
  */
object TestBackendRegistration:

    // Unsafe: the guard is read and written from plain test-bootstrap code, before any live Frame exists.
    import AllowUnsafe.embrace.danger

    private val done = AtomicBoolean.Unsafe.init(false)

    def ensure(): Unit =
        if done.compareAndSet(false, true) then
            PostgresClient.register()
            MysqlClient.register()
            Backend.register(new StubBackend())
            registerTestBackends()
    end ensure

    /** Registers the [[kyo.internal.SqlTestBackend]] conformance descriptors a `META-INF/services/kyo.internal.SqlTestBackend` scan cannot
      * reach on this platform. Empty here: on the JVM the services scan reaches every classpath descriptor, so nothing registers explicitly.
      * Each backend module's descriptor plugs its own registration in through this mechanism.
      */
    private def registerTestBackends(): Unit = ()

end TestBackendRegistration
