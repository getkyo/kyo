# kyo-aeron: eliminate `JvmAeronTransport` and run the JVM on `FfiAeronTransport`?

Design analysis of consolidating the two transport implementations behind `AeronTransport` into one
(`FfiAeronTransport` + the `kyo_aeron.c` shim, on all four platforms), evaluated on the
maintainability lens the user set. Performance is noted where honest but does not drive the
recommendation. Every claim is grounded in code read in the worktree; the few things that need a
build run to confirm are called out explicitly.

## (a) Verdict

**Consolidate.** The FFI path is functionally complete for the JVM today: the binding trait, the
generated Panama impl, the compiled shim, and the shared `FfiAeronTransport` all already exist inside
the kyo-aeron JVM artifact as build products; the only missing piece is the selector flip (the JVM
`AeronPlatformTransport` is the sole file that chooses io.aeron), and the Native and JS selectors are
code-identical to what the JVM one would become, so the whole module collapses to shared-only source.
Method-by-method parity against `AeronTransport` is complete with no capability gap, including the
embedded driver, `fatalError`, `injectError`, `maxMessageLength`, and driver error-code fidelity
(already pinned by cross-platform tests). The genuine cost is not code but distribution: the published
JVM JAR must then carry a working `libkyo_aeron` per supported os-arch, and the release pipeline has
no multi-arch bundling today; decisive context is that this same pipeline gap already exists and
already breaks the published JS/Wasm artifacts (and release.yml cannot even build kyo-aeron at all
right now, since it never stages libaeron), so the bundling work is owed regardless of this decision
and consolidation only adds the JVM to an already-required fix. The maintainability upside is concrete
and visible in the history: the JVM-side duplicate of the C shim's teardown model
(`OpsGate`/`closeAll`) is exactly where the open use-after-free lives, and consolidation deletes that
entire second concurrency model rather than patching it. Recommendation: consolidate, with the
multi-arch publish pipeline sequenced inside the same campaign as the one substantial work item; the
in-repo flip and CI validation can land first because CI already stages libaeron on every JVM leg.

## (b) Feasibility and parity

### The JVM already builds (but does not run) the entire FFI stack

- `FfiAeronTransport` and `AeronBindings` are **shared** sources
  (`kyo-aeron/shared/src/main/scala/kyo/internal/FfiAeronTransport.scala`, `.../AeronBindings.scala`)
  and compile on the JVM today.
- The kyo-ffi codegen runs on the JVM leg and the generated Panama impl is compiled into the JVM
  artifact: `kyo-aeron/jvm/target/scala-3.8.4/src_managed/main/kyo-ffi/kyo/internal/AeronBindingsImpl.scala`
  exists and its compiled class is in the JVM classes dir. The impl does Panama downcalls via cached
  `MethodHandle`s, loads the library through `NativeLoader.load("kyo_aeron")` (JvmEmitter.scala:1060),
  and wraps the two `@Ffi.blocking` calls in `BlockingBridge.run`
  (kyo-ffi/jvm-native/.../BlockingBridge.scala:24).
- The C shim is compiled and **bundled into the JVM jar** on every build where libaeron is staged:
  `ffiLibraries` for `kyo_aeron` sits in the cross-platform `.settings` block (build.sbt:1743-1787),
  the plugin's `ffiCompile` produces `libkyo_aeron-<os>-<arch>.{dylib,so,dll}` on JVM
  (KyoFfiPlugin.scala:436-527), and `Compile / resourceGenerators` copies it to
  `META-INF/native/<os>-<arch>/libkyo_aeron.<ext>` (KyoFfiPlugin.scala:546-553, Packager.scala:50-60),
  exactly the path the JVM `NativeLoader` resolves at runtime (kyo-ffi/jvm/.../NativeLoader.scala:129).
  CI stages libaeron on the JVM leg explicitly (.github/actions/setup/action.yml:220-243).
- `Ffi.load[AeronBindings]` (kyo-ffi/shared/.../Ffi.scala:344) resolves `AeronBindingsImpl` by
  reflection on JVM (FfiReflect.scala:19). Platform-neutral.

So consolidation is a selector flip, not a port. What has never happened is *running* it on JVM: no
test loads `Ffi.load[AeronBindings]` on the JVM (the raw-bindings leaf is `.notJvm`,
AeronTransportTest.scala:793). First validation step of the campaign is exactly that run.

### Method-by-method parity against `AeronTransport`

