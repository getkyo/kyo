# kyo-stats-machine

kyo-stats-machine has no Scala call site. There is no `Machine.start`, no handle to hold, no scope to thread. The first useful thing about this module is a build.sbt line: add it to any JVM, JS, or Native module that already runs kyo-core, and a detached fiber wakes once a second to read host CPU time, memory, swap, per-mount disk space, load average, and, on Linux, cgroup and PSI pressure, observing every reading directly into `kyo.Stat` counters, histograms, and gauges under the `machine.` scope.

That makes this module a monitoring contract, not an API. Three things describe it completely: what turns it on (classpath presence), what turns it off (one opt-out lever), and what it emits (a fixed metric taxonomy you query through whatever stats backend your application already wires, such as [kyo-stats-otlp](../kyo-stats-otlp/README.md)). What varies by OS and platform is a metric quietly missing from the exported set, never a fake zero and never a thrown error.

## Adding the module

```scala
// build.sbt
// libraryDependencies += "org.getkyo" %% "kyo-stats-machine" % "<version>"
```

That line is the entire integration. There is no import to add and no method to call: presence on the classpath is the activation event. The moment a `kyo.Stat` counter is first touched anywhere in the process (which kyo-core does eagerly at its own class-init), the service loader discovers `MachineStatFactory`, and constructing that factory starts the sampler.

> **Note:** the sampler is a one-shot, process-lifetime singleton. Once it starts, it runs for the life of the process; there is no stop call, and adding the dependency a second time or touching `Stat` again does not start a second sampler.

The sampler ticks once a second on a drift-corrected schedule (`Clock.repeatAtInterval(Schedule.anchored(1.second))`, anchored so a slow tick does not push the next one late) for as long as the process runs. That is the module's own cadence and it is not configurable. It is also unrelated to how often your metrics backend ships those readings off the process: an exporter like kyo-stats-otlp scrapes the `kyo.Stat` registry on its own schedule (`OTEL_METRIC_EXPORT_INTERVAL`, 60 seconds by default), so a 1-second sampling resolution can still be exported once a minute. If you want finer-grained visibility into the emitted histograms, lower the exporter's interval; kyo-stats-machine's tick period is fixed.

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

- **Gauge**: a point-in-time value read raw on each poll, with no delta. This is the largest group: fixed totals (core count, physical memory total, per-mount capacity), pre-averaged values (load average, PSI `avg10/60/300` percentages), and the cgroup config values that can rise or fall at runtime.
- **Histogram**: a bucketed distribution of per-tick observations. Some histograms observe a point-in-time level each tick (available/free memory and swap, per-mount free space); others observe a per-second delta (CPU usage, cgroup throttling, PSI stall time). A `.rate` histogram's running sum is its own cumulative total, exported under cumulative aggregation temporality so the lifetime total survives every flush, and a rate series therefore needs no separate counter.
- **Counter**: monotonic and cumulative. Exactly one metric is a Counter: `machine.cgroup.cpu.periods`, the scheduling-period count that has no per-second `.rate` companion. Every other cumulative signal is carried inside a `.rate` histogram's sum.

## The machine.* metric taxonomy

Every path below lives under the `machine` scope root. `total`, `free`, and `available` are always bytes; time is always stored in nanoseconds regardless of the OS's native unit, so per-source scaling (jiffies, microseconds, FILETIME 100ns units) happens once, in the sampler, not in every reader. Every cell registers its underlying `kyo.Stat` handle lazily, on its first present observation: a metric the host never produces never registers a handle and is simply absent from the exported set (never a fabricated zero).

### CPU

Per-second CPU-usage histograms, plus the fixed core count. The histogram buckets are derived at init from the host's actual core count, so a many-core host does not funnel every observation into one overflow bucket.

| Path | Type | Unit | Notes |
| --- | --- | --- | --- |
| `machine.cpu.total.rate` | Histogram | ns/s | sum carries cumulative ns |
| `machine.cpu.user.rate` | Histogram | ns/s | sum carries cumulative ns |
| `machine.cpu.system.rate` | Histogram | ns/s | sum carries cumulative ns; on Windows, kernel-minus-idle |
| `machine.cpu.idle.rate` | Histogram | ns/s | sum carries cumulative ns |
| `machine.cpu.iowait.rate` | Histogram | ns/s | Linux only; sum carries cumulative ns |
| `machine.cpu.steal.rate` | Histogram | ns/s | Linux only; hypervisor-steal signal; sum carries cumulative ns |
| `machine.cpu.cores` | Gauge | count | fixed for process lifetime, every OS |

`machine.cpu.steal.rate` is the per-second time a hypervisor gives this host's CPU to another tenant, distinguishing "my CPU is busy" from "my CPU was taken", and it exists on Linux only.

### Memory and swap

Physical totals are Gauges (fixed for the process lifetime); the varying levels are observed into Histograms:

