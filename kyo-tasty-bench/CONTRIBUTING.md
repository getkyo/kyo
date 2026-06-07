# kyo-tasty-bench contributor guide

This file documents the conventions and constraints for adding benchmarks to
`kyo-tasty-bench`. Read the root `CONTRIBUTING.md` and `kyo-tasty/CONTRIBUTING.md`
first; everything there applies here.

---

## Module purpose

`kyo-tasty-bench` is a JVM-only standalone bench harness for `kyo-tasty`. It is
not a JMH module; each bench is a runnable program that prints timing results to
stdout. The module has no test sources; its only build target is `run` /
`runMain`.

All source lives in `jvm/src/main/scala/kyo/bench/`.

---

## Entry-point convention

Every bench object extends `KyoApp` and declares a `run { ... }` block:

```scala
object MyBench extends KyoApp:
    run {
        // effect-tracked code here
    }
```

`run { ... }` is the single entry point. Do not add a `main` method separately.

---

## Time measurement

Use `Clock.nowMonotonic` for all timing. The established `timed` / `bench`
helpers from `TastyBench` and `ColdLoadBench` are the template:

```scala
private def timed(action: => Unit < Sync)(using Frame): Duration < Sync =
    Clock.nowMonotonic.map { start =>
        action.map { _ =>
            Clock.nowMonotonic.map { end =>
                end - start
            }
        }
    }

private def bench(name: String, warmup: Int, measure: Int)(action: => Unit < Sync)(using Frame): Chunk[Duration] < Sync =
    Kyo.foreachDiscard(Chunk.from(0 until warmup)) { _ =>
        action
    }.map { _ =>
        Kyo.foreach(Chunk.from(0 until measure)) { _ =>
            timed(action)
        }.map { durations =>
            val sorted = durations.sortBy(_.toNanos)
            val median = sorted(measure / 2)
            val p95    = sorted((measure * 95 / 100).min(measure - 1))
            Console.printLine(
                f"[$name] median=${median.toNanos / 1_000_000.0}%.2f ms  p95=${p95.toNanos / 1_000_000.0}%.2f ms"
            ).map(_ => sorted)
        }
    }
```

Copy this pattern; do not invent a new one.

---

## I/O conventions

| Operation                              | Use                                        |
|----------------------------------------|--------------------------------------------|
| Print results                          | `Console.printLine` / `Console.printLineErr` |
| File walk, read, size                  | `kyo.Path` (`walk`, `readBytes`, `size`, `readLines`, `tempDir`) |
| Dev-machine classes directory          | `System.env[Path]("KYO_BENCH_CLASSES_DIR")` with `Path.cwd`-relative fallback |

---

## Permitted Java carve-outs

The following Java APIs are used directly in this module. These are documented
gaps in `kyo-core`; do not add new Java carve-outs without updating the
kyo-core gaps document.

1. **`java.util.jar.JarFile`** (in `TastyBench.buildFixtureSource`): reads jar
   central-directory entries to discover `.tasty` fixture files. `kyo.Path` has
   no jar-read API.

2. **`java.net.URLClassLoader.getURLs`** (in `TastyBench.buildFixtureSource`):
   walks the classloader hierarchy to discover classpath entries. There is no
   cross-platform classloader-walk API in Kyo.

3. **`java.nio.file.Paths.get` / `Files.isDirectory` / `Files.list` /
   `Files.isRegularFile` / `Files.readAllBytes`** (in `TastyBench.buildFixtureSource`):
   used inside the classpath-entry directory scan. These will be replaceable with
   `kyo.Path` once `Path.size` and `Path.walk` are exercised here; the scan
   predates the addition of those APIs and is a refactor candidate.

4. **`java.util.ArrayList` / `java.util.Arrays` accumulators** (in
   `ColdLoadFullBench`): used as mutable accumulators inside the jar-path
   collection loop. They are local to the loading block and have no external
   surface.

No other Java standard-library types are permitted without an explicit gap
note.

---

## Path-based env-var resolution pattern

Bench programs that need a dev-machine artifact directory resolve it as follows:

```scala
System.env[Maybe[String]]("KYO_BENCH_CLASSES_DIR").map {
    case Present(dir) => Path(dir)
    case Absent       => Path.cwd / "kyo-bench" / ".jvm" / "target" / "scala-3.8.3" / "classes"
}
```

Always use `Path.cwd` (a `Sync` effect) for the fallback, not a hardcoded
absolute path.

---

## Adding a new bench

1. Create a new file `jvm/src/main/scala/kyo/bench/MyBench.scala`.
2. `object MyBench extends KyoApp`.
3. Implement a `run { ... }` block that calls `bench(...)` with a `timed` helper
   copied from `TastyBench` or `ColdLoadBench`.
4. Document the intended profiler invocation in the scaladoc comment on the
   object.
5. Verify it compiles: `sbt 'kyo-tasty-bench/Test/compile'` (bench programs are
   compiled under `Test/compile` in the sbt build).
