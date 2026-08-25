package kyo.internal

import java.util.ServiceLoader
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import scala.util.control.NonFatal

/** The test-backend descriptors this run can actually exercise: the registered set when a container runtime is reachable, empty when it is
  * not.
  *
  * A caller reads [[available]] and iterates it. An empty result is meaningful and is the caller's to react to: `SqlBackendTest.forEachBackend`
  * turns it into a failing leaf rather than a silent green, because a conformance run with no backend to exercise has verified nothing.
  *
  * @see
  *   [[SqlTestBackendRegistry]] where the descriptors are discovered and registered
  */
object SqlTestBackends:

    /** The descriptors to run this pass, computed once: every registered descriptor when [[containerRuntimeReachable]] holds, and none when it
      * does not, so an unreachable runtime yields an empty set rather than descriptors whose containers cannot start.
      *
      * Forcing [[TestBackendRegistration.ensure]] first is what makes the register-fallback platforms (JS, Wasm, and the second backend on
      * Native) contribute their descriptors before the registry is read; the services scan reaches the rest.
      */
    lazy val available: Seq[SqlTestBackend] =
        TestBackendRegistration.ensure()
        if containerRuntimeReachable then SqlTestBackendRegistry.all else Seq.empty

    /** Whether a container runtime is reachable, delegated to [[kyo.internal.ContainerRuntimeProbe]]. A host with no
      * reachable daemon reads as unreachable, which under the fail-on-empty rule above surfaces as a RED failing leaf
      * rather than a silent green, the safe direction.
      */
    private lazy val containerRuntimeReachable: Boolean = ContainerRuntimeProbe.reachable

end SqlTestBackends

/** The test-only registry of [[SqlTestBackend]] descriptors, mirroring the production `kyo.internal.SqlBackendDiscovery`: a
  * `META-INF/services/kyo.internal.SqlTestBackend` scan unioned with the descriptors handed to [[register]], deduped by class.
  *
  * The scan reaches every classpath descriptor on the JVM and one on Native; [[register]] supplies the descriptors a scan cannot, the second
  * one on Native and every one on JS and Wasm, called from each platform's `TestBackendRegistration`.
  */
object SqlTestBackendRegistry:

    // A lock-free cell rather than a monitor, which kyo forbids: registration is a rare startup event and reads are rare,
    // so a compare-and-set retry loop is both correct across threads and cheaper than synchronization.
    private val cell = new AtomicReference[Chunk[SqlTestBackend]](Chunk.empty)

    /** Adds `backend` to the registered set. The read side dedupes by class, so registering a descriptor the scan also reaches, or registering
      * the same one twice, does not double it.
      */
    def register(backend: SqlTestBackend): Unit =
        @scala.annotation.tailrec
        def loop(): Unit =
            val current = cell.get()
            if !cell.compareAndSet(current, current.append(backend)) then loop()
        end loop
        loop()
    end register

    /** Every descriptor this program can find: the services scan unioned with the registrations, deduped by class so a descriptor reached both
      * ways appears once. The scan is memoized; the registrations are read live, so one landing after the first read is still seen.
      */
    def all: Seq[SqlTestBackend] =
        val registered = cell.get()
        if registered.isEmpty then serviceScanned
        else dedupByClass(serviceScanned ++ registered)
    end all

    /** Keeps the first occurrence of each descriptor class, order preserving: the services-scanned first, then registrations the scan did not
      * reach.
      */
    private def dedupByClass(all: Chunk[SqlTestBackend]): Chunk[SqlTestBackend] =
        all.foldLeft((Chunk.empty[SqlTestBackend], Set.empty[String])) { case ((acc, seen), backend) =>
            val name = backend.getClass.getName
            if seen.contains(name) then (acc, seen) else (acc.append(backend), seen + name)
        }._1

    // Memoized: one scan per program. Any provider that fails to construct is skipped rather than ending the scan.
    private lazy val serviceScanned: Chunk[SqlTestBackend] = collect(providers(), Chunk.empty)

    // Scala Native synthesizes ServiceLoader.load's provider loader from the call site's exception handler; keeping the
    // load in a plain method, so there is no try/catch unwind at the call site, avoids the codegen crash a lazy-val
    // initializer would trigger. This is the same reason SqlBackendDiscovery.providers exists: do not inline it back.
    private def providers(): java.util.Iterator[SqlTestBackend] =
        ServiceLoader.load(classOf[SqlTestBackend]).iterator()

    // Drains the iterator, skipping a provider whose construction throws rather than ending discovery for the rest.
    // LinkageError is caught alongside NonFatal because a provider whose dependency is missing arrives as
    // NoClassDefFoundError, which NonFatal excludes; both mean the same thing here, that this descriptor is absent.
    @scala.annotation.tailrec
    private def collect(it: java.util.Iterator[SqlTestBackend], acc: Chunk[SqlTestBackend]): Chunk[SqlTestBackend] =
        val more =
            try it.hasNext
            catch
                case _: LinkageError               => false
                case ex: Throwable if NonFatal(ex) => false
        if !more then acc
        else
            val next =
                try acc.append(it.next())
                catch
                    case _: LinkageError               => acc
                    case ex: Throwable if NonFatal(ex) => acc
            collect(it, next)
        end if
    end collect

end SqlTestBackendRegistry
