# Contributing to kyo-stats-machine

Module-specific guide for kyo-stats-machine. Read the repository-root
[CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming
rules, type vocabulary, test patterns, and the unsafe-boundary tiers that apply
across all of Kyo. This document records only what is specific to
kyo-stats-machine: the fully-generic-per-OS architecture, the auto-load
mechanism, the metric model, the sampler's zero-alloc/lifecycle contract, the
per-OS FFI conventions, graceful degradation, and the test pattern.

## What kyo-stats-machine is

kyo-stats-machine reads host machine metrics (cpu time, memory, swap, disk,
load, cgroup, PSI) once per second and records them into `kyo.Stat` under the
`machine.*` taxonomy. It sits at the Applications layer, depends on `kyo-ffi`,
and auto-starts on classpath presence with zero user call, the same
service-loader-triggered pattern `kyo-stats-otlp` uses for its own background
exporter.

## The headline invariant: fully generic, one impl per OS

There is exactly ONE `Machine` implementation per operating system
(`MachineLinux`, `MachineMacos`, `MachineWindows`), and every one of them lives
in `shared/src/main/scala`, selected once at sampler init from
`System.operatingSystem` (`Machine.forOs`,
`shared/src/main/scala/kyo/stats/machine/Machine.scala:32-37`). This works
because every implementation composes only cross-platform kyo primitives:
files are read via `kyo.Path` (never a platform-specific file API), and
genuine syscalls go through a per-OS **kyo-ffi** binding
(`LinuxBindings`/`MacosBindings`/`WindowsBindings`), never bundled ad hoc per
platform. The `Machine` trait's own doc states this precisely: "Every
implementation compiles on every platform because it composes only
cross-platform kyo primitives" (`Machine.scala:9-11`).

The ONLY per-platform Scala leaves in the whole module are the two files the
auto-registration mechanism genuinely requires:

- `js/src/main/scala/kyo/stats/machine/MachineRegistration.scala` (JS/Wasm
  `@JSExportTopLevel` registration; JVM/Native use classpath
  `META-INF/services` discovery instead, so they need no such file).
- `jvm/src/test/scala/kyo/stats/machine/MachineSamplerJvmTest.scala` (a
  `java.lang.management`-based allocation probe; the JDK reflection API it
  uses does not exist on JS/Native).

Everything else, including all three `Machine` impls, every decoder, the
sampler, the handle set, and every per-OS FFI binding trait, is
`shared/src/main`. A change that adds a `jvm/`, `js/`, or `native/` leaf
outside those two exceptions is very likely the wrong shape: reach for
`kyo.Path` and a kyo-ffi binding first.

Every top-level type in the module is `private[kyo]` (`Machine`,
`MachineSampler`, `MachineHandles`, `MachineStatFactory`, every `<Os>Bindings`
trait, every decoder object), with one required exception:
`MachineRegistration` (`js/src/main/scala/kyo/stats/machine/
MachineRegistration.scala`) is a bare, default-visibility `object` because it
is the JS auto-load registration entry point, exported to the JS runtime via
`@JSExportTopLevel`; `private[kyo]` is not visible from JS, so this one object
cannot be narrowed. Nothing else in this module is public API; the only
externally-visible effect is metrics appearing under `kyo.Stat`'s
`machine.*` namespace and the `KYO_MACHINE_DISABLED` / `kyo.machine.disabled`
opt-out.

## Auto-load mechanism

The sampler starts with zero explicit user call, mirroring
`kyo-stats-otlp`'s `OTLPExporterFactory` / `OTLPInitPlatform` shape:

1. Classpath presence of `MachineStatFactory` triggers construction: JVM/Native
   via `META-INF/services/kyo.stats.internal.ExporterFactory`, forced eagerly
   by `kyo.Stat`'s class-init scan (`eagerExporterScan`, see "kyo-core
   touches" below); JS/Wasm via `MachineRegistration`'s
   `@JSExportTopLevel`-annotated registration object, which calls
   `JSServiceLoaderRegistry.register`
   (`js/src/main/scala/kyo/stats/machine/MachineRegistration.scala:13-19`).
2. Construction reads the opt-out once and, unless suppressed, starts exactly
   one sampler via a CAS-gated `AtomicBoolean`
   (`MachineStatFactory.started`, `shared/src/main/scala/kyo/stats/machine/MachineStatFactory.scala:39,72-84`).
   The factory contributes no `TraceExporter` (`traceExporter()` always
   returns `None`, `MachineStatFactory.scala:30`): the SPI seam is used purely
   as an on-classpath start trigger, not for tracing.
3. The started sampler is a detached fiber (`Fiber.initUnscoped`) running
   `MachineSampler.run` under its own `Scope`, ticking once per second
   (`Async.sleep(1.seconds).andThen(tick(sampler))` inside `Loop.forever`,
   `MachineSampler.scala:74-83`).
4. Opt-out: `KYO_MACHINE_DISABLED=true` (env) or `kyo.machine.disabled=true`
   (system property), read once via an injectable `System.Unsafe` so tests can
   stage a reader without mutating real process env
   (`MachineStatFactory.scala:52-64`). Unset or unparseable enables (graceful
   default, never a failure).
5. **Native caveat**: Scala Native's `ServiceLoader` discovers providers only
   from a build-time allowlist. A downstream Native build that wants the
   sampler MUST declare
   `nativeConfig ~= { _.withServiceProviders(Map("kyo.stats.internal.ExporterFactory" -> Seq("kyo.stats.machine.MachineStatFactory"))) }`
   (documented at `MachineStatFactory.scala:16-21`); absent that declaration,
   the provider is discovered but never constructed and sampling silently
   does not start on that platform. JVM and JS need no such step.

## The metric model

Every metric lives under the `machine.*` `Stat` scope, created once in
`MachineHandles` under `Stat.scope("machine")`
(`shared/src/main/scala/kyo/stats/machine/MachineHandles.scala`).

**The dual rule.** Every cumulative cpu-time quantity is a `Counter` (raw
nanoseconds, monotonic) PAIRED with a per-second-delta `Histogram` observed
with the exact same per-tick scaled delta: `cpu.<mode>.total` beside
`cpu.<mode>.rate` (`MachineHandles.scala:22-31`), and the same pairing for
cgroup cpu-throttling (`cgCpuThrPeriods`/`cgCpuThrPeriodsHi`,
`cgCpuThrTime`/`cgCpuThrTimeHi`, `MachineHandles.scala:52-55`) and both PSI
families' stall totals (`PsiHandles.One`, `shared/src/main/scala/kyo/stats/machine/PsiHandles.scala:33-59`).
The delta computation lives once, in `MachineHandles.advance`/`step`
(`MachineHandles.scala:129-171`): first tick advances by 0 (no absolute-value
spike), a negative raw delta clamps to 0 (a kernel counter reset), and an
Absent read leaves both the `Counter` and the prior-state baseline unchanged.
`cgroup.cpu.periods` is the one deliberately UNPAIRED counter (the taxonomy
declares no dual for it, `advanceCounter`, `MachineHandles.scala:144-155`).

**Point-in-time values are `Histogram`s**, not `Counter`s: memory,
swap, load, disk free/total, and PSI `avgNN` percentages are observed
directly with no delta (`MachineHandles.scala:33-45,229-237`).

**Config values are `Gauge`s, never `Counter`s.** `cgroup.memory.limit`,
`cgroup.cpu.quota`, and `cgroup.cpu.period` are point-in-time config values
that can rise OR fall at runtime (a cgroup limit lowered live); a
`CounterGauge`'s delta model would map a decrease to a wraparound, so each is
a plain `Gauge` (raw value, no delta) instead. Each is created LAZILY, on the
first `Present` observation only: an unset limit registers no handle and
exports nothing, and the handle is seeded with that first value so its first
poll is never a transient 0 (`MachineHandles.ConfigGauge`,
`MachineHandles.scala:57-64,199-223`).

cgroup v1/v2 detection and PSI are Linux-only: v2 unified is chosen iff
`/sys/fs/cgroup/cgroup.controllers` exists (`LinuxCgroup.isV2`,
`shared/src/main/scala/kyo/stats/machine/LinuxCgroup.scala:23`), else v1
legacy; the resolved process cgroup path is read from `/proc/self/cgroup`,
not the hierarchy root (`LinuxCgroup.resolvedV2Dir`/`resolveV1`,
`LinuxCgroup.scala:26-36`), because v2 resource-control files exist only on
non-root cgroups. System PSI (`/proc/pressure/*`) and cgroup v2 PSI (the
resolved dir's `*.pressure`) are recorded into two SEPARATE families
(`LinuxPressure.readSystem`/`readCgroup`,
`shared/src/main/scala/kyo/stats/machine/LinuxPressure.scala:11-18`) so a
consumer can distinguish system-wide pressure from this cgroup's pressure and
neither `.total` Counter double-advances from one read.

## The sampler contract

`MachineSampler` (`shared/src/main/scala/kyo/stats/machine/MachineSampler.scala`)
is the single detached owner fiber for the whole tick loop. Its contract:

- **Zero-allocation steady-state reads.** The sampler owns one
  `Path.ReadHandle` per proc file, opened once and retained in a
  `FileSlot` (`MachineSampler.scala:115,124-129`); each tick calls
  `handle.position(0L)` to rewind and reads into the SAME reused byte buffer
  (`readScoped`, `MachineSampler.scala:45-57`). On JVM/Native this rides the
  kyo-core `NioReadHandle`'s retained `ByteBuffer` (see "kyo-core touches"
  below), so a steady-state read allocates no per-read payload. `fill`
  (`MachineSampler.scala:138-158`) reuses a fixed scratch array across
  `readChunk` calls and only grows the output buffer once, the first time a
  file exceeds it; the borrowed `Span[Byte]` handed to the caller's callback
  must never escape that callback.
- **`Scope`-teardown lifecycle.** `MachineSampler.run` builds the sampler
  and its handles UNDER one `Scope`, then drives the tick loop with
  `Scope.ensure` registering `closeHandles()` as the finalizer
  (`MachineSampler.scala:74-83`). An interrupt of this effect stops the loop
  FIRST; the `Scope` finalizer then closes every retained handle, so no tick
  ever reads a closed handle. The loop itself keeps its own `Scope` open, so
  no separate keep-alive construct is needed.
- **The blockable disk read is isolated and bounded.** Every other read is a
  fast in-kernel/proc read; disk enumeration is the one genuinely-blockable
  syscall family (`statvfs`/`statfs`/`GetDiskFreeSpaceExW` against a possibly
  hung or dead mount). `MachineSampler.tick` reads it on the SAME fiber as
  everything else but wraps the call in `Async.timeout(diskReadTimeout)`
  (5 seconds, `MachineSampler.scala:90,104-107`): on expiry the tick records
  no disk data for that tick and the loop proceeds rather than parking. This
  is why `Machine.read` always returns `disks = Chunk.empty` and disk is read
  through the separate `readDisks` method
  (`Machine.scala:14-24`): so the bounded, potentially-timed-out path is
  structurally distinct from the fast synchronous path.

## The FFI conventions

Each OS has its own `Ffi` binding trait, all `private[machine]`, all
following kyo-ffi's unsafe-tier recipe (see kyo-ffi's own CONTRIBUTING.md):
`(using AllowUnsafe)` trailing every method, a bare-value return, failure
surfaced by throwing or a non-zero/negative return code that the `Machine`
impl catches to `Absent`.

