# kyo-stats-machine

kyo-stats-machine has no Scala call site. There is no `Machine.start`, no handle to hold, no scope to thread. The first useful thing about this module is a build.sbt line: add it to any JVM, JS, or Native module that already runs kyo-core, and a detached fiber wakes once a second to read host CPU time, memory, swap, per-mount disk space, load average, and, on Linux, cgroup and PSI pressure, observing every reading directly into `kyo.Stat` counters and histograms under the `machine.` scope.

That makes this module a monitoring contract, not an API. Three things describe it completely: what turns it on (classpath presence), what turns it off (one opt-out lever), and what it emits (a fixed metric taxonomy you query through whatever stats backend your application already wires, such as [kyo-stats-otlp](../kyo-stats-otlp/README.md)). What varies by OS and platform is a metric quietly missing from the exported set, never a fake zero and never a thrown error.

## Adding the module

```scala
// build.sbt
// libraryDependencies += "org.getkyo" %% "kyo-stats-machine" % "<version>"
```

That line is the entire integration. There is no import to add and no method to call: presence on the classpath is the activation event. The moment a `kyo.Stat` counter is first touched anywhere in the process (which kyo-core does eagerly at its own class-init), the service loader discovers `MachineStatFactory`, and constructing that factory starts the sampler.

> **Note:** the sampler is a one-shot, process-lifetime singleton. Once it starts, it runs for the life of the process; there is no stop call, and adding the dependency a second time or touching `Stat` again does not start a second sampler.

The sampler ticks once a second (`Loop.forever(Async.sleep(1.second)...)`) for as long as the process runs. That is the module's own cadence and it is not configurable. It is also unrelated to how often your metrics backend ships those readings off the process: an exporter like kyo-stats-otlp scrapes the `kyo.Stat` registry on its own schedule (`OTEL_METRIC_EXPORT_INTERVAL`, 60 seconds by default), so a 1-second sampling resolution can still be exported once a minute. If you want finer-grained visibility into the emitted histograms, lower the exporter's interval; kyo-stats-machine's tick period is fixed.

> **Note:** kyo-stats-machine is a producer only. Adding it with no exporter on the classpath records every metric into the in-process registry, but nothing ships anywhere observable. Pair it with an exporter module such as kyo-stats-otlp, or the effect of adding this dependency is invisible.

On the JVM, that is the whole story: the JVM discovers the factory through `META-INF/services`. On Scala.js, registration goes through the same `@JSExportTopLevel` mechanism kyo-stats-otlp uses: a single public object, `MachineRegistration`, calls `JSServiceLoaderRegistry.register` in a `@JSExportTopLevel`-annotated field. It is the one top-level symbol in this module that is not `private[kyo]`, because the JS runtime has to be able to reach it. Scala Native needs one more step, covered below.

> **Caution:** the Scala.js linker may tree-shake `MachineRegistration` if nothing references it, which drops the auto-load registration and disables sampling on JS with no error. If telemetry silently never starts on JS, force a reference (`val _ = MachineRegistration.init`) somewhere in your application's entry point, keeping the object retained.

### Scala Native: a build-time precondition

Scala Native's `ServiceLoader` only discovers providers named in a build-time allowlist; dead-code elimination drops the factory class entirely if nothing in the build references it. On every other platform, adding the dependency is sufficient. On Native, the downstream application's `build.sbt` must also register the provider explicitly:

```scala doctest:expect=skipped
// build.sbt (Scala Native targets only)
nativeConfig ~= {
    _.withServiceProviders(Map(
        "kyo.stats.internal.ExporterFactory" -> Seq("kyo.stats.machine.MachineStatFactory")
    ))
}
```

> **Caution:** without this line, kyo-stats-machine compiles and links cleanly on Native but never samples anything. There is no error, no warning, and no metric: the sampler simply never starts. This is the one platform where adding the library dependency is not enough by itself.

## Turning it off

The sampler has exactly one lever: an opt-out, read once when the factory is constructed.

```bash
export KYO_MACHINE_DISABLED=true
```

or the equivalent system property:

```scala
// -Dkyo.machine.disabled=true
```

Either suppresses the sampler start. Both are read exactly once, at registration time; there is no runtime toggle. Setting the variable after the process has already started the sampler has no effect, because the read already happened.

> **Note:** an unset or unparseable value enables the sampler. This is a fail-open default, not a fail-safe one: if you meant to disable monitoring and misspelled the variable name or the value, the sampler starts anyway. Only the literal string `"true"` (case-insensitive) disables it.

## Reading the metrics

There is no Scala value to read. You consume `machine.*` the same way you consume any other `kyo.Stat` metric your application records: through whatever stats backend or exporter you already have wired, using `machine` as the scope root (`Stat.scope("machine")`). The scope root is a plain string, independent of the module's Scala package (`kyo.stats.machine`) and independent of an exporter's own operational scope. If you pair this module with kyo-stats-otlp, for example, that exporter records its own health counters under `kyo.stats.otel.*`; keep the two prefixes distinct when you query a backend, since one is host telemetry this module produces and the other is the exporter's own health telemetry.

