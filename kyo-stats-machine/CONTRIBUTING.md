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
`shared/src/main/scala/kyo/stats/machine/Machine.scala:43-48`). This works
because every implementation composes only cross-platform kyo primitives:
files are read via `kyo.Path` (never a platform-specific file API), and
genuine syscalls go through a per-OS **kyo-ffi** binding
(`LinuxBindings`/`MacosBindings`/`WindowsBindings`), never bundled ad hoc per
platform. The `Machine` trait's own doc states this precisely: "Every
implementation compiles on every platform because it composes only
cross-platform kyo primitives" (`Machine.scala:15-16`).

The ONLY per-platform Scala leaves in the whole module are the two files the
auto-registration mechanism genuinely requires:

- `js-wasm/src/main/scala/kyo/stats/machine/MachineRegistration.scala` (JS/Wasm
  `@JSExportTopLevel` registration; JVM/Native use classpath
  `META-INF/services` discovery instead, so they need no such file).
- `jvm/src/test/scala/kyo/stats/machine/MachineSamplerJvmTest.scala` (a
  `java.lang.management`-based allocation probe; the JDK reflection API it
  uses does not exist on JS/Native).

Everything else, including all three `Machine` impls, every decoder, the
sampler, the handle set, and every per-OS FFI binding trait, is
`shared/src/main`. A change that adds a `jvm/`, `js-wasm/`, or `native/` leaf
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
   (`MachineStatFactory.started`, `shared/src/main/scala/kyo/stats/machine/MachineStatFactory.scala:39,82-94`).
   The factory contributes no `TraceExporter` (`traceExporter()` always
   returns `None`, `MachineStatFactory.scala:30`): the SPI seam is used purely
   as an on-classpath start trigger, not for tracing.
3. The started sampler is a detached fiber (`Fiber.initUnscoped`) running
   `MachineSampler.run` under its own `Scope`, which drives two fibers: the
   fast family ticks on a drift-corrected 1 Hz schedule
   (`Clock.repeatAtInterval(Schedule.anchored(1.second))(readFast(machine))`,
   `MachineSampler.scala:155`), and disk reads run on their own
   1-second-interval fiber the fast fiber never awaits inline
   (`Clock.repeatAtInterval(diskInterval)(readDisksBounded(sampler, machine))`,
   `MachineSampler.scala:153`).