- **`LinuxBindings`** (`shared/src/main/scala/kyo/stats/machine/LinuxBindings.scala`):
  `Ffi.Config(library = "c")`, a system-library binding with NO bundled C.
  Binds `statvfs` (per-mount free/total) and `sysconf(_SC_CLK_TCK)` (the
  jiffies-to-nanoseconds scale).
- **`MacosBindings`** (`shared/src/main/scala/kyo/stats/machine/MacosBindings.scala`):
  `Ffi.Config(library = "machine_macos", symbolPrefix = "machine_macos_", nativeBundled = true)`.
  A real `statfs`/`vm_statistics64`/`host_cpu_load_info` carries nested and
  array fields the FFI struct layer rejects, so a bundled C **projection
  shim**, `shared/src/main/c/machine_macos.c`, flattens each syscall's wanted
  fields into flat primitive out-params the binding can read with raw
  `Buffer.get` calls. The whole shim is `#ifdef __APPLE__`-guarded: the
  `#else` branch provides same-signature stubs returning failure codes
  (`machine_macos.c:110-124`), so the file compiles and every symbol resolves
  on a non-macOS build host (the Linux-only CI compiles this on the BUILD
  HOST for JVM/JS; Scala Native compiles it into the binary on every OS). The
  module's `build.sbt` registers it via `nativeBundled` plus an explicit
  `FfiLibrary` entry naming the C source
  (`build.sbt:1131-1140`), since `library = "machine_macos"` is not a system
  library id.
