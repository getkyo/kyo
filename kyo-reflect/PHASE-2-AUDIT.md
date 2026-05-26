# Phase 2 Audit (commit d66d57b4f)

Scope: Phase 2 of `kyo-reflect/execution-plan-perf.md` ("Digest by jar metadata — eliminate the third JAR walk"). Read against committed HEAD only.

## Files changed (matches plan?)

`git show d66d57b4f --name-only`:

- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/DigestComputer.scala`
- `kyo-reflect/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala`

Plan `### Files to modify` lists exactly `DigestComputer.scala`; `### Tests` lists `SnapshotRoundTripTest.scala`. Both files are in scope. **No steering deviation.**

## Test count

Plan calls for 5 new tests (T14..T18 in plan; mapped here to T-J1..T-J5). All five are present in `SnapshotRoundTripTest.scala`.

- T-J1 jar-root determinism — `kyo-reflect/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala:597` — **PRESENT_STRICT**. Two `DigestComputer.compute` calls on the same temp jar; asserts `d1.sameElements(d2)`.
- T-J2 directory-root determinism — `SnapshotRoundTripTest.scala:633` — **PRESENT_STRICT**. Uses `fixtureSource()` and asserts `d1.sameElements(d2)`.
- T-J3 jar mtime change detected — `SnapshotRoundTripTest.scala:648` — **PRESENT_STRICT**. Uses `Files.setLastModifiedTime(jarPath, FileTime.from(Instant.now().plus(1, ChronoUnit.HOURS)))` (lines 670-673). `Thread.sleep` is **absent** (grep of file confirms zero occurrences in the added block). Asserts `!d1.sameElements(d2)`.
- T-J4 jar size change detected — `SnapshotRoundTripTest.scala:687` — **PRESENT_STRICT**. Rewrites the jar with an extra `B.tasty` entry (5 bytes vs original 3 bytes) AND bumps mtime by +1h to prevent resolution rounding from masking the rewrite (matches the plan note literally). Asserts `!d1.sameElements(d2)`.
- T-J5 mixed jar+directory root-order independence — `SnapshotRoundTripTest.scala:735` — **PRESENT_STRICT**. Builds a `combinedSrc` that routes jar paths to `PlatformFileSource.get` and directory paths to a `MemoryFileSource`. Computes digest for `Seq(jPath, "root")` and `Seq("root", jPath)`, asserts `d1.sameElements(d2)`.

**Test count: 5/5 PRESENT_STRICT.**

## Unsafe markers

`git show d66d57b4f:.../DigestComputer.scala | grep -nE 'asInstanceOf|Frame\.internal|AllowUnsafe|Sync\.Unsafe'` → **zero matches**. Phase 2 introduces no new unsafe markers in DigestComputer. Compliant.