| Op | JvmAeronTransport | FfiAeronTransport + shim | Gap |
|---|---|---|---|
| `asyncAddPublication`/`asyncAddSubscription` | :116/:206, closed-client `AeronException` -> `Absent` | FfiAeronTransport.scala:32/:71; shim returns NULL on closed/closing bundle | none |
| `pollAddPublication`/`pollAddSubscription` | :122/:211, `RegistrationException.errorCodeValue()`; closed -> `Failed(0,"")` | :35/:74, `math.abs(aeron_errcode())`; `Failed(0,"")` defensive arm | none; positive-code parity pinned by `TopicUniformInvariantsTest` |
| `freeAsyncPub`/`freeAsyncSub` | no-op (Long token) | shim `_free` releases token + refcount | none |
| `publicationIsConnected`/`subscriptionIsConnected` | :146/:229 (OpsGate + isClosed) | :56/:92; shim `closing`/`closed` guards | none |
| `offer` | :156 tryClaim/offer + IAE catch -> -6 | :59 `Buffer.useArray` + `kyo_aeron_publication_offer`; C returns -6 natively | none |
| `maxMessageLength` | :188 (0 after close) | :65; shim returns 0 under `closed` | none |
| `pollOne` | :235 FragmentAssembler | :95 grow-on-demand buffer + shim reassembly slot; poll error -1 -> `Absent` | none |
| `closePublication`/`closeSubscription` | :201/:255 gate close + drain | :68/:115; shim idempotent close + deferred free | none |
| `fatalError` | :270 AtomicRef slot | :119 C error slot via non-exiting handler | none |
| `injectError` | :275 | :129 `kyo_aeron_test_inject_error` | none |

Embedded driver: `kyo_aeron_driver_start` (kyo_aeron.c:378-392) mirrors `MediaDriver.launchEmbedded`:
per-instance dir, `dir_delete_on_start(true)`, DEDICATED threading. External connect: `clientConnect`
NULL -> `FfiNullPointer` -> `Abort.fail(TopicTransportFailedException)`. `AeronClient` and `Topic`
reference only `AeronPlatform`; nothing above the selector is transport-specific. The Native and
js-wasm selectors are code-identical (only scaladoc differs). After the flip, one shared
`AeronPlatformTransport` serves all four platforms and kyo-aeron's `jvm/`, `native/`, `js-wasm/` main
source dirs disappear.

Behavioral deltas to accept knowingly (none a correctness gap):

1. The embedded media driver on JVM becomes the C driver (`aeronmd`) instead of the Java
   `MediaDriver`. Same protocol, same vendor, same pinned 1.50.2.
2. Teardown converges on the shim's model (`close_mutex`/`closing`/refcounts/deferred frees) instead
   of the JVM `closeAll` drain. The shim model is the one WITHOUT the open bug.
3. Windows JVM runs the MSVC-built DLL. The shim has a Win32 mutex layer and is already
   runtime-exercised on windows-x64 by the JS and Wasm CI legs, so the JVM would be a new *loader*
   (Panama vs koffi) of an already-tested artifact.

## (c) Packaging and distribution reality

- **Today (io.aeron)**: JVM runtime needs `aeron-driver` + `aeron-client` JARs. NOT zero-config: any
  build hosting the embedded driver must set `fork := true` plus four `--add-opens` flags or
  `Topic.run` fails at driver launch (kyo-aeron/README.md:57-67, build.sbt:1792-1797).
- **After**: JVM runtime needs `libkyo_aeron` loadable for the host os-arch. Resolution order
  (NativeLoader.scala:118-172): `-Dkyo.ffi.kyo_aeron.path` override, then classpath resource
  `META-INF/native/<os>-<arch>/libkyo_aeron.<ext>`, then system lookup. The shim statically folds
  libaeron (`aeron_driver_static`, `staticLink = true`), so ONE self-contained shared object per
  os-arch, modulo system libs.

Build/publish gap: the plugin packages ONLY the host os-arch (Packager.scala:50-60). release.yml runs
entirely on `ubuntu-latest` and has no "Prepare libaeron" step, while kyo-aeron.jvm/.js are in the
publish aggregates. Structurally, `sbt -Dplatform=JVM ci-release` invokes kyo-aeron's `ffiCompile`
with an unstaged libaeron and `CCompiler.compile` hard-errors on non-zero cc exit. **The publish path
for kyo-aeron is broken today independent of this decision.** The same one-host limitation already
invalidates the published JS/Wasm artifacts. So "publish working shims per platform" is work the
module already owes its shipped backends; consolidation extends the fix to the JVM jar rather than
creating a new class of work.