4. Opt-out: `KYO_MACHINE_DISABLED=true` (env) or `kyo.machine.disabled=true`
   (system property), read once via an injectable `System.Unsafe` so tests can
   stage a reader without mutating real process env
   (`MachineStatFactory.scala:52-74`). Unset or unparseable enables (graceful
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
`MachineHandles` under the `Stat.initScope("machine")` root
(`shared/src/main/scala/kyo/stats/machine/MachineHandles.scala:254`), with each
family a `root.scope(...)` child.

**The rate rule (the cumulative rides the histogram sum).** A per-second flow
(cpu-time per mode, cgroup cpu-throttling, PSI stall time) is a single `.rate`
`Histogram` observed with the per-tick scaled delta; there is no separate
cumulative `.total` `Counter` beside it. The histogram's own non-draining
running sum (`Summary.sum`, an `AtomicLong` over raw double bits) carries the
cumulative total, re-sent every export under CUMULATIVE temporality, so the
lifetime total survives without a second series. The delta computation lives
once in the `RateCell`: the first tick advances by 0 (no absolute-value spike),
a negative raw delta clamps to 0 (a kernel counter reset), and an Absent read
leaves the prior-state baseline unchanged. `cgroup.cpu.periods` is the one
metric modeled as a plain `Counter`: it is the single cumulative signal with no
per-second `.rate` companion.

**Point-in-time levels are `Histogram`s, fixed totals and pre-averaged values
are `Gauge`s.** A genuinely varying, adequately-sampled level (available/free
memory and swap, cgroup memory usage, per-mount free space) is a `Histogram`
observed directly with no delta. A fixed total (`cpu.cores`, `memory.total`,
`swap.total`, per-mount `disk.<store>.total`) and a pre-averaged value (`load.*`,
PSI `avgNN` percentages) is a plain `Gauge`, never a `Histogram`: a value that
is already an average or a constant total is not a distribution to bucket, and a
value that can fall is not a monotonic counter.

**Config values are `Gauge`s, never `Counter`s.** `cgroup.memory.limit`,
`cgroup.cpu.quota`, and `cgroup.cpu.period` are point-in-time config values that
can rise OR fall at runtime (a cgroup limit lowered live); a `CounterGauge`'s
delta model would map a decrease to a wraparound, so each is a plain `Gauge`
(raw value, no delta) instead.

**Every cell registers lazily, on its first `Present` observation only.** Not
just config gauges: every metric cell in the module (`RateCell`, `LevelCell`,
`LongGaugeCell`, `DoubleGaugeCell`, `CounterCell`) constructs its `kyo.Stat`
handle on its first present value, seeding a gauge's holder before it registers
so the first poll is never a transient 0. A metric the host never produces
never observes a present value, so it never registers a handle and is absent
from the export (never a fabricated zero). This is also why every cell must be
a retained `val` field for the sampler lifetime: the registry keys entries by
`WeakReference`, so a handle constructed locally per tick, or a cell dropped
from `val` to `def`, would compile cleanly while silently resetting the metric.

cgroup v1/v2 detection and PSI are Linux-only. The cgroup filesystem root is
resolved once from `/proc/self/mountinfo` (`LinuxCgroup.resolveV2Mount`,
`shared/src/main/scala/kyo/stats/machine/LinuxCgroup.scala:25,89-90`), falling
back to the conventional `/sys/fs/cgroup` only when mountinfo lists no cgroup2
mount; v2 unified is then chosen iff `<root>/cgroup.controllers` exists
(`LinuxCgroup.v2`, `LinuxCgroup.scala:26`), else v1 legacy. The resolved process
cgroup path is read from `/proc/self/cgroup`, not the hierarchy root
(`LinuxCgroup.resolveV2Dir`/`resolveV1Dirs`, `LinuxCgroup.scala:92-108`), because
v2 resource-control files exist only on non-root cgroups. System PSI (`/proc/pressure/*`) and cgroup v2 PSI (the
resolved dir's `*.pressure`) are recorded into two SEPARATE families through
distinct retained decode callbacks, one set writing `h.systemPressure` and
the other `h.cgroupPressure`, both invoked from the one `LinuxPressure.read`
(`shared/src/main/scala/kyo/stats/machine/LinuxPressure.scala:26-40`), so a
consumer can distinguish system-wide pressure from this cgroup's pressure and
neither `.rate` Histogram's running sum double-advances from one read.

## The sampler contract

`MachineSampler` (`shared/src/main/scala/kyo/stats/machine/MachineSampler.scala`)
is the single detached owner fiber for the whole tick loop. Its contract:

- **Zero-allocation steady-state reads.** The sampler owns one
  `Path.ReadHandle` per proc file, opened once at construction and retained
  in a `FileSlot` (`MachineSampler.scala:57-62,197-202`); each tick's
  `readInto` rewinds the handle (`fs.handle.position(0L)`) and refills the
  SAME reused buffer before handing the borrowed bytes to the retained decode
  callback (`MachineSampler.scala:68-77`). On JVM/Native this rides the
  kyo-core `NioReadHandle`'s retained `ByteBuffer` (see "kyo-core touches"
  below), so a steady-state read allocates no per-read payload. `fill`
  (`MachineSampler.scala:210-230`) reuses a fixed scratch array across
  `readChunk` calls and only grows the output buffer once, the first time a
  file exceeds it; the borrowed `Span[Byte]` handed to the caller's callback
  must never escape that callback.
- **`Scope`-teardown lifecycle.** `MachineSampler.run`/`runWith` build the
  sampler and its handles UNDER one `Scope`, register the buffer-closing
  finalizer FIRST (`Scope.ensure { machine.close(); sampler.closeHandles() }`,
  `MachineSampler.scala:149-152`), then register the disk and fast fibers for
  interrupt (`MachineSampler.scala:153-157`). `Scope` finalizers run LIFO, so
  on teardown both fibers are interrupted FIRST and the handle-closing
  finalizer runs LAST, so no tick or disk read ever touches a closed handle.
  Awaiting the fast fiber's `get` (it never returns) keeps the `Scope` open
  for the process lifetime.
- **The blockable disk read runs on its own fiber, off the tick loop.** Every
  other read is a fast in-kernel/proc read; disk enumeration is the one
  genuinely-blockable syscall family (`statvfs`/`statfs`/`GetDiskFreeSpaceExA`
  against a possibly hung or dead mount), and a blocking disk syscall against a
  dead mount has no suspension point a timeout can interrupt. So the tick fiber
  never awaits the disk read inline. The fast in-kernel/proc reads run on the
  tick fiber at the fixed 1 Hz cadence; the disk read runs on a separate
  detached fiber that enumerates mounts, bounds each per-mount read with
  `Async.timeout`, and never launches a new read for a mount whose prior read is
  still in flight (single-in-flight-per-mount, so a permanently dead mount leaks
  at most one blocked worker for the process lifetime, not one per tick). A hung
  mount stalls only disk data; it never delays the fast reads of the same or any
  later tick. Both readers write decoded primitives straight into retained
  cells and return `Unit`: `Machine.read` writes the fast families,
  `Machine.readDisks` writes each mount's total/free, neither returns a reading
  value. Both fibers are registered with the sampler `Scope` for interrupt
  BEFORE the finalizer closes buffers, so no tick or disk read ever touches a
  closed handle.

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
  (`machine_macos.c:128-140`), so the file compiles and every symbol resolves
  on a non-macOS build host (the Linux-only CI compiles this on the BUILD
  HOST for JVM/JS; Scala Native compiles it into the binary on every OS). The
  module's `build.sbt` registers it via `nativeBundled` plus an explicit
  `FfiLibrary` entry naming the C source
  (`build.sbt:1123-1125`), since `library = "machine_macos"` is not a system
  library id.
- **`WindowsBindings`** (`shared/src/main/scala/kyo/stats/machine/WindowsBindings.scala`):
  `Ffi.Config(library = "kernel32", headers = Chunk("windows.h"), symbols = Map(...))`.
  Bound directly against `kernel32`, no bundled C. The `symbols` map pins
  each Scala method to its EXACT Win32 export name (the derived snake-case
  name would not resolve). The disk entry points bind the ANSI (`A`) forms,
  `GetDriveTypeA` and `GetDiskFreeSpaceExA` (`GetDiskFreeSpaceEx` is a macro,
  not an export, so an `A`/`W` form must be named explicitly), because kyo-ffi
  marshals a Scala `String` uniformly as narrow/UTF-8 on every target and this
  binding only ever passes pure-ASCII drive-root strings (`"C:\\"`): ANSI is
  then byte-identical to what kyo-ffi produces, while a `W` (wide/UTF-16) entry
  point fed a narrow buffer is an ABI mismatch that returns empty disk data.
  `headers = Chunk("windows.h")` gates Native `@extern` emission, so a
  non-Windows Native build emits runtime stubs instead of a `@link("kernel32")`
  that would fail to link.

Every reader hand-projects each syscall's wanted fields into a flat
`Buffer[Long]`/`Buffer[Double]` scratch area, allocated with `Buffer.alloc` and
read back through `Buffer`'s NON-generic `getLong`/`getDouble`/`setLong`
accessors, never the generic `get`/`set` (which box every element through the
`UnsafeLayout[A]` typeclass dispatch) and never `StructLayout` (see
`MacosBindings.hostCpuLoad`'s doc, `MacosBindings.scala:14`; the
non-generic-accessor rationale on `MachineMacos`, `MachineMacos.scala:13-16`;
and every `Machine` impl's `readCpu`/`readMemory`/etc.). Every out-buffer is RETAINED:
allocated exactly once, at reader construction (`MachineMacos.scala:21-24`,
`MachineWindows.scala:20-23`), never per read, since `Buffer.alloc` opens a
fresh memory arena per call. Each reader's `close()` method closes every
retained buffer, invoked exactly once by the sampler's `Scope` finalizer at
teardown, never per-tick (`MachineMacos.scala:42-48`,
`MachineWindows.scala:49-54`). `WindowsBindings.fillMemoryStatus` presets the
shared `MEMORYSTATUSEX` buffer's `dwLength` field before the one-per-tick
`GlobalMemoryStatusEx` call, so both the memory and swap rows read the same
filled buffer from a single syscall (`WindowsBindings.scala:88-91`).

Every `AllowUnsafe.embrace.danger` import is scoped to the single field
initializer (or, in the one class-body case below, the single trailing init
statement) that needs it, never the whole class body, and carries a
`// Unsafe:` comment naming the specific bridge it opens: the disk fiber's
in-flight guard flag, a single-owner `AtomicBoolean` (`MachineSampler.scala:33`);
the module-init SPI activation boundary and the opt-out read
(`MachineStatFactory.scala:27,37`); `LinuxDisk`'s retained `/proc/mounts` read
handle, opened once at construction (`LinuxDisk.scala:28`); every metric
cell that owns its own unsafely-constructed field state, `RateCell`,
`CounterCell`, `LongGaugeCell`, and `DoubleGaugeCell`, each of which builds an
`AtomicLong.Unsafe.init` holder in its field initializer
(`MachineHandles.scala:100,146,178,199`); and the init-time core-count seed
(`MachineHandles.scala:84`), the one class-body import, placed last so it covers
only the trailing `cpuCores.set(coreCount)` statement. `LevelCell` carries no
import of its own, since it holds only a plain `var` and observes through the
capability its caller already supplies. `MachineSampler`'s companion object
carries no `embrace.danger` import of its own: its two unsafe-tier helpers,
`openSlot` and `fill`, each redeclare `(using AllowUnsafe)` on their own
signature, and every caller that reaches them (`run`, `runWith`, `readFast`,
`readDisksBounded`) does so from inside `Sync.Unsafe.defer`, which
manufactures the capability internally rather than the caller supplying one
ambiently (`kyo-core/shared/src/main/scala/kyo/Sync.scala:136-139`).

## Graceful degradation

There is no public `Machine.snapshot`/`Snapshot` read primitive: the sampler
observes availability straight into (or skips) retained `kyo.Stat` handles.
An unavailable metric is `Absent`, NEVER a fake zero and NEVER a throw
(`Machine.scala:5-11`). This holds at every layer:

- **Per-field decode totality.** Every field read in `LinuxDecoders`,
  `LinuxCgroup`, `LinuxCgroupPath`, and `LinuxPressure` goes through one of
  `LinuxScan`'s byte-span field primitives (`keyedLong`, `taggedLong`,
  `taggedDouble`, `longField`, `doubleField`): each scans the retained buffer
  directly for its key or column and returns the primitive sentinel
  (`Path.ReadHandle.AbsentLong`, or `Double.NaN` for a fixed-point value) when
  the key is missing, the line is truncated, or the value is non-numeric, with
  no `String`, no `split`, and no exhaustive match to maintain
  (`LinuxScan.scala:36-72`). The v1 memory-limit "unlimited" sentinel
  (`>= 1L << 62`) routes to `Absent` rather than being recorded as a
  ~9.2e18-byte limit, through the package-private pure routing function
  `LinuxCgroup.limit` (`private[machine] def limit(v: Long): Long`), which the
  cgroup decode test exercises directly rather than re-implementing the
  comparison; cpu PSI's `full` line is never scanned at all, since the cpu
  `PsiDecode` is constructed with no full-line cells (`Absent`), so no
  `cpu.full` series is ever registered (`LinuxPressure.scala:26,57-64`); a
  cgroup `cpu.stat` field this module has no cell for (a kernel that also
  reports `nr_bursts`/`burst_usec`/`burst_time`, for instance) is simply never
  scanned for, since `decodeCpuStat` only looks up the three keys it has cells
  for (`nr_periods`, `nr_throttled`, and the version-selected throttled field,
  `LinuxCgroup.scala:62-67,116-119`).
- **Per-OS binding-load failure degrades that whole OS's syscall-backed
  families uniformly**, never a partial throw: `MachineMacos.bindings` and
  `MachineWindows.bindings` catch a load failure (e.g. browser-JS with no
  koffi) to `Absent` (`MachineMacos.scala:53-55`; `MachineWindows.scala:57-59`),
  and every read method pattern-matches on that `Maybe` to write NOTHING at
  all rather than returning a reading value (`MachineMacos.scala:28-40`;
  `MachineWindows.scala:27-47`). Windows additionally catches a LAZY
  first-symbol-lookup failure (`LinkageError`, e.g. `ExceptionInInitializerError`
  wrapping a missing Win32 export) at the same degrade boundary
  (`MachineWindows.scala:34-38,44-46`), since a library that resolves at
  `Ffi.load` time can still fail its first real call.
- **A per-store disk failure skips only that store**, not the whole disk
  set: `LinuxDisk.statvfsInto`, `MacosDisk.statfsInto`, and
  `WindowsDisk.diskFreeInto` each write NOTHING for the one failing mount
  (a non-zero return code or a caught `NonFatal` exception both fall through
  to no cell write, `LinuxDisk.scala:212-222`) and the enumeration loop
  continues to the next store; an all-failing or all-filtered mount set
  yields no disk metrics at all, never a throw.
- **An OS with no dedicated `Machine` impl** (`Machine.forOs`'s wildcard
  case) degrades to `NullMachine`, whose `read`/`readDisks`/`close` each write
  nothing unconditionally (`Machine.scala:48,54-57`), never a throw at sampler
  init.

## Unit scaling

The stored unit for every cumulative time quantity is NANOSECONDS, with
each source's scale applied on read BEFORE the delta:

- `/proc/stat` jiffies -> ns: `1e9 / sysconf(_SC_CLK_TCK)`
  (`MachineLinux.jiffiesToNanos`/`jiffiesFromBinding`,
  `MachineLinux.scala:52-55,73-77`), falling back to the 100 Hz Linux default
  (`defaultJiffiesToNanos = 10000000L`) when `sysconf` is unavailable or
  returns non-positive.
- cgroup v2 `cpu.max` (quota and period, decoded together off one line) and
  the v2 branch of `cpu.stat`'s `throttled_usec` are MICROSECONDS: scale
  x1000 (`LinuxCgroup.decodeCpuMax`, `LinuxCgroup.scala:56-59`; the
  `throttledScale` selection, `LinuxCgroup.scala:52-53,62-67`). PSI `total=`
  is also MICROSECONDS: scale x1000 (`LinuxPressure.observeLine`,
  `LinuxPressure.scala:78`).
- cgroup v1 `cpu.stat`'s `throttled_time` is ALREADY nanoseconds: x1 (the v1
  branch of `throttledScale`, `LinuxCgroup.scala:52-53`).
