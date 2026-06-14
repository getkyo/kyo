package kyo.ffi.bench

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*

/** Shared scaffolding for the kyo-ffi JMH benchmarks.
  *
  * The benchmarks in this module measure FFI call overhead; real C work is kept to a minimum so the numbers isolate the binding cost rather
  * than the callee.
  *
  * To avoid a build-time C compilation step inside this module (which would require wiring `KyoFfiPlugin` into a bench project, and bench
  * runs are intentionally lightweight), the benchmarks bind to POSIX `strlen` and `abs`, which are present in libc on every POSIX-class
  * host and have a stable ABI. This is sufficient to compare binding paths (Panama vs a kyo-ffi-style wrapper) without a library-loading
  * story.
  *
  * On Windows, `libc` is reachable via the default lookup (`msvcrt`/`ucrtbase`); the same code works across platforms without modification.
  *
  * ## JMH harness knobs
  *
  * All benches share the settings wired into this base class so results are comparable across runs:
  *   - `@BenchmarkMode(AverageTime)`, reports ns/op; easier to compare small deltas than throughput.
  *   - `@OutputTimeUnit(NANOSECONDS)`, single unit keeps scale consistent across benches.
  *   - `@State(Benchmark)`, shared state across all threads (benches are single-threaded by default).
  *   - `@Warmup(3 x 500ms)` / `@Measurement(3 x 500ms)`, deliberately short; each bench takes ~3s per method. Override per-bench if a
  *     specific bench needs more warmup to stabilise (e.g. reflection-heavy paths that take longer to JIT).
  *   - `@Fork(1)`, single JVM run. Multi-fork (value=3+) is the right call for publication-quality numbers; the harness defaults to 1 so
  *     CI sanity checks complete quickly and the `BENCH.md` recipe stays honest about what a single-fork run measures.
  *   - `@Threads(1)`, single-threaded baseline. Concurrency benches override this locally.
  *
  * The command-line equivalents (`-wi`, `-i`, `-r`, `-w`, `-f`, `-t`) override these defaults, see `kyo-ffi/BENCH.md`.
  */
@State(Scope.Benchmark)
@Fork(
    value = 1,
    jvmArgs = Array(
        "--enable-native-access=ALL-UNNAMED"
    )
)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Threads(1)
abstract class BenchBase:

    /** A process-wide linker, cheap to obtain, reuse across benchmarks. */
    protected val linker: Linker = Linker.nativeLinker()

    /** Default symbol lookup, walks the process image so libc symbols resolve. */
    protected val lookup: SymbolLookup = linker.defaultLookup()

    /** A shared arena for hot-path allocations. Closed on JVM shutdown, the bench process is short-lived so leak impact is negligible.
      */
    protected val sharedArena: Arena = Arena.ofShared()

end BenchBase