- **`WindowsBindings`** (`shared/src/main/scala/kyo/stats/machine/WindowsBindings.scala`):
  `Ffi.Config(library = "kernel32", headers = Chunk("windows.h"), symbols = Map(...))`.
  Bound directly against `kernel32`, no bundled C. The `symbols` map pins
  each Scala method to its EXACT Win32 export name (the derived snake-case
  name would not resolve, and `GetDiskFreeSpaceEx` is a macro, not an export,
  so the `W` form is named explicitly). `headers = Chunk("windows.h")` gates
  Native `@extern` emission, so a non-Windows Native build emits runtime
  stubs instead of a `@link("kernel32")` that would fail to link.

Every binding uses raw `Buffer.get`/`Buffer.alloc` accessors for its
out-params, never `StructLayout`: each syscall's wanted fields are
hand-projected into a flat `Buffer[Long]`/`Buffer[Double]` scratch area (see
`MacosBindings.hostCpuLoad`'s doc, `MacosBindings.scala:14`, and every
`Machine` impl's `readCpu`/`readMemory`/etc.). Every `Buffer.alloc` call is
matched with a `Buffer.close()` on EVERY exit path, success or failure
(`try ... finally out.close()` throughout `MachineMacos.scala` and
`MachineWindows.scala`; `WindowsBindings.withMemoryStatus`,
`WindowsBindings.scala:82-100`, closes on both the false-return path and a
thrown-before-success path).

Every `AllowUnsafe.embrace.danger` import carries a `// Unsafe:` comment
naming the specific bridge it opens: the sampler's retained handles and
buffer (`MachineSampler.scala:19-20`), the module-init SPI activation
boundary (`MachineStatFactory.scala:25-26,35-36`), the per-OS reader's
syscall/file bridge (`MachineLinux.scala:16-17`, `MachineMacos.scala:13-14`,
`MachineWindows.scala:13-14`), and the `ConfigGauge`'s off-effect-context
construction (`MachineHandles.scala:217-219`).

## Graceful degradation

There is no public `Machine.snapshot`/`Snapshot` read primitive: the sampler
observes availability straight into (or skips) retained `kyo.Stat` handles.
An unavailable metric is `Absent`, NEVER a fake zero and NEVER a throw
(`Machine.scala:5-11`). This holds at every layer:

- **Per-field decode totality.** Every parser in `LinuxDecoders`,
  `LinuxCgroupDecode`, `LinuxCgroupPath`, and `LinuxPressureDecode` routes a
  malformed, truncated, non-numeric, or wrong-order field to `Absent` for
  that field alone, never a `NumberFormatException` or a non-exhaustive
  match. The v1 memory-limit "unlimited" sentinel (`>= 1L << 62`) routes to
  `Absent` rather than being recorded as a ~9.2e18-byte limit
  (`LinuxCgroup.applyV1LimitSentinel`, `LinuxCgroup.scala:74-75`); cpu PSI's
  `full` line is parsed but never emitted, since it is present-but-pinned-zero
  and a constant zero is not a signal (`Machine.scala:84`,
  `LinuxPressureDecode`); `nr_bursts`/`burst_usec`/`burst_time` cgroup fields
  are read but ignored, never decoded as throttling
  (`LinuxCgroupDecode.statField` only reads the named `key`).
- **Per-OS binding-load failure degrades that whole OS's syscall-backed
  families uniformly**, never a partial throw: `MachineMacos.bindings` and
  `MachineWindows.bindings` catch a load failure (e.g. browser-JS with no
  koffi) to `Absent`, and every read method pattern-matches on that
  `Maybe` to return `Machine.Reading.empty`
  (`MachineMacos.scala:17-30,37-40`; `MachineWindows.scala:17-38,49-51`).
  Windows additionally catches a LAZY first-symbol-lookup failure
  (`LinkageError`, e.g. `ExceptionInInitializerError` wrapping a missing
  Win32 export) at the same degrade boundary
  (`MachineWindows.scala:20-37`), since a library that resolves at
  `Ffi.load` time can still fail its first real call.
- **A per-store disk failure skips only that store**, not the whole disk
  set (`LinuxDisk.stat`, `MacosDisk.stat`, `WindowsDisk.stat` each return an
  Absent/Absent reading for the one failing mount and continue the
  enumeration); an all-failing or all-filtered mount set yields an empty
  Chunk, never a throw.
- **An OS with no dedicated `Machine` impl** (`Machine.forOs`'s wildcard
  case) degrades to `NullMachine`, which returns `Reading.empty`
  unconditionally (`Machine.scala:36,106-108`), never a throw at sampler
  init.

## Unit scaling

The stored unit for every cumulative time quantity is NANOSECONDS, with
each source's scale applied on read BEFORE the delta:

- `/proc/stat` jiffies -> ns: `1e9 / sysconf(_SC_CLK_TCK)`
  (`MachineLinux.jiffiesToNanos`/`jiffiesFromBinding`,
  `MachineLinux.scala:51-62,68-72`), falling back to the 100 Hz Linux default
  (`defaultJiffiesToNanos = 10000000L`) when `sysconf` is unavailable or
  returns non-positive.
- cgroup v2 `throttled_usec`/`cpu.max` fields and PSI `total=` are
  MICROSECONDS: scale x1000 (`LinuxCgroupDecode.v2Quota`/`v2Period`,
  `LinuxCgroupDecode.scala:19-28`; `LinuxPressureDecode.parse`,
  `LinuxPressureDecode.scala:22`).
- cgroup v1 `throttled_time` is ALREADY nanoseconds: x1
  (`LinuxCgroup.readV1`, `LinuxCgroup.scala:63`).
- cgroup v1 `cfs_quota_us`/`cfs_period_us` are MICROSECONDS: scale x1000
  (`LinuxCgroup.readV1Quota`, `LinuxCgroup.scala:92-93`; `readV1`,
  `LinuxCgroup.scala:60`).
- Windows `FILETIME` is 100ns units: scale x100
  (`MachineWindows.readCpu`, `MachineWindows.scala:61-64`).

Mixing up a v1-ns and a v2-us source without applying its own scale is a
1000x error; any new cumulative-time source added to this module states its
native unit and its scale-to-ns factor explicitly, the same way the sources
above do.

## Test conventions

- **Decoders are tested via PRODUCTION code paths, driven by injectable
  StubBindings with concrete values**, not by re-implementing the decode
  logic in the test. `MachineWindowsTest.StubBindings` is the canonical shape
  (`shared/src/test/scala/kyo/stats/machine/MachineWindowsTest.scala:19-37`):
  a per-OS binding subclass whose every method is a settable function field,
  defaulting to a failure code so an un-stubbed call surfaces as an obvious
  `Absent` rather than a silent success. Tests then call the REAL
  `MachineWindows.readCpu`/`readMemory`/etc. (or `MachineLinux`'s
  package-private `statvfsWith`/`jiffiesFromBinding` bridges,
  `LinuxBindingsTest.scala:46-50`) against the stub, so the assertion
  exercises the actual production catch/scale/decode logic.
- **1:1 test files.** Every source file has a matching `*Test.scala` (e.g.
  `LinuxCgroup.scala` -> `LinuxCgroupDecodeTest.scala`/tests folded into the
  matching aspect file where a source has multiple decode concerns).
- **Real-host FFI leaves are `.onlyJvm` plus `assume(OS)`.** A leaf that
  touches the actual host libc/shim/kernel32 (not a stub) is gated
  `.onlyJvm` (the platform-specific struct layout only needs proving once,
  on the JVM host) and additionally `assume`s the matching
  `System.live.unsafe.operatingSystem()`, so the assertion is skipped rather
  than failed on a non-matching CI host
  (`LinuxBindingsTest.scala:19-23`, `MacosBindingsTest.scala:19-23`,
  `WindowsBindingsTest.scala:18-21`). These leaves assert HOST-INVARIANT
  properties (a positive `sysconf` Hz, a coherent statvfs total/free
  relation), never a specific numeric value that would vary by runner.
- **The Windows behavioral test is HELD on Linux CI.** `WindowsBindingsTest`'s
  "real host load" leaf is written and gated exactly like the Linux/macOS
  equivalents but this repository's CI is Linux-only, so it is validated on
  a Windows host outside CI, not exercised in the standard run
  (`WindowsBindingsTest.scala:12-17`; `MachineWindows.scala:9`, "The
  behavioral read is validated on a Windows host, not this Linux-only CI").
- **The module opts out of its own auto-start during its own tests.** Every
  platform's test config disables the sampler so the once-per-second tick
  does not race the suites' destructive counter-drain assertions against
  the shared process-global `machine.*` handles: JVM sets
  `-Dkyo.machine.disabled=true` (`build.sbt:1149`), Native sets
  `KYO_MACHINE_DISABLED=true` as an env var (`build.sbt:1154`), and JS sets
  the same env var through the Node test environment config
  (`build.sbt:1162-1166`). A test that needs an actually-running sampler
  starts and stops it explicitly and locally
  (`MachineStatFactoryTest`, sequential-suite `stopForTest`/`resetForTest`
  seams, `MachineStatFactoryTest.scala:10,41-47`).

## kyo-core and kyo-stats-registry touches

This module depends on two additive `kyo-core` touches and one
`kyo-stats-registry` call:

1. **The eager `ExporterFactory` scan hook**, `Stat.eagerExporterScan`
   (`kyo-core/shared/src/main/scala/kyo/Stat.scala:219-222`), the LAST `val`
   in `object Stat`. It forces `Stat.scannedExporter`
   (`Stat.scala:189-191`) at class-init, which runs
   `TraceExporter.getIsolated` regardless of whether the application ever
   traces, so a metrics-only app that never calls `traceSpan`/`traceListen`
   still constructs `MachineStatFactory` and starts the sampler on classpath
   presence alone. This hook MUST remain the last `val` declared: a
   discovered factory's constructor runs inside `Stat`'s own class
   initializer and must never observe a later, not-yet-initialized `Stat`
   field.
2. **The `Stat` metric-handle API** (`initScope`, `initCounter`,
   `initHistogram`, `initGauge`, `initCounterGauge`) that `MachineHandles`
   builds every retained handle on top of (`MachineHandles.scala:21-88,256-261`).
3. **`kyo.stats.internal.TraceExporter.getIsolated`**
   (`kyo-stats-registry/shared/src/main/scala/kyo/stats/internal/TraceExporter.scala:40-58`),
   the per-factory-ISOLATED service-loader discovery variant: a factory
   whose construction or `traceExporter()` call throws is skipped rather
   than failing the whole scan, so one bad third-party provider cannot brick
   discovery for every other module (including this one). `Stat.scannedExporter`
   is the sole production caller; the module's own test suite also calls it
   directly to prove `MachineStatFactory` is reachable through that exact
   mechanism (`MachineStatFactoryTest.scala:122-126`).

## Pre-submission checklist (kyo-stats-machine-specific)

- [ ] Any new metric family lives in `shared/src/main`, selected only by
      `Machine.forOs`; no new `jvm/`/`js/`/`native/` leaf outside the two
      sanctioned exceptions (JS registration, JVM allocation test).
- [ ] Every new cumulative-time quantity gets a paired `Counter` +
      per-second-delta `Histogram` (the dual rule); a point-in-time value is
      a `Histogram`; a config value that can rise or fall is a lazily-created
      `Gauge`, never a `Counter`/`CounterGauge`.
- [ ] Every new decoder field routes a malformed/missing value to `Absent`,
      never a throw; a new source's native unit and its scale-to-ns factor
      are stated explicitly.
- [ ] A new syscall goes through the OS's existing (or a new) kyo-ffi
      binding trait, `(using AllowUnsafe)`-gated, with a matching
      `// Unsafe:` comment at the bridging call site; every `Buffer.alloc`
      is closed on every exit path.
- [ ] A new blockable read (any syscall against a mount or remote resource)
      is bounded the way `readDisksBounded` bounds disk reads, never run
      unbounded on the tick loop's own fiber.
- [ ] A new decoder test drives the REAL production decode/bridge function
      against an injectable stub, not a reimplementation of the decode logic.
- [ ] A real-host FFI leaf is `.onlyJvm` and `assume`s the matching
      `System.OS`.
- [ ] No phase/campaign codes or change-relative wording in any comment.
