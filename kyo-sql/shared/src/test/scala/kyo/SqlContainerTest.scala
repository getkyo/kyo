package kyo

import kyo.internal.Platform

/** Base class for every container-backed SQL test suite.
  *
  * Cancels (does not fail) each leaf on Windows only: the Windows CI runners serve just Windows containers over a named
  * pipe, so the Linux images these suites boot cannot be pulled there. Everywhere else the leaves run, and a host with
  * no container runtime fails honestly instead of skipping, so a misconfigured Linux runner surfaces as RED rather than
  * as a silent green with no coverage.
  *
  * One central gate replaces per-suite guards: any suite that spawns a container extends this instead of [[kyo.Test]],
  * so a new container suite is covered by construction. A suite that also needs a per-leaf HttpClient threads this gate
  * through super:
  * `override def aroundLeaf[A](body) = super.aroundLeaf(HttpClient.init().flatMap(c => HttpClient.let(c)(body)))`.
  */
abstract class SqlContainerTest extends kyo.Test:

    // Every container suite runs its leaves one at a time PROCESS-WIDE. They contend on one container daemon (parallel
    // leaves race concurrent container starts and blow the port-binding budget) and on one shared network transport
    // (concurrent connections whose reads starve each other on io_uring, which is how the auth/notification suites timed
    // out). `sequential` alone only orders a single suite's own leaves; kyo-test runs suites concurrently in one forked
    // JVM, so `globallySequential` is required to also serialize container leaves ACROSS suites (same need as kyo-ai's
    // BaseAITest, whose CLI suites share one account under this identical config).
    override def config = super.config.sequential.globallySequential(true)

    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        if Platform.isWindows then
            Sync.defer(cancel(
                "container-backed SQL suites do not run on Windows: its container daemon cannot serve the Linux images these suites boot"
            ))
        else body
end SqlContainerTest