The runtime layout is arch-keyed, so the JVM jar can carry
`META-INF/native/{linux-x86_64,linux-aarch64,darwin-aarch64,windows-x86_64,...}/` side by side and
`NativeLoader` picks the right one. What must be built: per-arch CI jobs running
`kyo-aeron/scripts/build-aeron.sh <os-arch>` (already parameterized) plus `ffiCompile`, artifact
upload, and a merge step before `publishSigned` (the sqlite-jdbc/zstd-jni model). Open decisions:
supported os-arch set (darwin-x86_64? linux-musl?) and whether the JS npm package ships in the same
campaign.

System-library footprint (Linux): the shim keeps dynamic `-lpthread -lm -ldl -luuid` plus `-latomic`
on aarch64. A consolidated JVM runtime on Linux needs libuuid (and libatomic on arm64); macOS gets
everything from libSystem; Windows needs the MSVC dynamic CRT (/MD). Worth a README/container note.

## (d) Runtime requirements and test impact

| | today (io.aeron) | after (FFI/Panama) |
|---|---|---|
| JDK floor | JDK 25 already | same |
| Mandatory flags | `fork := true` + 4 `--add-opens` or embedded `Topic.run` FAILS | none mandatory today |
| Advisory flags | — | `--enable-native-access=ALL-UNNAMED` (one flag, currently advisory) |
| System props | none | none required (`kyo.ffi.scratch.size` defaults 64 KiB; path override optional) |
| Native runtime deps | none | libuuid (+libatomic arm64) on Linux; MSVC CRT on Windows |

Net: launch-flag story *improves* (four mandatory add-opens replaced by one currently-advisory flag).
The "zero-config JVM" framing does not hold for the current code. Convention gap to fix while
flipping: kyo-aeron compiles at `-release 17` yet its generated impl imports `java.lang.foreign`;
once the JVM runtime path uses the impl, kyo-aeron should carry `foreignRelease` like every other
foreign-API module.

Test impact:
- **"UAF-reads" (`.notJvm`, :793)**: drop the gate and the leaf runs on JVM, directly exercising the
  shim's freed-handle guards through Panama. The missing "does FFI work on JVM" validation, for free.
- **"oversize offer ... does not throw" (`.onlyJvm`, :880)**: tests the deleted `JvmAeronTransport`
  IAE-suppression mechanic; leaf goes with it; cross-platform sibling (:920) keeps the -6 contract.
- **JvmAeronTransportTest** (jvm/src/test, 91 lines): deleted with the class; its concern is covered
  on the C path by the shim's `-1 -> Absent` mapping.
- **NeverConfirmTransport** (AeronTransportTest.scala:194): platform-neutral fake; unaffected.
- **TopicUniformInvariantsTest**: role shifts from JVM-vs-FFI parity to single-impl regression;
  comment updates only.
- **UAF-loop/UAF-saturated (:739, :765)**: after the flip they exercise the C teardown on JVM (green
  on Native/JS today) instead of crashing via the OpsGate granularity hole.
- CI: no new steps (libaeron already staged on every JVM leg). kyo-aeron JVM gains
  `--enable-native-access` and drops the four `--add-opens`; `kyo-compiler` mirrors the cleanup.

## (e) The maintainability case, concretely

1. **Two teardown/concurrency models for one contract.** The C shim's `close_mutex` + `closing` +
   registry + refcounted bundles + deferred frees (~1136 lines, four documented races) vs the JVM's
   independently re-solved `OpsGate` + `livePubs`/`liveSubs` + `closeAll`, and that re-solution has
   the open use-after-free. The in-place fix is to re-derive the C shim's client-level barrier in
   Scala, i.e. keep maintaining a hand-written mirror of logic that already exists and runs on three
   platforms.
2. **Every transport change is a double implementation by rule** (CONTRIBUTING.md:28). History shows
   the tax: the non-exiting error-handler built twice, the close-vs-offer guard built a second time
   (incompletely, hence this bug), an errcode-sign asymmetry needing per-impl normalization.
3. **A parity test surface exists only because there are two impls.**
4. **Two upstream pins in lockstep** (io.aeron 1.50.2 JARs and libaeron 1.50.2) become one.
5. **Per-platform source dirs vanish** (JvmAeronTransport 283 lines, jvm selector 84,
   JvmAeronTransportTest 91, native/js-wasm duplicates) replaced by one shared selector (~70).