- cgroup v1 `cfs_quota_us`/`cfs_period_us` are MICROSECONDS: scale x1000 (the
  v1 branch of `LinuxCgroup.read`, `LinuxCgroup.scala:73-75`).
- Windows `FILETIME` is 100ns units: scale x100
  (`MachineWindows.readCpu`, `MachineWindows.scala:61,64-66`).

Mixing up a v1-ns and a v2-us source without applying its own scale is a
1000x error; any new cumulative-time source added to this module states its
native unit and its scale-to-ns factor explicitly, the same way the sources
above do.

## Test conventions

- **Decoders are tested via PRODUCTION code paths, driven by injectable
  StubBindings with concrete values**, not by re-implementing the decode
  logic in the test. `MachineWindowsTest.StubBindings` is the canonical shape
  (`shared/src/test/scala/kyo/stats/machine/MachineWindowsTest.scala:20-38`):
  a per-OS binding subclass whose every method is a settable function field,
  defaulting to a failure code so an un-stubbed call surfaces as an obvious
  `Absent` rather than a silent success. Tests then call the REAL
  `MachineWindows.readCpu`/`readMemoryAndSwap`/etc. against the stub (or, for a
  binding-driven production helper, `MachineLinux.jiffiesFromBinding`,
  `LinuxBindingsTest.scala:10-42`, and `LinuxDisk.statvfsInto`,
  `LinuxDiskTest.scala`), so the assertion exercises the actual production
  catch/scale/decode logic.
