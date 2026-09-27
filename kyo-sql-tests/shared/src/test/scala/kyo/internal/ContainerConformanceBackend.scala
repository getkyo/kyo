package kyo.internal

import kyo.*

/** A kyo descriptor whose engine runs in a Linux container this process provisions.
  *
  * Reachable only where a container runtime answers and the host is not Windows: the Windows CI runners serve Windows containers alone,
  * over a named pipe, so these images cannot be pulled there. An embedded descriptor does not extend this and runs on Windows.
  *
  * A container that fails to start is a panic, not a typed failure: the leaf reports red with the cause, and the published contract stays
  * free of kyo-pod.
  */
abstract class ContainerConformanceBackend extends SqlConformanceBackend:

    /** The container this engine runs in, shared across leaves by descriptor id. */
    def containerConfig: Container.Config

    override def reachable: Boolean = !Platform.isWindows && ContainerRuntimeProbe.reachable

    /** Provisions a fresh schema inside this engine's container and runs `f` against it. */
    def provision[A, S](f: SqlConformanceBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException] & Scope)

    final def withFreshSchema[A, S](f: SqlConformanceBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException] & Scope) =
        Abort.recover[ContainerException](e => Abort.panic(e))(provision(f))

end ContainerConformanceBackend