| Path | Type | Unit | Notes |
| --- | --- | --- | --- |
| `machine.memory.total` | Gauge | bytes | physical memory total |
| `machine.memory.available` | Histogram | bytes | Linux `MemAvailable`, macOS free+inactive, Windows `ullAvailPhys` |
| `machine.memory.free` | Histogram | bytes | Linux, macOS; absent on Windows (no distinct free-vs-available concept) |
| `machine.swap.total` | Gauge | bytes | Windows reports the commit limit here, the closest honest mapping |
| `machine.swap.free` | Histogram | bytes | Windows reports the available commit here |

### Disk

Per-mount, so the `<store>` segment is a dynamic scope, not a fixed name: two metrics fan out to every physical mount the sampler discovers. This pair is the only part of the taxonomy that is not a fixed count of leaves.

| Path | Type | Unit | Notes |
| --- | --- | --- | --- |
| `machine.disk.<store>.total` | Gauge | bytes | capacity, one per physical mount |
| `machine.disk.<store>.free` | Histogram | bytes | free space, one per physical mount |

`<store>` is a sanitized mount identity (kernel octal escapes in the mount path are decoded first). The mount set is physical filesystems only (network and virtual mounts are filtered out) and is rebuilt only when the mount table changes, not re-scanned per tick. The disk read runs on its own fiber the tick loop never awaits inline, so a slow or dead mount (a hung NFS export, for instance) never delays the fast CPU/memory/load reads of the same or any later tick.

### Load average

Pre-averaged values, so each is a plain Gauge (a load average can rise or fall, and it is not a distribution to bucket):

| Path | Type | Unit | Notes |
| --- | --- | --- | --- |
| `machine.load.one` | Gauge | load units | Linux, macOS; absent on Windows |
| `machine.load.five` | Gauge | load units | Linux, macOS; absent on Windows |
| `machine.load.fifteen` | Gauge | load units | Linux, macOS; absent on Windows |

### cgroup

Linux only, read at the process's resolved cgroup path (resolved once at init from `/proc/self/mountinfo` and `/proc/self/cgroup`, transparently across v1 and v2). Memory is a usage Histogram plus a limit Gauge; CPU carries the quota/period config as Gauges, the period count as the one Counter, and throttling as per-second Histograms:

| Path | Type | Unit | Notes |
| --- | --- | --- | --- |
| `machine.cgroup.memory.usage` | Histogram | bytes | |
| `machine.cgroup.memory.limit` | Gauge | bytes (config) | Absent when unset or unlimited |
| `machine.cgroup.cpu.quota` | Gauge | ns (config) | Absent when unset |
| `machine.cgroup.cpu.period` | Gauge | ns (config) | |
| `machine.cgroup.cpu.periods` | Counter | count (cumulative) | scheduling periods observed; the one unpaired Counter |
| `machine.cgroup.cpu.throttled.periods.rate` | Histogram | periods/s | sum carries cumulative throttled-period count |
| `machine.cgroup.cpu.throttled.rate` | Histogram | ns/s | sum carries cumulative throttled ns |

> **Note:** `machine.cgroup.memory.limit`, `machine.cgroup.cpu.quota`, and `machine.cgroup.cpu.period` are plain Gauges, not the monotonic CounterGauge you might expect from their neighbors. A config value can rise or fall at runtime (a Kubernetes in-place resize lowering a memory limit, for instance), and a delta-based exporter reading a CounterGauge would map a decrease to a rolling-counter wraparound. The Gauge exports the raw value with no delta, which is the only correct reading for a value that is not monotonic. Each is seeded with its first real value before its handle registers, so its first poll is never a transient zero; an unset config value never registers a handle at all.

### System pressure (PSI)

Linux only, read from `/proc/pressure/{cpu,memory,io}`. Each `(resource, type)` pair carries three pre-averaged EWMA gauges plus a per-second stall-rate histogram:

| Path pattern | Type | Unit | Notes |
| --- | --- | --- | --- |
| `machine.pressure.<res>.<type>.avg10` | Gauge | percent 0-100 | 10-second EWMA |
| `machine.pressure.<res>.<type>.avg60` | Gauge | percent 0-100 | 60-second EWMA |
| `machine.pressure.<res>.<type>.avg300` | Gauge | percent 0-100 | 300-second EWMA |
| `machine.pressure.<res>.<type>.rate` | Histogram | ns/s | per-tick stall-time delta; sum carries cumulative stall ns |

`<res>` ranges over `cpu`, `memory`, `io`; `<type>` ranges over `some`, `full`. Five pairs are emitted: `cpu.some`, `memory.some`, `memory.full`, `io.some`, `io.full`. `cpu.full` is parsed but never emitted, since the kernel pins it at zero on kernels 5.13 and later and it carries no information.

### cgroup pressure (PSI)

Linux cgroup v2 only, read from the resolved cgroup's own `{cpu,memory,io}.pressure` files. Same shape as system pressure, but a distinct scope, so a container's own pressure is never mixed with the host's:

