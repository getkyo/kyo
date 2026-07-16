package kyo.stats.machine

import kyo.*

/** JS/Wasm run the whole-disk read inline: they are single-threaded and have no OS thread to off-load a
  * synchronous FFI read to, and the parked-scheduler-worker hazard that motivates the JVM/Native dedicated
  * thread (see the sibling implementation under `jvm-native/`) does not arise on the JS event loop. This keeps
  * the pre-existing behavior behind the same `run`/`close` seam the shared sampler calls.
  */
final private[machine] class DiskExecutor:

    def run(body: AllowUnsafe ?=> Unit)(using Frame): Unit < Async =
        Sync.Unsafe.defer(body)

    def close(): Unit = ()

end DiskExecutor