What consolidation does NOT remove: the `AeronTransport` seam stays (it is `Topic`'s `Env` type and
the fake point); kyo-ffi's JVM emitter becomes load-bearing for aeron (kyo-net already runs FFI on
JVM, so not a first). Perf, no numbers asserted: FFI `offer` copies each message off-heap + a downcall
vs io.aeron's zero-copy `tryClaim`; accepted per the user's framing, with a clear later-optimization
path (`aeron_buffer_claim_t` claim/commit). CONTRIBUTING.md:21's "Routing JVM through FFI would
regress the primary latency platform, so it does not" is the documented decision this overturns.

## (f) Work items

1. **Selector flip / single source tree.** Move the FFI `AeronPlatformTransport` to shared; delete
   jvm/native/js-wasm selector files and `JvmAeronTransport.scala`; merge scaladoc.
2. **build.sbt (JVM leg):** drop io.aeron deps; drop the four `--add-opens`; add
   `--enable-native-access=ALL-UNNAMED`; add `foreignRelease`. Mirror add-opens cleanup in
   `kyo-compiler`.
3. **Tests:** remove `.notJvm` from AeronTransportTest:793; delete the `.onlyJvm` oversize-IAE leaf
   and JvmAeronTransportTest.scala; update stale comments naming OpsGate/JvmAeronTransport/io.aeron.
4. **Docs:** rewrite kyo-aeron/CONTRIBUTING.md (delete "Two-transport architecture" + implement-in-
   both rule); README.md:55-67 (replace add-opens with native-access + bundled-lib/system-deps notes).
5. **Release pipeline (the substantial item, owed regardless):** (a) add libaeron staging to
   release.yml; (b) multi-arch shim artifacts via per-os-arch jobs, merged into the published JVM
   jar's `META-INF/native/` tree before `publishSigned`; same set feeds JS/Wasm; (c) document the
   `-Dkyo.ffi.kyo_aeron.path` escape hatch.
6. **Validation:** full kyo-aeron matrix on all four platforms and three CI OS poles: the
   formerly-`.notJvm` raw-bindings leaf on JVM, UAF-loop/UAF-saturated on JVM under full-suite
   parallelism, windows-x64 JVM loading the MSVC DLL via Panama.
7. **Follow-up (outside correctness scope):** zero-copy offer in the shim to recover the tryClaim
   profile on all platforms at once.

## (g) Blockers and risks, ranked

No hard correctness or capability blocker. Ranked risks:

1. **Published-artifact packaging (highest; pre-existing, made mandatory).** The kyo-ffi plugin
   bundles only the build host's os-arch and release.yml neither stages libaeron nor merges arches.
   Until fixed, a consolidated JVM artifact works only on the publish host's platform (or via the
   path override). Mitigating: the identical gap already ships broken JS/Wasm artifacts; the runtime
   layout already supports a merged jar with zero code change; the fix is CI work on the
   sqlite-jdbc/zstd-jni model.
2. **Unvalidated JVM-FFI runtime path.** No test today executes `Ffi.load[AeronBindings]` on the JVM.
   Bounded: the same shim+selector is green on Native/JS/Wasm across three OS poles, kyo-net already
   runs Panama-loaded shims on JVM in CI, and the flip un-gates the leaf that validates it. Windows
   JVM (Panama loading the MSVC /MD DLL) is the least-exercised combination, though the DLL itself is
   runtime-exercised on windows-x64 by the JS/Wasm CI legs today.
3. **In-process C driver on the JVM.** A driver defect is a process crash, not an exception.
   Counterweights: the Java path just demonstrated the same failure class (this UAF SIGSEGV); same
   pinned 1.50.2 stack trusted on Native/JS; the shim's non-exiting error handler keeps fatal
   conductor conditions in-band.
4. **New Linux system deps** of the bundled shim (libuuid, +libatomic aarch64): README note + musl
   decision. User-visible delta, not a blocker.
5. **`--enable-native-access` trajectory**: advisory on JDK 25, heading to mandatory; one flag
   replacing four currently-mandatory `--add-opens`, so net improvement. JDK floor unchanged.
6. **Minor**: GraalVM native-image ergonomics (not a kyo target); io.aeron Java-first extras
   (Archive/Cluster) that kyo-aeron does not use.

Needs-a-run confirmations (not establishable read-only): the release-path ffiCompile failure on an
unstaged runner; green kyo-aeronJVM tests on the FFI path; Windows JVM DLL load via Panama.
