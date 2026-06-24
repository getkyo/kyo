package kyo.net.internal.backend

import kyo.*
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
      * Resolution order: a forced name (`forcedProp` system property) is honored if its entry is available, and fails loudly with `Closed`
      * if not (never a silent fall-through). With no forced name, the highest-priority available entry wins, walking the priority gradient.
      * If nothing is available, `Closed` is thrown.
      */
    def select[A](
        registered: Chunk[A],
        name: A => String,
        priority: A => Int,
        available: A => Boolean,
        forcedProp: String
    )(using AllowUnsafe, Frame): A =
        // TODO use Kyo's System.Unsafe
        val forcedName = Maybe(java.lang.System.getProperty(forcedProp)).filter(_.nonEmpty)
        val forced     = forcedName.flatMap(n => Maybe.fromOption(registered.find(e => name(e) == n)))
        forced match
            case Present(entry) =>
                if available(entry) then entry
                else
                    // TODO kyo.Log.Unsafe.warn
                    throw Closed("IoBackend", summon[Frame], s"forced $forcedProp=${forcedName.get} is unavailable")
            case Absent =>
                // TODO kyo.Log.Unsafe.warn
                Maybe.fromOption(registered.sortBy(e => -priority(e)).find(available))
                    .getOrElse(throw Closed(
                        "IoBackend",
                        summon[Frame],
                        "no available backend"
                    )) // TODO we need much better errors for these closed excpeitons here
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
    )(using AllowUnsafe, Frame): B =
        val forcedName = Maybe(java.lang.System.getProperty(forcedProp)).filter(_.nonEmpty)
        val forced     = forcedName.flatMap(n => Maybe.fromOption(registered.find(e => name(e) == n)))
        forced match
            case Present(entry) =>
                // Forced: a build failure propagates (fail loud); a forced backend never silently falls through.
                if available(entry) then build(entry)
                else throw Closed("IoBackend", summon[Frame], s"forced $forcedProp=${forcedName.get} is unavailable")
            case Absent =>
                buildFirst(registered.sortBy(e => -priority(e)).filter(available).to(Chunk), name, build, Absent)
        end match
    end selectAndBuild

    /** Build the first entry in priority order that constructs successfully, warning and falling through on each build failure. Rethrows the
      * last failure (or a no-available-backend `Closed` when nothing was available) once the list is exhausted.
      */
    @tailrec
    private def buildFirst[A, B](
        ordered: Chunk[A],
        name: A => String,
        build: A => B,
        lastError: Maybe[Closed]
    )(using AllowUnsafe, Frame): B =
        ordered.headMaybe match
            case Present(entry) =>
                val rest = ordered.tail
                val attempted =
                    try Result.succeed(build(entry))
                    catch case closed: Closed => Result.fail(closed)
                attempted match
                    case Result.Success(built) => built
                    case Result.Failure(closed) =>
                        Log.live.unsafe.warn(
                            s"IoBackend: backend '${name(entry)}' is available but failed to build; falling back to the next available backend",
                            closed
                        )
                        buildFirst(rest, name, build, Present(closed))
                    case Result.Panic(t) => throw t
                end match
            case Absent =>
                throw lastError.getOrElse(Closed("IoBackend", summon[Frame], "no available backend"))
        end match
    end buildFirst

end IoBackend
