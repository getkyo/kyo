package kyo.internal

import java.util.ServiceLoader
import kyo.Chunk
import kyo.db.Backend
import scala.util.control.NonFatal

/** The backend factories the RUNNING program can find, declared in `META-INF/services/kyo.db.Backend`.
  *
  * This is the second half of backend resolution and it never displaces the first. [[kyo.db.Backend.Registry]] still carries the factories the
  * derivation found on the COMPILE classpath, those still win every scheme they claim, and a literal URL naming a scheme none of them claims is
  * still a compile error. What this adds is the case the derivation cannot see: a factory that arrives only at run time, reached when a URL the
  * compiler could not read names a scheme no compile-time factory answers.
  *
  * ==One spelling, four platforms==
  *
  * `ServiceLoader` resolves on all four targets for anything depending on kyo-core, so this file is shared rather than split per platform. The
  * JVM has the real class; Scala Native resolves providers at LINK time, which is why a Native application must also enlist them through
  * `nativeConfig.withServiceProviders` (an un-enlisted provider links as `Available` and is never loaded); Scala.js and Wasm reach a kyo-core
  * shim in `java.util` that reads the registry a backend writes to at module load, because neither has a classpath to scan. kyo-http's
  * `HttpFilter.Factory` calls `ServiceLoader.load` from shared source across the same four targets, which is the precedent.
  *
  * What a backend owes per platform is therefore not uniform even though this call site is. The services entry alone is enough for the
  * compile-time derivation everywhere. Being found at RUN time additionally needs an `@JSExportTopLevel` registration on JS and Wasm, and on
  * Native the link-time enlistment plus, for the multi-backend case, an explicit [[kyo.db.Backend.register]]. Scala Native embeds only ONE
  * `META-INF/services/kyo.db.Backend` file when several jars declare the service (it does not concatenate the per-jar files, and `getResources`
  * is unimplemented), so the services scan reaches at most one backend there; a program that opens more than one flavor by computed URL
  * registers the rest. A single-backend Native program still needs nothing beyond the services entry and enlistment. Omitting what a platform
  * owes leaves the backend usable through a literal URL and invisible to a computed one, with no error at either point.
  *
  * The services scan is read once, not per open, and the `serviceScanned` `lazy val` below is that memo: one scan per program, however many
  * registries are derived or opens attempted. Registrations are read live on top of it so a `register` after the first scan is still seen. The
  * cost lands on whichever caller first asks for a scheme the compile-time registry does not claim. A program whose URLs are all literals never
  * pays it, because [[kyo.db.Backend.Registry.forScheme]] consults this only after its own factories come up empty.
  */
private[kyo] object SqlBackendDiscovery:

    /** Every backend this program can find at run time: the `META-INF/services` scan unioned with the backends handed to
      * [[kyo.db.Backend.register]].
      *
      * The scan is memoized (one pass per program); the registrations are read live, so one that lands after discovery first ran is still seen;
      * the two are deduped by class so a backend reached both ways appears once. Declaration order is kept: services-scanned first, then any
      * registration the scan did not already reach. On the JVM the scan alone reaches every classpath backend and registrations are usually
      * empty; on Scala Native registration is what supplies the flavors the single embedded services file leaves out; on JS and Wasm the scan
      * IS the registrations, read back through the kyo-core shim, so the live union adds nothing.
      */
    def factories: Chunk[Backend] =
        val registered = SqlBackendRegistrations.all
        if registered.isEmpty then serviceScanned
        else dedupByClass(serviceScanned ++ registered)
    end factories

    /** Keeps the first occurrence of each backend class, so a backend reached through both the services scan and [[kyo.db.Backend.register]], or
      * registered more than once, appears once. Order-preserving: the services-scanned come first, then registrations the scan did not reach.
      */
    private def dedupByClass(all: Chunk[Backend]): Chunk[Backend] =
        all.foldLeft((Chunk.empty[Backend], Set.empty[String])) { case ((acc, seen), backend) =>
            val name = backend.getClass.getName
            if seen.contains(name) then (acc, seen) else (acc.append(backend), seen + name)
        }._1

    /** The `META-INF/services` scan, memoized: one pass per program however many registries are derived or opens attempted. Any provider that
      * fails to construct is skipped rather than ending the scan for the rest.
      */
    private lazy val serviceScanned: Chunk[Backend] = collect(providers(), Chunk.empty)

    // Scala Native resolves ServiceLoader.load at LINK time by synthesizing a provider-loader def that reuses the
    // call site's exception handler (scala-native#4087 sibling, unfixed as of 0.5.12). A call site inside a body
    // that compiles to a try/catch, which a lazy val initializer does, makes that synthesized def reference a
    // handler label it does not contain and crashes codegen with "key not found: Local(N)" in Lower. Keeping the
    // load in a plain method, so there is no unwind at the call site, avoids it. kyo-http's
    // HttpFilter.Factory.loadFactories exists for the same reason: do not inline it into its call site.
    private def providers(): java.util.Iterator[Backend] =
        ServiceLoader.load(classOf[Backend]).iterator()

    /** Drains `it`, skipping a provider whose construction throws rather than ending discovery for the rest.
      *
      * Mirrors `kyo.stats.internal.TraceExporter.getIsolated`, and for its reason: on the JVM and Native the iterator constructs each provider
      * lazily on `next()`, and this walk runs from a `lazy val`, a boundary that turns a throw into an unrecoverable initializer failure. So a
      * third-party factory whose constructor throws degrades to a backend that is absent instead of to a program that can open no client at
      * all. A throw from `hasNext` ends the walk, because there is then no next entry to step past.
      *
      * ON JS AND WASM THIS ISOLATION IS DEAD, and it stays rather than being removed as unreachable. The kyo-core shim builds its whole
      * provider list inside `load`, so by the time `iterator()` is returned every provider already exists and neither `hasNext` nor
      * `next()` can run user code. The guard is load-bearing on exactly the two platforms that construct lazily, and inert rather than wrong
      * on the two that do not. Splitting the file to delete two dead catch clauses would cost the shared spelling above.
      *
      * `LinkageError` is caught alongside `NonFatal`, and this walk is where the difference matters. `ServiceLoader` reports a malformed
      * services file or a class that is not a subtype as `ServiceConfigurationError`, which `NonFatal` does admit, but a provider whose own
      * dependency is missing arrives as `NoClassDefFoundError`, which `NonFatal` excludes as a `LinkageError`. Both mean the same thing here,
      * that this provider's class cannot be used, and both deserve the same answer, that the backend is absent. This matters beyond tidiness
      * because a caller can reach this walk while an error message is being built: `Registry.schemes` is what
      * `SqlConnectionUnsupportedSchemeException` reports, so an uncaught throw here would replace the scheme failure the caller asked about
      * with a discovery failure they did not. Everything `NonFatal` excludes for a reason still propagates: a `VirtualMachineError`,
      * `InterruptedException`, `ThreadDeath` or `ControlThrowable` must not be swallowed by a classpath scan.
      *
      * Accumulates into a `Chunk` rather than a `List`, so no reversal or conversion is needed and declaration order is what comes out.
      * `TraceExporter` uses a `List` because `kyo-stats-registry` sits below `kyo-data` and has no `Chunk`; that constraint is not this
      * module's.
      */
    @scala.annotation.tailrec
    private def collect(it: java.util.Iterator[Backend], acc: Chunk[Backend]): Chunk[Backend] =
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

end SqlBackendDiscovery