- **1:1 test files.** Every source file has a matching `*Test.scala` (e.g.
  `LinuxCgroupPath.scala` -> `LinuxCgroupPathTest.scala`/tests folded into the
  matching aspect file where a source has multiple decode concerns).
- **Real-host FFI leaves are `.onlyJvm` plus `assume(OS)`.** A leaf that
  touches the actual host shim or kernel32 (not a stub) is gated `.onlyJvm`
  (the platform-specific struct layout only needs proving once, on the JVM
  host) and additionally `assume`s the matching
  `System.live.unsafe.operatingSystem()`, so the assertion is skipped rather
  than failed on a non-matching host (`MacosBindingsTest.scala:60-64,76-79,96-99`,
  `WindowsBindingsTest.scala:159-163`). These leaves assert HOST-INVARIANT
  properties (a positive cumulative cpu-time sum, at least one drive with a
  positive total), never a specific numeric value that would vary by runner.
  `WindowsBindingsTest` additionally gates the INVERSE case, a
  `.onlyJvm`/`assume`-gated leaf that only runs where Windows is ABSENT, to
  prove the off-Windows degrade path (`WindowsBindingsTest.scala:141-145`).
  Linux has no equivalent `Ffi.load[LinuxBindings]`-against-the-real-libc
  leaf: the standard CI host already IS Linux, so every stub-driven
  `MachineLinux`/`LinuxBindings` assertion already runs against the real
  kernel underneath the production `Machine.forOs` path on every CI run,
  without needing a separate `assume`-gated leaf. `LinuxDisk` takes its
  mounts source as a constructor parameter (`mountsPath: Path`, defaulting
  to `Path("/proc/mounts")`, production behavior unchanged) precisely so a
  test can drive the REAL `LinuxDisk.read` end to end against a staged file
  instead of only unit-testing `statvfsInto` in isolation:
  `MachineSamplerJvmTest`'s disk-probe leaf stages a temp mounts file,
  proves the steady read allocates exactly 0 bytes per op, then rewrites the
  file and asserts the mount-change fingerprint mismatch genuinely rebuilds
  the retained store set (`MachineSamplerJvmTest.scala`, the Linux
  steady-state disk read leaf). That closes the direct-production-path gap;
  the real-syscall gap (an actual `statvfs(2)` call against the real libc,
  the counterpart to the macOS/Windows `Ffi.load` leaves below) remains open.
