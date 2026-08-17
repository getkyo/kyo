package kyo

// A pool of forked worker processes that run submitted tasks. R is a resource each worker
// acquires once and holds warm across the tasks it runs (Unit when the queue has no resource).
sealed abstract class ForkQueue[R]:

    // Submit a task. Task-level failures (E, timeout) land in the Outcome's `result`, resource leaks in its
    // `leaks` (and retire the worker); only infrastructure failures (crash/spawn/transport) abort the fiber.
    // The task's Env[R] is satisfied by the worker's held resource.
    def apply[A: Schema, E: Schema](
        computation: A < (Async & Abort[E] & Scope & Env[R]),
        options: ForkQueue.Options = ForkQueue.Options.default
    )(using Frame): Fiber[ForkQueue.Outcome[A, E | TaskWorkersException], Abort[InfraWorkersException]] < Sync

    // Like `apply`, but returns a Session with the worker's raw stdin/stdout/stderr, the values the task emits
    // via Emit[O], and a fiber for the final Outcome.
    def stream[A: Schema, E: Schema, O: Schema](
        computation: A < (Async & Abort[E] & Scope & Env[R] & Emit[O]),
        options: ForkQueue.Options = ForkQueue.Options.default
    )(using Frame): ForkQueue.Session[O, A, E | TaskWorkersException] < Sync

end ForkQueue

object ForkQueue:

    // Create a resource-free queue with at most `maxWorkers` concurrent workers.
    def init(launch: Launch, maxWorkers: Int)(using Frame): ForkQueue[Unit] < (Sync & Scope) = ???

    // Create a queue whose workers each hold a resource: `acquire` runs once per worker and its
    // result is reused across tasks; `release` runs when the worker retires.
    def init[R: Tag](launch: Launch, maxWorkers: Int)(
        acquire: R < Async
    )(
        release: R => Any < (Async & Abort[Throwable])
    )(using Frame): ForkQueue[R] < (Sync & Scope) = ???

    // Process-global worker cap, resolved once at class load from a system property
    // (e.g. -Dkyo.ForkQueue.maxWorkers=16): the total worker count across all queues; the per-queue `init`
    // maxWorkers caps one queue's share of it. Default = available processors.
    private[kyo] object maxWorkers extends StaticFlag[Int](Runtime.getRuntime().availableProcessors(), n => Right(Math.max(1, n)))

    // Per-task scheduling and execution options.
    final case class Options(
        exclusions: Set[Exclusion],   // relational exclusion: Global = run alone; Named(key) = mutual exclusion on a resource
        fresh: Boolean,               // true = a fresh worker per task; false = reuse a warm worker
        timeout: Maybe[Duration],     // kill the task if it runs longer than this
        retry: Maybe[Schedule],       // re-run the task on failure per this schedule
        leakChecks: Chunk[Leak.Check] // leak scans after each task; Leak.Check.all by default, empty disables
    )

    object Options:
        val default: Options = ???

    // Relational exclusion a task requires while running, arbitrated cross-pool by the single coordinator.
    enum Exclusion derives CanEqual:
        case Global             // run alone against every task in every pool
        case Named(key: String) // mutual exclusion on a named resource; unrelated tasks are unaffected
    end Exclusion

    // How to spawn a worker process for this queue.
    final case class Launch(
        classpath: Maybe[Chunk[Path]] = Absent,
        jvmOptions: Chunk[String] = Chunk.empty,
        isolation: Isolation = Isolation.Process()
    ) derives CanEqual

    enum Isolation derives CanEqual:
        case Process(env: Map[String, String] = Map.empty) // a plain OS process on the host, with these env overrides
        case Container(config: kyo.Container.Config)       // a kyo-pod container: image, env, mounts, cpu/memory, network
    end Isolation

    // The result of a completed task. `result` is the value or a task-level failure; `leaks` are resources the
    // task left open (non-empty means the worker was torn down and a warning logged); `usage` is the measured
    // resource use; `attempts` counts tries including retries. Streaming output goes through `ForkQueue.stream`.
    final case class Outcome[+A, +F](
        result: Result[F, A],
        leaks: Chunk[Leak],
        timing: Timing,
        usage: Usage,
        worker: WorkerInfo,
        attempts: Int
    )

    // The live handles of a streaming forked task, from `ForkQueue.stream`. The coordinator always drains
    // stdout/stderr into bounded buffers, so an unconsumed stream backs up the buffer, never the worker.
    final case class Session[O, A, F](
        emitted: Stream[O, Async],   // values the task emits via Emit[O]
        stdout: Stream[Byte, Async], // the worker process's raw stdout
        stderr: Stream[Byte, Async], // the worker process's raw stderr
        result: Fiber[Outcome[A, F], Abort[InfraWorkersException]]
    )

    // A resource the task left open, by kind, with type-specific detail.
    enum Leak derives CanEqual:
        case Fiber(id: Long, forkedAt: Frame) // forkedAt = where the fiber was started
        case Thread(name: String, id: Long)
        case FileDescriptor(fd: Int, path: Maybe[Path])
        case Socket(protocol: Leak.Protocol, local: Leak.Endpoint, remote: Maybe[Leak.Endpoint])
    end Leak

    object Leak:
        enum Protocol derives CanEqual:
            case Tcp
            case Udp
        end Protocol

        final case class Endpoint(host: String, port: Int) derives CanEqual

        // A leak scan to run after each task. Each kind carries its own allowlist, whose patterns are
        // kind-specific: fork-site frames, thread names, fd paths, socket endpoints.
        enum Check derives CanEqual:
            case Fibers(allowlist: Chunk[String] = Chunk.empty)          // ignore fibers forked at a matching frame
            case Threads(allowlist: Chunk[String] = Chunk.empty)         // ignore threads with a matching name
            case FileDescriptors(allowlist: Chunk[String] = Chunk.empty) // ignore fds with a matching path
            case Sockets(allowlist: Chunk[String] = Chunk.empty)         // ignore sockets with a matching endpoint
        end Check

        object Check:
            val all: Chunk[Check] = Chunk(Fibers(), Threads(), FileDescriptors(), Sockets()) // the default: check everything
        end Check
    end Leak

    // `queued` = time waiting to start; `running` = execution time; `total` = queued + running.
    final case class Timing(queued: Duration, running: Duration, total: Duration) derives CanEqual

    // Peak resource use measured for the task on the worker (sampled via kyo-machine).
    final case class Usage(peakMemory: Long, cpuTime: Duration) derives CanEqual

    // The worker that ran the task; `fresh` is false when a reused warm worker handled it.
    final case class WorkerInfo(label: String, id: Long, fresh: Boolean) derives CanEqual

end ForkQueue
