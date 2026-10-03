package kyo.internal

import kyo.*
import kyo.db.Backend

/** A kyo descriptor whose engine runs in a Linux container this process provisions.
  *
  * Reachable only where a container runtime answers and the host is not Windows: the Windows CI runners serve Windows containers alone,
  * over a named pipe, so these images cannot be pulled there. An embedded descriptor does not extend this and runs on Windows.
  *
  * A container that fails to start, or a container client that refuses its configuration, is a panic, not a typed failure: the leaf reports
  * red with the cause, and the published contract stays free of kyo-pod and kyo-http.
  */
abstract class ContainerConformanceBackend(backend: Backend) extends SqlConformanceBackend(backend):

    /** The container this engine runs in, shared across leaves. */
    def containerConfig: Container.Config

    override def reachable: Boolean = !Platform.isWindows && ContainerRuntimeProbe.reachable

    /** Provisions a fresh schema inside this engine's container and runs `f` against it. */
    def provision[A, S](f: SqlConformanceBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException | HttpConfigException] & Scope)

    final def withFreshSchema[A, S](f: SqlConformanceBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException] & Scope) =
        Abort.recover[ContainerException | HttpConfigException](e => Abort.panic(e))(provision(f))

end ContainerConformanceBackend