- **The macOS real-host leaves run on their own CI job.**
  `MacosBindingsTest`'s real-host leaves, gated `.onlyJvm` plus an `assume` on
  `System.OS.MacOS` as above, run for real on the `machine-stats-macos` CI job
  (`.github/workflows/ci.yml`), which runs `sbt "kyo-stats-machineJVM/test"` on
  a real macOS host so those leaves execute in CI rather than being skipped
  everywhere but a manual run (`MacosBindingsTest.scala:60-61,76-77,96-97`).
  `WindowsBindingsTest`'s real-host leaves have no standing CI job; they run
  only on a manual `kyo-stats-machineJVM/test` on a Windows host
  (`WindowsBindingsTest.scala:159-160`).
- **The module opts out of its own auto-start during its own tests.** Every
  platform's test config disables the sampler so the once-per-second tick
  does not race the suites' destructive counter-drain assertions against
  the shared process-global `machine.*` handles: JVM sets
  `-Dkyo.machine.disabled=true` (`build.sbt:1134`), Native sets
  `KYO_MACHINE_DISABLED=true` as an env var (`build.sbt:1139`), and JS sets
  the same env var through the Node test environment config
  (`build.sbt:1150`). A test that needs an actually-running sampler starts
  and stops it explicitly and locally (`MachineStatFactoryTest`,
  sequential-suite `stopForTest`/`resetForTest` seams,
  `MachineStatFactoryTest.scala:10,36-47`).

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
   `initHistogram`, `initGauge`) that `MachineHandles` builds every retained
   handle on top of (`MachineHandles.scala:35-87,252`). No cell uses
   `initCounterGauge`: `cpu.cores` and every other fixed-total or
   pre-averaged value is a plain `Gauge`, not a `CounterGauge`.