Every metric in the taxonomy is one of three `kyo.Stat` types:

- **Counter**: monotonic and cumulative. Advances by a per-tick delta; a query against it gives the running total since the sampler started.
- **Histogram**: a bucketed distribution of per-tick observations. Some histograms observe a point-in-time reading each tick (memory, disk, load); others observe a per-second delta (CPU usage, cgroup throttling, PSI stall time).
- **Gauge**: a point-in-time value with no delta, read raw on each poll. Used only for the handful of cgroup config values that can rise or fall at runtime.

> **Note:** every cumulative time or count Counter (a leaf named `.total`) is paired with a per-second-delta Histogram (a sibling leaf named `.rate`) under the same base path, never as parent and child. `machine.cpu.total.total` is the Counter; `machine.cpu.total.rate` is its paired Histogram. If you query only the `.total` Counter, you get the cumulative window average and silently miss any within-window spike; this is exactly the case for cgroup CPU throttling and PSI stall time, where a brief but severe stall can vanish into an averaged total. Expect both to exist for every cumulative family: `.rate` is not an alternate unit of `.total`, it is the distribution `.total` alone cannot show you.

> **Caution:** the three cgroup config-value metrics (`machine.cgroup.memory.limit`, `machine.cgroup.cpu.quota`, `machine.cgroup.cpu.period`) are plain Gauges, not the monotonic CounterGauge you might expect from their neighbors. A config value can rise or fall at runtime (a Kubernetes in-place resize lowering a memory limit, for instance), and a delta-based exporter reading a CounterGauge maps a decrease to a rolling-counter wraparound. The Gauge exports the raw value with no delta, which is the only correct reading for a value that is not monotonic. `machine.cpu.cores` stays a CounterGauge, because a process's core count is fixed for its lifetime and never decreases.

## The machine.* metric taxonomy

Every path below lives under the `machine` scope root. `total` and `free`/`available` are always bytes; time is always stored in nanoseconds regardless of the OS's native unit, so per-source scaling (jiffies, microseconds) happens once, in the sampler, not in every reader.

### CPU

Cumulative cpu-time Counters, each paired with a per-second-usage Histogram, plus the fixed core count:

| Path | Type | Unit | Notes |
| --- | --- | --- | --- |
| `machine.cpu.total.total` | Counter | ns (cumulative) | paired with `.rate` |
| `machine.cpu.total.rate` | Histogram | ns/s | per-tick delta |
| `machine.cpu.user.total` | Counter | ns (cumulative) | per-mode where cheap to read |
| `machine.cpu.user.rate` | Histogram | ns/s | paired with `.total` |
| `machine.cpu.system.total` | Counter | ns (cumulative) | per-mode where cheap to read |
| `machine.cpu.system.rate` | Histogram | ns/s | paired with `.total` |
| `machine.cpu.idle.total` | Counter | ns (cumulative) | per-mode where cheap to read |
| `machine.cpu.idle.rate` | Histogram | ns/s | paired with `.total` |
| `machine.cpu.iowait.total` | Counter | ns (cumulative) | Linux only |
| `machine.cpu.iowait.rate` | Histogram | ns/s | Linux only, paired with `.total` |
| `machine.cpu.cores` | CounterGauge | count | fixed for process lifetime, every OS |

### Memory and swap

Point-in-time readings observed into Histograms:

| Path | Type | Unit | Notes |
| --- | --- | --- | --- |
| `machine.memory.total` | Histogram | bytes | |
| `machine.memory.available` | Histogram | bytes | Linux `MemAvailable`; Absent where the OS does not expose it |
| `machine.memory.free` | Histogram | bytes | |
| `machine.swap.total` | Histogram | bytes | |
| `machine.swap.free` | Histogram | bytes | |

### Disk

Per-mount, so the `<store>` segment is a dynamic scope, not a fixed name: two metrics fan out to every physical mount the sampler discovers at init.

| Path | Type | Unit | Notes |
| --- | --- | --- | --- |
| `machine.disk.<store>.total` | Histogram | bytes | one pair per physical mount |
| `machine.disk.<store>.free` | Histogram | bytes | one pair per physical mount |

`<store>` is a sanitized mount identity, and the mount set (physical filesystems only, network and virtual mounts filtered out) is fixed once at sampler init, not re-scanned per tick.

### Load average

| Path | Type | Unit | Notes |
| --- | --- | --- | --- |
| `machine.load.one` | Histogram | load units | Linux, macOS; Absent on Windows |
| `machine.load.five` | Histogram | load units | Linux, macOS; Absent on Windows |
| `machine.load.fifteen` | Histogram | load units | Linux, macOS; Absent on Windows |

### cgroup

Linux only, read at the process's resolved cgroup path, transparently across v1 and v2. Memory is a usage Histogram plus a limit Gauge; CPU carries the quota/period config as Gauges and throttling as a dual Counter/Histogram pair:

