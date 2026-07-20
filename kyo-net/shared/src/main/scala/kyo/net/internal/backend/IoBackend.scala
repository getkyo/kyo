package kyo.net.internal.backend

import kyo.*
import kyo.net.NetBackendUnavailableException
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
    def createDriver()(using AllowUnsafe, Frame): IoDriver[Handle]

end IoBackend

private[net] object IoBackend:

    /** The single selection function for BOTH registries (generic on the entry type and the failure leaf). Selection logic never changes;
      * adding an entry is a list edit, never a `select` edit.
      *
      * Resolution order: `forced` (a name the caller resolved, typically from a `-D` property) is honored if its entry is available, and
      * fails with `onUnavailable(forced)` if not (never a silent fall-through). With no forced name, the highest-priority available entry
      * wins, walking the priority gradient; if nothing is available, the result fails with `onUnavailable(Absent)`. Both failure paths log a
      * warning via `log` before failing.
      */
    def select[A, E](
        registered: Chunk[A],
        name: A => String,
        priority: A => Int,
        available: A => Boolean,
        forced: Maybe[String],
        onUnavailable: Maybe[String] => E,
        log: Log.Unsafe = Log.live.unsafe
    )(using AllowUnsafe, Frame): Result[E, A] =
        val forcedEntry = forced.flatMap(n => Maybe.fromOption(registered.find(e => name(e) == n)))
        forcedEntry match
            case Present(entry) =>
                if available(entry) then Result.succeed(entry)
                else
                    log.warn(s"IoBackend: forced backend '${forced.get}' is unavailable")
                    Result.fail(onUnavailable(forced))
            case Absent =>
                Maybe.fromOption(registered.sortBy(e => -priority(e)).find(available)) match
                    case Present(entry) => Result.succeed(entry)
                    case Absent =>
                        log.warn("IoBackend: no available backend")
                        Result.fail(onUnavailable(Absent))
        end match
    end select

    /** Like [[select]], but additionally BUILDS the chosen entry, and for the unforced gradient FALLS BACK to the next available entry when a
      * higher-priority one is available yet fails to build. Fixed to [[NetBackendUnavailableException]] rather than generic over the failure
      * leaf: `buildFirst`'s catch below must match a concrete class (a free type parameter is erased and cannot be caught), and this is the
      * backend registry's own failure type, its only production leaf.
      *
      * `isAvailable` is a cheap capability probe; it can pass on a host where the backend still cannot construct at production scale (the
      * canonical case: io_uring whose probe succeeds but whose production-depth `queue_init` fails under a container's RLIMIT_MEMLOCK / seccomp
      * policy). For an UNFORCED selection, building must then degrade to the next available backend rather than surface the init failure, so the
      * transport ends up on a working backend (epoll) instead of failing outright. A FORCED name never falls through: its build failure
      * propagates (fail loud), matching the forced-unavailable contract. If every available entry fails to build, the last build failure wins.
      */
    def selectAndBuild[A, B](
        registered: Chunk[A],
        name: A => String,
        priority: A => Int,
        available: A => Boolean,
        build: A => B,
        forced: Maybe[String],
        onUnavailable: Maybe[String] => NetBackendUnavailableException,
        log: Log.Unsafe = Log.live.unsafe
    )(using AllowUnsafe, Frame): Result[NetBackendUnavailableException, B] =
        val forcedEntry = forced.flatMap(n => Maybe.fromOption(registered.find(e => name(e) == n)))
        forcedEntry match
            case Present(entry) =>
                // Forced: a build failure propagates (fail loud); a forced backend never silently falls through.
                if available(entry) then
                    try Result.succeed(build(entry))
                    catch case e: NetBackendUnavailableException => Result.fail(e)
                else
                    log.warn(s"IoBackend: forced backend '${forced.get}' is unavailable")
                    Result.fail(onUnavailable(forced))
            case Absent =>
                buildFirst(registered.sortBy(e => -priority(e)).filter(available).to(Chunk), name, build, Absent, onUnavailable, log)
        end match
    end selectAndBuild

    /** Build the first entry in priority order that constructs successfully, warning and falling through on each build failure. Fails with the
      * last build failure (or `onUnavailable(Absent)` when nothing was ever available) once the list is exhausted.
      */
    @tailrec
    private def buildFirst[A, B](
        ordered: Chunk[A],
        name: A => String,
        build: A => B,
        lastError: Maybe[NetBackendUnavailableException],
        onUnavailable: Maybe[String] => NetBackendUnavailableException,
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
                            s"IoBackend: backend '${name(entry)}' is available but failed to build; falling back to the next available backend",
                            e
                        )
                        buildFirst(rest, name, build, Present(e), onUnavailable, log)
                    case Result.Panic(t) => throw t
                end match
            case Absent =>
                Result.fail(lastError.getOrElse(onUnavailable(Absent)))
        end match
    end buildFirst

end IoBackend
