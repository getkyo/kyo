package kyo.net.internal.backend

import kyo.*
import kyo.net.NetBackendInitException
import kyo.net.NetBackendUnavailableException
import kyo.net.NetException
import kyo.net.TransportConfig
import kyo.net.internal.transport.IoDriver
import scala.annotation.tailrec

/** A capability-probed I/O backend. Adding a backend is one entry in the platform `registered` list; the selection logic in
  * `IoBackend.select` never changes.
  *
  * The concrete driver this backend produces is parameterized by `Handle`, the platform's connection identifier. The posix backends
  * (io_uring/epoll/kqueue) produce a driver over the unified `PosixHandle` on both Native and JVM; the JVM Nio floor produces a driver over
  * `NioHandle`, and JS over `JsHandle`. The shared trait therefore does not name any platform `Handle` type; each platform backend fixes
  * `Handle` to its own.
  */
private[net] trait IoBackend:

    /** The platform connection identifier the produced driver operates over. */
    type Handle

    /** Stable id matched by `-Dkyo.net.backend` ("io_uring" | "epoll" | "kqueue" | "nio" | "node"). */
    def name: String

    /** Higher wins. io_uring=30, epoll/kqueue=20, nio/node=10 (Backend & TLS matrix). */
    def priority: Int

    /** Cheap capability probe: OS plus syscall availability plus (JVM) native access. MUST NOT throw. */
    def isAvailable(using AllowUnsafe): Boolean

    /** Build the completion-contract driver this backend produces. Called once selection wins. */
    def createDriver(config: TransportConfig)(using AllowUnsafe, Frame): IoDriver[Handle]

end IoBackend

private[net] object IoBackend:

    /** The single selection function for BOTH registries (generic on the entry type). Selection logic never changes; adding an entry is
      * a list edit, never a `select` edit.
      *
      * Resolution order: a forced name (`forcedProp` system property) is honored if its entry is available, and fails loudly with
      * [[NetBackendUnavailableException]] if not (never a silent fall-through). With no forced name, the highest-priority available
      * entry wins, walking the priority gradient. If nothing is available, the same leaf is returned with `forced = Absent`.
      */
    def select[A](
        registered: Chunk[A],
        name: A => String,
        priority: A => Int,
        available: A => Boolean,
        forcedProp: String
    )(using AllowUnsafe, Frame): Result[NetException, A] =
        val forcedName = Maybe(java.lang.System.getProperty(forcedProp)).filter(_.nonEmpty)
        val forced     = forcedName.flatMap(n => Maybe.fromOption(registered.find(e => name(e) == n)))
        forced match
            case Present(entry) =>
                if available(entry) then Result.succeed(entry)
                else Result.fail(NetBackendUnavailableException(Present(forcedName.get)))
            case Absent =>
                Maybe.fromOption(registered.sortBy(e => -priority(e)).find(available)) match
                    case Present(entry) => Result.succeed(entry)
                    case Absent         => Result.fail(NetBackendUnavailableException(Absent))
        end match
    end select

    /** Like [[select]], but additionally BUILDS the chosen entry, and for the unforced gradient FALLS BACK to the next available entry when a
      * higher-priority one is available yet fails to build.
      *
      * `isAvailable` is a cheap capability probe; it can pass on a host where the backend still cannot construct at production scale (the
      * canonical case: io_uring whose probe succeeds but whose production-depth `queue_init` fails under a container's RLIMIT_MEMLOCK / seccomp
      * policy). For an UNFORCED selection, building must then degrade to the next available backend rather than surface the init failure, so the
      * transport ends up on a working backend (epoll) instead of failing outright. A FORCED name (`forcedProp`, e.g. `-Dkyo.net.backend`) never
      * falls through: its build failure propagates (fail loud), matching the forced-unavailable contract. If every available entry fails to
      * build, the last build failure is rethrown.
      */
    def selectAndBuild[A, B](
        registered: Chunk[A],
        name: A => String,
        priority: A => Int,
        available: A => Boolean,
        build: A => B,
        forcedProp: String
    )(using AllowUnsafe, Frame): Result[NetException, B] =
        val forcedName = Maybe(java.lang.System.getProperty(forcedProp)).filter(_.nonEmpty)
        val forced     = forcedName.flatMap(n => Maybe.fromOption(registered.find(e => name(e) == n)))
        forced match
            case Present(entry) =>
                // Forced: a build failure propagates (fail loud); a forced backend never silently falls through.
                if available(entry) then Result.catching[NetException](build(entry))
                else Result.fail(NetBackendUnavailableException(Present(forcedName.get)))
            case Absent =>
                buildFirst(registered.sortBy(e => -priority(e)).filter(available).to(Chunk), name, build, Absent)
        end match
    end selectAndBuild

    /** Build the first entry in priority order that constructs successfully, warning and falling through on each build failure. Returns the
      * last failure (or a no-available-backend [[NetBackendUnavailableException]] when nothing was available) once the list is exhausted.
      */
    @tailrec
    private def buildFirst[A, B](
        ordered: Chunk[A],
        name: A => String,
        build: A => B,
        lastError: Maybe[NetException]
    )(using AllowUnsafe, Frame): Result[NetException, B] =
        ordered.headMaybe match
            case Present(entry) =>
                val rest = ordered.tail
                // build throws NetBackendInitException at a driver-init failure; the recoverable predicate drives the degrade.
                val attempted = Result.catching[NetException](build(entry))
                attempted match
                    case Result.Success(built)                                              => Result.succeed(built)
                    case Result.Failure(init: NetBackendInitException) if !init.recoverable =>
                        // Non-recoverable init failure (wake eventfd / registerWake): a shared liveness primitive no fallback can supply.
                        // Do NOT degrade; propagate the typed leaf.
                        Result.fail(init)
                    case Result.Failure(failure) =>
                        Log.live.unsafe.warn(
                            s"IoBackend: backend '${name(entry)}' is available but failed to build; falling back to the next available backend",
                            failure
                        )
                        buildFirst(rest, name, build, Present(failure))
                    // A Panic is an unmodeled fatal that must PROPAGATE, never fold into the next-backend fallback. The compiler-mandated
                    // exhaustive arm over the sealed Result.Error: statically required and semantically correct.
                    case Result.Panic(t) => throw t
                end match
            case Absent =>
                Result.fail(lastError.getOrElse(NetBackendUnavailableException(Absent)))
        end match
    end buildFirst

end IoBackend