| Path | Type | Unit | Notes |
| --- | --- | --- | --- |
| `machine.cgroup.memory.usage` | Histogram | bytes | |
| `machine.cgroup.memory.limit` | Gauge | bytes (config) | Absent when unset or unlimited |
| `machine.cgroup.cpu.quota` | Gauge | ns (config) | Absent when unset |
| `machine.cgroup.cpu.period` | Gauge | ns (config) | |
| `machine.cgroup.cpu.periods` | Counter | count (cumulative) | scheduling periods observed |
| `machine.cgroup.cpu.throttled.periods.total` | Counter | count (cumulative) | paired with `.rate` |
| `machine.cgroup.cpu.throttled.periods.rate` | Histogram | periods/s | per-tick delta |
| `machine.cgroup.cpu.throttled.total` | Counter | ns (cumulative) | paired with `.rate` |
| `machine.cgroup.cpu.throttled.rate` | Histogram | ns/s | per-tick delta |

> **Note:** `machine.cgroup.memory.limit`, `machine.cgroup.cpu.quota`, and `machine.cgroup.cpu.period` do not even create their Gauge handle until the first present reading is observed. An unset config value never emits a fabricated zero; it is simply absent from the exported set until (or unless) it becomes readable.

### System pressure (PSI)

Linux only, read from `/proc/pressure/{cpu,memory,io}`. Each `(resource, type)` pair carries three EWMA gauges plus a dual cumulative-stall Counter/Histogram pair:

| Path pattern | Type | Unit | Notes |
| --- | --- | --- | --- |
| `machine.pressure.<res>.<type>.avg10` | Histogram | percent 0-100 | 10-second EWMA |
| `machine.pressure.<res>.<type>.avg60` | Histogram | percent 0-100 | 60-second EWMA |
| `machine.pressure.<res>.<type>.avg300` | Histogram | percent 0-100 | 300-second EWMA |
| `machine.pressure.<res>.<type>.total` | Counter | ns (cumulative stall) | paired with `.rate` |
| `machine.pressure.<res>.<type>.rate` | Histogram | ns/s | per-tick stall-time delta |

`<res>` ranges over `cpu`, `memory`, `io`; `<type>` ranges over `some`, `full`. Five pairs are emitted: `cpu.some`, `memory.some`, `memory.full`, `io.some`, `io.full`. `cpu.full` is parsed but never emitted, since the kernel pins it at zero on kernels 5.13 and later and it carries no information.

### cgroup pressure (PSI)

Linux cgroup v2 only, read from the resolved cgroup's own `{cpu,memory,io}.pressure` files. Same shape as system pressure, but a distinct scope, so a container's own pressure is never mixed with the host's:

| Path pattern | Type | Unit | Notes |
| --- | --- | --- | --- |
| `machine.cgroup.pressure.<res>.<type>.avg10` | Histogram | percent 0-100 | |
| `machine.cgroup.pressure.<res>.<type>.avg60` | Histogram | percent 0-100 | |
| `machine.cgroup.pressure.<res>.<type>.avg300` | Histogram | percent 0-100 | |
| `machine.cgroup.pressure.<res>.<type>.total` | Counter | ns (cumulative stall) | paired with `.rate` |
| `machine.cgroup.pressure.<res>.<type>.rate` | Histogram | ns/s | per-tick stall-time delta |

Same five `(res, type)` pairs as system pressure, same `cpu.full` exclusion. Absent on cgroup v1 hosts and off Linux entirely.

When you need host-wide pressure (contention across every process on the machine), read `machine.pressure.*`. When you need pressure scoped to your own container (contention your cgroup specifically experiences, which can differ sharply from the host under a busy neighbor), read `machine.cgroup.pressure.*`. Both can be present at once on a Linux cgroup v2 host, and they answer different questions.

## Platform and OS coverage

Two independent axes decide what you see: the operating system decides which metric *families* exist, and the compile target decides whether the sampler *runs* at all.

### By operating system

| Family | Linux | macOS | Windows |
| --- | --- | --- | --- |
| CPU | full, including `iowait` | full, no `iowait` | full, no `iowait` |
| Memory / swap | full | full | full |
| Disk | full | full | full |
| Load average | yes | yes | Absent |
| cgroup | yes | Absent | Absent |
| PSI (system + cgroup) | yes | Absent | Absent |

### By compile target

| Target | Status |
| --- | --- |
| JVM | auto-loads via `META-INF/services`, no extra step |
| Scala.js (Node) | auto-loads via `@JSExportTopLevel`; native calls go through a koffi-backed FFI binding; keep `MachineRegistration` referenced so the linker does not tree-shake it (see the Caution above) |
| Scala.js (browser) | degrades: no koffi in a browser environment, so host reads are unavailable |
| Scala Native | requires the `nativeConfig.withServiceProviders(...)` build line above; auto-loads identically to JVM/JS once declared |
| Wasm | held; not yet cross-compiled for this module |

> **Note:** every row above where a family or platform is unsupported means the metric is simply absent from the exported set, structurally, not just by documentation. An OS with no PSI support never creates the PSI handles at all; a browser-JS build never attempts the koffi-backed reads. You will never see a fake zero standing in for "not available here." A metric's total absence from what your backend receives is the signal that the current host or platform does not support it.
