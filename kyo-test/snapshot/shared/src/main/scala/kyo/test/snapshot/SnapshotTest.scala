package kyo.test.snapshot

import kyo.<
import kyo.Abort
import kyo.Async
import kyo.Frame
import kyo.Maybe
import kyo.Render
import kyo.Scope
import kyo.test.AssertionFailed
import kyo.test.SuiteFingerprintMarker
import kyo.test.internal.TestBase
import kyo.test.snapshot.SnapshotNotFound
import kyo.test.snapshot.internal.SnapshotDiff
import kyo.test.snapshot.internal.SnapshotStore

/** Implementation base that adds snapshot assertions on top of [[kyo.test.internal.TestBase]] WITHOUT the
  * [[kyo.test.SuiteFingerprintMarker]] mixin.
  *
  * This base exists so the deliberately-failing fixture suites in the snapshot test sources (driven via `TestRunner.runToFuture`, never run
  * standalone) can extend it directly and stay out of sbt test discovery. The public [[SnapshotTest]] subclass adds the marker so real user
  * snapshot suites are discovered; the fixtures must not be, because they fail by design. JVM discovery (Zinc's `xsbt.api.Discovery`) does
  * not surface a `SnapshotTest[Any]` fixture as a standalone suite, but Scala Native's reflective discovery does (it matches the marker on
  * the actual class hierarchy via `@EnableReflectiveInstantiation`), which previously failed the Native task. Extending this marker-free
  * base keeps the fixtures instantiable by `runToFuture` while invisible to discovery on every platform.
  *
  * The DSL methods in this class use `protected` visibility because the framework contract is inheritance-based; this is a deliberate
  * exception to the `No protected` convention (CONTRIBUTING P5), documented here as permitted for abstract DSL base classes.
  *
  * @tparam S
  *   the additive extra effect row a suite's leaf bodies may use beyond the always-present baseline
  * @see
  *   [[SnapshotTest]] the public, discoverable subclass that adds the SuiteFingerprintMarker mixin
  * @see
  *   [[kyo.test.Test]] the plain-suite base class analogous to this one
  * @see
  *   [[kyo.test.snapshot.SnapshotNotFound]] thrown on the first run when no snapshot file exists yet
  * @see
  *   [[kyo.test.AssertionFailed]] thrown when a snapshot exists but does not match the rendered value
  * @see
  *   [[kyo.test.TestResult.Failed]] the leaf outcome produced when assertSnapshot fails
  */
abstract class SnapshotTestBase[S] extends TestBase[S]:

    /** Root directory under which snapshot files are stored.
      *
      * Override in a suite to change the storage location (e.g. to a temp dir in tests).
      */
    protected def snapshotDir: String = "test-snapshots"

    /** Returns true when snapshot update mode is active.
      *
      * The default implementation reads the `KYO_TEST_SNAPSHOT` environment variable and returns `true` when its value is `"update"`.
      * Override this method in a test suite subclass to force update mode on or off without mutating the process environment.
      */
    protected def snapshotUpdateMode: Boolean =
        java.lang.System.getenv("KYO_TEST_SNAPSHOT") == "update"

    /** Assert that the rendered form of `actual` matches the stored snapshot identified by `name`.
      *
      * Algorithm:
      *   1. Render `actual` via `Render[A]`.
      *   2. Compute the storage path `${snapshotDir}/${this.name}/${name}.snap`.
      *   3. If `KYO_TEST_SNAPSHOT=update` (or override): write and pass.
      *   4. If no snapshot exists yet: write the proposed value and fail with `SnapshotNotFound`.
      *   5. If snapshot exists and matches (after trailing-whitespace normalization): pass.
      *   6. If snapshot exists and mismatches: fail with a unified diff.
      *
      * @param actual
      *   the value to snapshot
      * @param name
      *   the snapshot identifier; must not contain path separators
      * @tparam A
      *   the type of the value; a `Render[A]` instance must be in scope
      */
    protected inline def assertSnapshot[A](
        inline actual: A,
        inline name: String
    )(using inline r: Render[A], inline frame: Frame, inline as: kyo.test.AssertScope): Unit < (S & Async & Abort[Throwable] & Scope) =
        as.recordEvaluated()
        val rendered = r.asString(actual)
        if name.contains('/') || name.contains('\\') then
            throw new IllegalArgumentException(
                s"Snapshot name must not contain path separators: $name"
            )
        end if
        if name.isEmpty then
            throw new IllegalArgumentException("snapshot name must not be empty")
        end if
        if name == "." then
            throw new IllegalArgumentException("snapshot name must not be '.'")
        end if
        if name == ".." then
            throw new IllegalArgumentException("snapshot name must not be '..'")
        end if
        if name.contains(' ') then
            throw new IllegalArgumentException("snapshot name must not contain a space")
        end if
        val path       = s"${snapshotDir}/${this.name}/${name}.snap"
        val updateMode = snapshotUpdateMode
        if updateMode then
            SnapshotStore.write(path, rendered)
        else
            SnapshotStore.read(path) match
                case Maybe.Present(stored) =>
                    if stored.stripTrailing() != rendered.stripTrailing() then
                        val diagram = SnapshotDiff.render(stored.stripTrailing(), rendered.stripTrailing())
                        val failure = new AssertionFailed(diagram, frame, Maybe.Present("Snapshot mismatch"), Maybe.Absent)
                        as.record(failure)
                        throw failure
                case Maybe.Absent =>
                    SnapshotStore.write(path, rendered)
                    throw SnapshotNotFound(path)(using frame)
        end if
    end assertSnapshot

end SnapshotTestBase

/** Base class that adds snapshot assertions to a kyo-test V3 (next) suite.
  *
  * Extend this class (instead of `Test[S]` directly) to gain access to `assertSnapshot`. Snapshot files are stored under `snapshotDir`
  * (default `"test-snapshots"`) in subdirectories named after the suite class.
  *
  * Carries the [[kyo.test.SuiteFingerprintMarker]] mixin (via [[SnapshotTestBase]] plus the marker here) so sbt's `SubclassFingerprint`
  * discovery picks up user snapshot suites, exactly as [[kyo.test.Test]] does for plain suites. The assertSnapshot machinery lives on
  * [[SnapshotTestBase]]; this subclass adds only the discovery marker.
  *
  * The DSL methods in this class use `protected` visibility because the framework contract is inheritance-based; this is a deliberate
  * exception to the `No protected` convention (CONTRIBUTING P5), documented here as permitted for abstract DSL base classes.
  *
  * @tparam S
  *   the additive extra effect row a suite's leaf bodies may use beyond the always-present baseline
  * @see
  *   [[SnapshotTestBase]] the marker-free implementation base (used by non-discoverable internal fixtures)
  * @see
  *   [[kyo.test.Test]] the base class that SnapshotTest extends (transitively)
  * @see
  *   [[kyo.test.snapshot.SnapshotNotFound]] thrown on the first run when no snapshot file exists yet
  * @see
  *   [[kyo.test.AssertionFailed]] thrown when a snapshot exists but does not match the rendered value
  * @see
  *   [[kyo.test.TestResult.Failed]] the leaf outcome produced when assertSnapshot fails
  */
abstract class SnapshotTest[S] extends SnapshotTestBase[S] with SuiteFingerprintMarker