| Path pattern | Type | Unit | Notes |
| --- | --- | --- | --- |
| `machine.cgroup.pressure.<res>.<type>.avg10` | Gauge | percent 0-100 | |
| `machine.cgroup.pressure.<res>.<type>.avg60` | Gauge | percent 0-100 | |
| `machine.cgroup.pressure.<res>.<type>.avg300` | Gauge | percent 0-100 | |
| `machine.cgroup.pressure.<res>.<type>.rate` | Histogram | ns/s | per-tick stall-time delta; sum carries cumulative stall ns |

Same five `(res, type)` pairs as system pressure, same `cpu.full` exclusion. Absent on cgroup v1 hosts and off Linux entirely.

When you need host-wide pressure (contention across every process on the machine), read `machine.pressure.*`. When you need pressure scoped to your own container (contention your cgroup specifically experiences, which can differ sharply from the host under a busy neighbor), read `machine.cgroup.pressure.*`. Both can be present at once on a Linux cgroup v2 host, and they answer different questions.

## Platform and OS coverage

Two independent axes decide what you see: the operating system decides which metric *families* exist, and the compile target decides whether the sampler *runs* at all.

### By operating system

| Family | Linux | macOS | Windows |
| --- | --- | --- | --- |
| CPU total/user/system/idle rate | yes | yes | yes |
| CPU `iowait.rate` | yes | Absent | Absent |
| CPU `steal.rate` | yes | Absent | Absent |
| CPU `cores` | yes | yes | yes |
| Memory total/available | yes | yes | yes |
| Memory `free` | yes | yes | Absent |
| Swap total/free | yes | yes | yes (commit limit) |
| Disk (per mount) | yes | yes | yes |
| Load average | yes | yes | Absent |
| cgroup (memory/cpu) | yes | Absent | Absent |
| System PSI | yes | Absent | Absent |
| cgroup PSI | yes (cgroup v2) | Absent | Absent |

The structural absences are deliberate, and each is the absence of a real host concept, not a gap in coverage:

- **Load average is Absent on Windows.** Windows exposes no load-average equivalent, so the three `machine.load.*` gauges never register there.
- **cgroup and PSI are Linux only.** They are kernel features with no macOS or Windows counterpart, so every `machine.cgroup.*` and `machine.pressure.*` series is absent off Linux, and cgroup PSI additionally requires cgroup v2.
- **`cpu.steal.rate` and `cpu.iowait.rate` are Linux only.** macOS `host_cpu_load_info` has no iowait bucket and neither macOS nor Windows exposes hypervisor steal time, so both series are written and registered only on Linux.
- **`memory.free` is Absent on Windows.** Windows exposes only total and available physical memory (`GlobalMemoryStatusEx`), with no distinct free-versus-available figure; reporting the available number again under a `free` label would be a real number under the wrong meaning, so the cell is never written there.
- **`cpu.full` pressure is never emitted, on any host.** The kernel parses a `full` line for the CPU resource but pins it at zero, so no `machine.pressure.cpu.full.*` or `machine.cgroup.pressure.cpu.full.*` series is ever created. Memory and io emit both `some` and `full`; cpu emits `some` only.

### By compile target

| Target | Status |
| --- | --- |
| JVM | auto-loads via `META-INF/services`, no extra step |
| Scala.js (Node) | auto-loads via `@JSExportTopLevel`; native calls go through a koffi-backed FFI binding; keep `MachineRegistration` referenced so the linker does not tree-shake it (see the Caution above) |
| Scala.js (browser) | degrades: no koffi in a browser environment, so host reads are unavailable |
| Scala Native | requires the `nativeConfig.withServiceProviders(...)` build line above; auto-loads identically to JVM/JS once declared |
| Wasm | held; not yet cross-compiled for this module |

> **Note:** every row above where a family or platform is unsupported means the metric is simply absent from the exported set, structurally, not just by documentation. An OS with no PSI support never creates the PSI handles at all; a browser-JS build never attempts the koffi-backed reads. You will never see a fake zero standing in for "not available here." A metric's total absence from what your backend receives is the signal that the current host or platform does not support it.

## Demos

A runnable demo lives in [`shared/src/test/scala/demo`](shared/src/test/scala/demo). `MachineStatsDemoApp` is the auto-load story end to end: it touches only `kyo.Stat`, lets the classpath-present module start the sampler, waits a few ticks, then prints the `machine.*` families it read off this host and validates them.

```bash
sbt 'kyo-stats-machineJVM/Test/runMain demo.MachineStatsDemoApp'
```

The `Test/runMain` scope runs with auto-start on and prints `validation: OK`: the module's own `test` tasks opt out of the sampler, and `build.sbt` sheds that opt-out only for `run` and `runMain`. Prepend `KYO_MACHINE_DISABLED=true` to watch the lever suppress the sampler: the demo then reports `validation FAILED` and exits non-zero by design. CI runs this same command on Linux, macOS, and Windows hosts to prove auto-start on each.
