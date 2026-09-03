package kyo.net.internal.backend

import kyo.*
import kyo.net.NetBackendUnavailableException
import kyo.net.internal.transport.IoDriver
import scala.annotation.tailrec

/** A capability-probed I/O backend. Adding a backend is one entry in the platform `registered` list; the selection logic in
  * `IoBackend.select` never changes.
  *
  * It adds ONE member to the shared [[CapabilityDescriptor]] identity: the driver it produces. The concrete driver is parameterized by
  * `Handle`, the platform's connection identifier. The posix backends (io_uring/epoll/kqueue) produce a driver over the unified `PosixHandle`
  * on both Native and JVM; the JVM Nio floor produces a driver over `NioHandle`, and JS over `JsHandle`. The shared trait therefore does not
  * name any platform `Handle` type; each platform backend fixes `Handle` to its own.
  */
private[net] trait IoBackend extends CapabilityDescriptor:

    /** The platform connection identifier the produced driver operates over. */
    type Handle

    /** Build the completion-contract driver this backend produces. Called once selection wins. */
    def createDriver()(using AllowUnsafe, Frame): IoDriver[Handle]

end IoBackend

private[net] object IoBackend:

    /** A registered candidate paired with the outcome of its probe.
      *
      * Selection asks three questions of the same candidates (which one wins, which higher-priority ones were skipped and why, what goes in
      * the report), and each used to re-read availability. Probing once up front and carrying the outcome answers all three from one pass.
      * The per-candidate memo makes the pass cheap on repeat selections; this makes it cheap WITHIN one.
      */
    final private case class Candidate[D <: CapabilityDescriptor](descriptor: D, outcome: CapabilityOutcome)

    private def candidates[D <: CapabilityDescriptor](registered: Chunk[D])(using AllowUnsafe): Chunk[Candidate[D]] =
        registered.map(d => Candidate(d, d.probe))

    private def report[D <: CapabilityDescriptor](cs: Chunk[Candidate[D]], chosen: Maybe[String]): SelectionReport =
        SelectionReport(chosen, cs.map(c => SelectionReport.Entry.Probed(c.descriptor.name, c.descriptor.priority, c.outcome)))

    /** Build the report and log it as the single init line, returning it for the caller to render into a terminal failure. */
    private def logged[D <: CapabilityDescriptor](cs: Chunk[Candidate[D]], chosen: Maybe[String], log: Log.Unsafe)(using
        AllowUnsafe,
        Frame
    ): SelectionReport =
        val selection = report(cs, chosen)
        log.info(s"IoBackend: ${selection.render}")
        selection
    end logged

    /** The single selection function for BOTH registries (generic on the descriptor type and the failure leaf). Selection logic never
      * changes; adding an entry is a list edit, never a `select` edit.
      *
      * Resolution order: `forced` (a name the caller resolved, typically from a `-D` property) is honored when it names a REGISTERED
      * candidate that is available, and fails with `onUnavailable` when that candidate is unavailable. A forced name matching no registered
      * candidate is a third case and does NOT fail: it resolves as if unforced, so a misspelled name selects the gradient's winner silently.
      * That is deliberate (`IoBackendRegistryTest` pins it) and it differs from the pinned-provider path in `TlsProvider.selectFor`, which
      * reports an unregistered id as unavailable. With no forced name, the highest-priority available candidate wins, walking the priority
      * gradient; if nothing is available, the result fails with `onUnavailable(Absent, ...)`. Both failure paths log a warning via `log`
      * before failing, and every path logs the [[SelectionReport]], which `onUnavailable` renders into the terminal failure's cause.
      */
    def select[D <: CapabilityDescriptor, E](
        registered: Chunk[D],
        forced: Maybe[String],
        onUnavailable: (Maybe[String], SelectionReport) => E,
        log: Log.Unsafe = Log.live.unsafe
    )(using AllowUnsafe, Frame): Result[E, D] =
        val cs = candidates(registered)
        // `@unchecked`: the match on a flatMap'd `Maybe` of an abstract element is exhaustive (Present/Absent),
        // but Scala 3 cannot prove it through the opaque `Maybe`'s union + nested-depth machinery for abstract A,
        // the same reason `Maybe.scala` and the kyo-browser flatMap sites use this idiom.
        (forced.flatMap(n => Maybe.fromOption(cs.find(_.descriptor.name == n))): @unchecked) match
            case Present(candidate) =>
                if candidate.outcome.isAvailable then
                    discard(logged(cs, Present(candidate.descriptor.name), log))
                    Result.succeed(candidate.descriptor)
                else
                    log.warn(s"IoBackend: forced backend '${forced.get}' is unavailable: ${candidate.outcome.describe}")
                    Result.fail(onUnavailable(forced, logged(cs, Absent, log)))
            case Absent =>
                Maybe.fromOption(cs.sortBy(c => -c.descriptor.priority).find(_.outcome.isAvailable)) match
                    case Present(candidate) =>
                        warnDemotion(cs, candidate, log)
                        discard(logged(cs, Present(candidate.descriptor.name), log))
                        Result.succeed(candidate.descriptor)
                    case Absent =>
                        log.warn("IoBackend: no available backend")
                        Result.fail(onUnavailable(Absent, logged(cs, Absent, log)))
        end match
    end select

    /** Warn once when the unforced gradient settled on `chosen` only because higher-priority backends were unavailable, so a demotion to a
      * lower backend (the NIO/Node floor in the common case) is visible rather than silent. Silent when `chosen` is already the
      * highest-priority registered candidate, so a host on its fast path logs nothing. Each skipped candidate carries the reason its probe
      * gave, which is the difference between "epoll was skipped" and "epoll was skipped because its bundled shim is not staged".
      */
    private def warnDemotion[D <: CapabilityDescriptor](cs: Chunk[Candidate[D]], chosen: Candidate[D], log: Log.Unsafe)(using
        AllowUnsafe,
        Frame
    ): Unit =
        val skipped = cs.filter(c => c.descriptor.priority > chosen.descriptor.priority && !c.outcome.isAvailable)
        if skipped.nonEmpty then
            log.warn(
                s"IoBackend: selected '${chosen.descriptor.name}'; higher-priority backend(s) unavailable: " +
                    skipped.map(c => s"${c.descriptor.name} (${c.outcome.describe})").mkString(", ")
            )
        end if
    end warnDemotion

    /** Like [[select]], but additionally BUILDS the chosen candidate, and for the unforced gradient FALLS BACK to the next available
      * candidate when a higher-priority one is available yet fails to build. Fixed to [[NetBackendUnavailableException]] rather than generic
      * over the failure leaf: `buildFirst`'s catch below must match a concrete class (a free type parameter is erased and cannot be caught),
      * and this is the backend registry's own failure type, its only production leaf.
      *
      * `build` stays a PARAMETER rather than a member read off the descriptor, because the production method differs per refinement
      * (`build(): Transport` on the JVM/Native registry `Entry`, `createDriver()` on the JS one) and those methods live in source sets this
      * shared function cannot see. Reading it off the descriptor would either not compile here or force this function into `jvm-native`,
      * where the shared selection tests could not reach it.
      *
      * An available candidate can still fail to construct at production scale (the canonical case: io_uring whose probe succeeds but whose
      * production-depth `queue_init` fails under a container's RLIMIT_MEMLOCK / seccomp policy). For an UNFORCED selection, building must then
      * degrade to the next available candidate rather than surface the init failure, so the transport ends up on a working backend (epoll)
      * instead of failing outright. A FORCED name never falls through: its build failure propagates (fail loud), matching the
      * forced-unavailable contract. If every available candidate fails to build, the last build failure wins, and it keeps its own cause: the
      * report augments a diagnosis, it never displaces one.
      */
    def selectAndBuild[D <: CapabilityDescriptor, B](
        registered: Chunk[D],
        build: D => B,
        forced: Maybe[String],
        onUnavailable: (Maybe[String], SelectionReport) => NetBackendUnavailableException,
        log: Log.Unsafe = Log.live.unsafe
    )(using AllowUnsafe, Frame): Result[NetBackendUnavailableException, B] =
        val cs = candidates(registered)
        // `@unchecked`: the match on a flatMap'd `Maybe` of an abstract element is exhaustive (Present/Absent),
        // but Scala 3 cannot prove it through the opaque `Maybe`'s union + nested-depth machinery for abstract A,
        // the same reason `Maybe.scala` and the kyo-browser flatMap sites use this idiom.
        (forced.flatMap(n => Maybe.fromOption(cs.find(_.descriptor.name == n))): @unchecked) match
            case Present(candidate) =>
                // Forced: a build failure propagates (fail loud); a forced backend never silently falls through.
                if candidate.outcome.isAvailable then
                    discard(logged(cs, Present(candidate.descriptor.name), log))
                    try Result.succeed(build(candidate.descriptor))
                    catch case e: NetBackendUnavailableException => Result.fail(e)
                else
                    log.warn(s"IoBackend: forced backend '${forced.get}' is unavailable: ${candidate.outcome.describe}")
                    Result.fail(onUnavailable(forced, logged(cs, Absent, log)))
            case Absent =>
                val availableSorted = cs.sortBy(c => -c.descriptor.priority).filter(_.outcome.isAvailable).to(Chunk)
                availableSorted.headMaybe.foreach(best => warnDemotion(cs, best, log))
                discard(logged(cs, availableSorted.headMaybe.map(_.descriptor.name), log))
                buildFirst(
                    availableSorted.map(_.descriptor),
                    build,
                    Absent,
                    () => onUnavailable(Absent, report(cs, Absent)),
                    log
                )
        end match
    end selectAndBuild

    /** Build the first candidate in priority order that constructs successfully, warning and falling through on each build failure. Fails
      * with the last build failure (or `noneAvailable()` when nothing was ever available) once the list is exhausted.
      */
    @tailrec
    private def buildFirst[D <: CapabilityDescriptor, B](
        ordered: Chunk[D],
        build: D => B,
        lastError: Maybe[NetBackendUnavailableException],
        noneAvailable: () => NetBackendUnavailableException,
        log: Log.Unsafe
    )(using AllowUnsafe, Frame): Result[NetBackendUnavailableException, B] =
        ordered.headMaybe match
            case Present(entry) =>
                val rest = ordered.tail
                val attempted =
                    try Result.succeed(build(entry))
                    catch case e: NetBackendUnavailableException => Result.fail(e)
                attempted match
                    case Result.Success(built) => Result.succeed(built)
                    case Result.Failure(e) =>
                        log.warn(
                            s"IoBackend: backend '${entry.name}' is available but failed to build; falling back to the next available backend",
                            e
                        )
                        buildFirst(rest, build, Present(e), noneAvailable, log)
                    case Result.Panic(t) => throw t
                end match
            case Absent =>
                Result.fail(lastError.getOrElse(noneAvailable()))
        end match
    end buildFirst

end IoBackend