3. **`kyo.stats.internal.TraceExporter.getIsolated`**
   (`kyo-stats-registry/shared/src/main/scala/kyo/stats/internal/TraceExporter.scala:40-58`),
   the per-factory-ISOLATED service-loader discovery variant: a factory
   whose construction or `traceExporter()` call throws is skipped rather
   than failing the whole scan, so one bad third-party provider cannot brick
   discovery for every other module (including this one). `Stat.scannedExporter`
   is the sole production caller; the module's own test suite also calls it
   directly to prove `MachineStatFactory` is reachable through that exact
   mechanism (`MachineStatFactoryTest.scala:94-109`).

## Pre-submission checklist (kyo-stats-machine-specific)

- [ ] Any new metric family lives in `shared/src/main`, selected only by
      `Machine.forOs`; no new `jvm/`/`js/`/`native/` leaf outside the two
      sanctioned exceptions (JS registration, JVM allocation test).
- [ ] Every new cumulative-time quantity is a single `.rate` `Histogram`
      whose own running sum carries the cumulative total (the rate rule), not
      a separate `.total` `Counter`; a genuinely varying level is a
      `Histogram`; a fixed total or pre-averaged value is a `Gauge`; a config
      value that can rise or fall is a lazily-created `Gauge`, never a
      `Counter`/`CounterGauge`.
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