(Note: the test file uses one `asInstanceOf` on the JS-runtime `js.Dynamic` value — but that's only in pre-existing test infrastructure plus the new T-J5 `combinedSrc` does NOT use `asInstanceOf`; verified by reading the diff. Tests added by Phase 2 contain no `asInstanceOf` / `AllowUnsafe` / `Frame.internal`.)

## CONTRIBUTING.md compliance

Checked against §Core Principles, §API Design, §Code Conventions, §Testing, §Unsafe Boundary.

- **Naming** (action verbs, `compute` / `computeParanoid` / `collectAllStats` / `collectAllFiles`): conforms. `collectAll*` is a clear collective and matches the existing `collectStats` / `collectFiles` naming inside the same file.
- **Effect signatures**: `compute` and `computeParanoid` retain the original `Array[Byte] < (Sync & Abort[ReflectError])` signature. No effect-row drift. No effect aliases introduced.
- **Branching style**: jar-root detection uses `if root.startsWith("jrt:/") then ... else if root.toLowerCase.endsWith(".jar") then ...`. This is exactly the convention used by `JvmFileSource.scala` lines 29-31 and 72-79 for read/list dispatch. **Conforms.**
- **Kyo computation chaining**: all branches return `Sync & Abort[ReflectError]` computations; no dangling computations; no semicolons; no `Var` (atomic primitives are not applicable here — pure folding). Compliant.
- **No backwards compatibility shim**: digest semantics change for jar roots is acknowledged in the commit message ("Pre-existing .krfl snapshots become unreachable...intentional and acceptable"). No dual surface. Compliant with `feedback_no_backcompat`.
- **Tests on public API**: `DigestComputer.compute` is package-internal (`kyo.internal.reflect.snapshot`) but is the unit-under-test — this matches the surrounding pre-existing T29/T30 tests, which also call `DigestComputer.compute` directly. **Consistent with existing test style** for this internal API.

## Cross-platform consistency

DigestComputer lives in `shared/`. The jar-branch calls `source.stat(root)` on the jar file path. Verified:

- **JVM** (`kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/query/JvmFileSource.scala`): `stat` at line 117 handles `jrt:/`, jar paths (line 31 ascii check), and regular files. For a literal jar file path, it `Files.getLastModifiedTime` on the jar file itself. Works.
- **Native** (`kyo-reflect/native/src/main/scala/kyo/internal/reflect/query/NativeFileSource.scala`): `stat` at line 69 delegates to `statFile(path)` (POSIX `stat()` FFI) and aborts with `ReflectError.FileNotFound` if the path doesn't exist. The jar file is just a regular file on the filesystem — POSIX `stat()` on it returns (mtime, size). Works.
- **JS-Node** (`kyo-reflect/js/src/main/scala/kyo/internal/reflect/query/JsFileSource.scala`): `stat` at line 106 calls `fs.statSync(path)` and returns `mtime` and `size`. Works.
- **JS-browser**: `stat` aborts with `browserError`. The existing browser behavior already returns `Abort.fail(ReflectError.FileNotFound("browser: use fromPickles"))` from `openCached` before any digest computation (per the file-header docstring). Not a regression.

PERF-VERIFICATION.md §2 confirms NativeFileSource "has no JAR support at all" — but Phase 2 doesn't try to enumerate jar entries on Native; it `stat`s the file. For a real jar file on disk, this works on Native. The only Native-impactful change is that a jar root which previously returned an empty list (and thus contributed nothing to the digest) now contributes `(jarPath, mtime, size)` to the digest. Semantically meaningful and correct.

All five new tests are `taggedAs jvmOnly` except T-J2 (which uses `MemoryFileSource` and runs on all platforms — fine). T-J1, T-J3, T-J4, T-J5 are JVM-only because they require `Files.createTempFile` and `ZipOutputStream`. **Reasonable platform tagging.**

## Anti-flakiness measures

- **T-J3 mtime test**: `Thread.sleep` absent (verified by grep). `Files.setLastModifiedTime(jarPath, FileTime.from(Instant.now().plus(1, ChronoUnit.HOURS)))` — +1h offset is far beyond any filesystem mtime resolution (HFS+ 1s, ext4 1ns, APFS 1ns, NTFS 100ns). **Robust.**
- **T-J4 size test**: rewrites jar with explicitly longer payload (3 bytes → 5 bytes plus an extra entry). Also bumps mtime +1h to prevent resolution-rounding from also masking the change. **Robust and matches plan literally.**
- **Temp file lifecycle**: every jar-creating test wraps the temp jar with `Scope.ensure(Sync.defer(Files.deleteIfExists(jarPath): Unit)).andThen(...)`. `Test.run` provides `Scope` (verified via `kyo-reflect/shared/src/test/scala/kyo/Test.scala` → `BaseKyoCoreTest` → `Scope.run` handler). Temp file deletion is hooked. **Robust.**

## Steering deviation

`git show d66d57b4f --name-only` matches Phase 2's `### Files to modify` (DigestComputer.scala) + `### Tests` (SnapshotRoundTripTest.scala). **No deviation.**

One implementation-strategy note (not a deviation, but worth flagging for clarity): the plan text says "make `DigestComputer` accept a pre-computed file list for jar roots", which suggests passing the Phase 1 entry list through. The commit instead chose the stronger optimization: it eliminates the jar entry list from the digest input altogether and hashes only `(jarPath, mtime, size)`. The end goal — eliminating the third JAR open — is achieved more aggressively than the plan text describes, and aligns with the plan's stated rationale ("`stat()`-based invalidation correct for jar roots"). The plan's `Files to modify` bullet for `compute` actually does say "call `source.stat(root)` ... include `(root, mtime, size)` as the hash input for that root", which matches the implementation exactly. **No deviation.**

## Pre-existing DigestComputer tests still call the new API

`SnapshotRoundTripTest.scala:329, 332, 346, 354` (the pre-Phase-2 T29/T30 deterministic-digest tests) still call `DigestComputer.compute(Seq("root"), src)` with directory roots backed by `MemoryFileSource`. With Phase 2's directory-root branch unchanged, these continue to exercise the per-file `.tasty` enumeration path. **No regression.**

## Categorized findings

### BLOCKER
None.

### WARN
None.

### NOTE

- N1: Phase 2 changes digest semantics for jar roots, invalidating existing `.krfl` snapshots. Commit message acknowledges this intentionally. No user-facing migration shim needed (per `feedback_no_backcompat`).
- N2: T-J5's `combinedSrc` declares an inline `FileSource` anonymous class with `mkdirs(path) = Kyo.unit` and a hand-written `exists` that probes `memSrc.files.keys.exists(_.startsWith(path + "/"))`. This is test-only scaffolding for a single test and acceptable; no production code reuse expected.
- N3: T-J4 bumps mtime in addition to changing size. Strictly the size-change alone would invalidate the digest because size is hashed. The extra mtime bump prevents a filesystem-resolution edge case where two successive writes within the same mtime tick could leave mtime unchanged on slow systems. This matches the plan's anti-flakiness guidance literally.

## Phase 4 SLOT-A launch gate

**No BLOCKER findings. Phase 4 SLOT-A launch is unblocked from a Phase 2 audit perspective.**
