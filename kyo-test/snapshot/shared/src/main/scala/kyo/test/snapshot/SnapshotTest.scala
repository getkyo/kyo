package kyo.test.snapshot

import kyo.<
import kyo.Abort
import kyo.Async
import kyo.Changeset
import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Render
import kyo.Result
import kyo.Schema
import kyo.Scope
import kyo.test.AssertionFailed
import kyo.test.SuiteFingerprintMarker
import kyo.test.internal.TestBase
import kyo.test.prop.Gen
import kyo.test.snapshot.SnapshotCodec
import kyo.test.snapshot.SnapshotNotFound
import kyo.test.snapshot.SnapshotSchemaEvolution
import kyo.test.snapshot.internal.SnapshotDiff
import kyo.test.snapshot.internal.SnapshotStore

/** Implementation base that adds snapshot assertions on top of [[kyo.test.internal.TestBase]] WITHOUT the
  * [[kyo.test.SuiteFingerprintMarker]] mixin.
  *
  * This base exists so the deliberately-failing fixture suites in the snapshot test sources (driven via `TestRunner.runToFuture`, never run
  * standalone) can extend it directly and stay out of sbt test discovery. The public [[SnapshotTest]] subclass adds the marker so real user
  * snapshot suites are discovered; the fixtures must not be, because they fail by design. JVM discovery (Zinc's `xsbt.api.Discovery`) does
  * not surface a `SnapshotTest[Any]` fixture as a standalone suite, but Scala Native's reflective discovery does (it matches the marker on
  * the actual class hierarchy via `@EnableReflectiveInstantiation`), so a marker-carrying fixture would otherwise be discovered as a
  * standalone suite on Native. Extending this marker-free base keeps the fixtures instantiable by `runToFuture` while invisible to
  * discovery on every platform.
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
        validateSnapshotName(name)
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

    /** Snapshot serialization format used by [[assertSchemaSnapshot]].
      *
      * Override in a suite to change the format (e.g. `SnapshotCodec.Protobuf` for a binary round trip). Defaults to
      * `SnapshotCodec.Yaml`, the readable field-named text format.
      */
    protected def snapshotCodec: SnapshotCodec = SnapshotCodec.Yaml

    /** Assert that `actual`, normalized then encoded through [[snapshotCodec]], matches the stored schema snapshot named `name`.
      *
      * Algorithm:
      *   1. Normalize: `norm = config(SnapshotConfig.apply[A]).modify.applyTo(actual)`, applied before encode AND before compare.
      *   2. Encode `norm` by codec kind: a Text codec produces a UTF-8 string, a Binary codec produces raw wire bytes.
      *   3. Compute the storage path `${snapshotDir}/${this.name}/${name}.${snapshotCodec.ext}`.
      *   4. If update mode: write the proposed value and pass.
      *   5. If no snapshot exists yet: write the proposed value and fail with `SnapshotNotFound`.
      *   6. If a snapshot exists: decode it via `Schema[A]`; a decode failure fails with `SnapshotSchemaEvolution`, a decode
      *      success compares structurally and passes when equal, else fails with the changed field paths (plus a textual diff
      *      for Text codecs).
      *
      * @param actual
      *   the value to snapshot; a `Schema[A]` instance must be in scope
      * @param name
      *   the snapshot identifier; must not contain path separators, be empty, be `.` or `..`, or contain a space
      * @param config
      *   customizes the assertion through the `SnapshotConfig[A]` builder (today `.normalize`); defaults to `identity`
      * @tparam A
      *   the type of the value; a `Schema[A]` instance must be in scope
      */
    protected inline def assertSchemaSnapshot[A](
        inline actual: A,
        inline name: String,
        inline config: SnapshotConfig[A] => SnapshotConfig[A] = identity[SnapshotConfig[A]]
    )(using inline schema: Schema[A], inline frame: Frame, inline as: kyo.test.AssertScope): Unit < (S & Async & Abort[Throwable] & Scope) =
        as.recordEvaluated()
        validateSnapshotName(name)
        val codec = snapshotCodec
        val norm  = normalizeWith(config, actual)
        val path  = s"${snapshotDir}/${this.name}/${name}.${codec.ext}"
        storeAndCompare[A](codec, path, norm) { (storedValue, textDiff) =>
            val ops = Changeset[A](storedValue, norm)(using schema, frame).operations
            if ops.nonEmpty then
                val paths = snapshotChangedPaths(ops)
                val diagram = textDiff match
                    case Maybe.Present(diff) => s"changed fields: ${paths.mkString(", ")}\n\n$diff"
                    case Maybe.Absent        => s"changed fields: ${paths.mkString(", ")}"
                val failure = new AssertionFailed(diagram, frame, Maybe.Present("Snapshot mismatch"), Maybe.Absent)
                as.record(failure)
                throw failure
            end if
        }
    end assertSchemaSnapshot

    /** Assert that a deterministic spread of `sampleCount` generated `A` values, normalized then encoded through [[snapshotCodec]] as one
      * document, matches the stored golden identified by `name`.
      *
      * Draws `config.sampleCount` values from the required `Gen[A]` via `gen.samples(config.seed, config.size, config.sampleCount)`,
      * normalizes each through the accumulated `Modify[A]`, and encodes the normalized spread as a single `samples`-keyed document. On a
      * later run the stored document is decoded and compared per sample against the freshly-regenerated, identically-normalized spread; a
      * field change is reported as the literal token `sample[N].field.path`.
      *
      * Algorithm:
      *   1. Draw and normalize the spread, guarding `sampleCount >= 1`.
      *   2. Compute the storage path `${snapshotDir}/${this.name}/${name}.golden.${bare}`, `bare = snapshotCodec.ext.stripPrefix("snap.")`.
      *   3. If update mode: write the proposed golden and pass.
      *   4. If no golden exists yet: write the proposed golden and fail with `SnapshotNotFound`.
      *   5. If a golden exists: decode it; a decode failure fails with `SnapshotSchemaEvolution`, a length mismatch fails naming the count
      *      delta, and a per-sample structural difference fails with the index-prefixed changed field paths.
      *
      * @param name
      *   the golden identifier; must not contain path separators, be empty, be `.` or `..`, or contain a space
      * @param config
      *   customizes the assertion through the `GoldenConfig[A]` builder (`sampleCount`/`seed`/`size`/`normalize`); defaults to `identity`
      * @tparam A
      *   the type being sampled; a `Gen[A]` and a `Schema[A]` instance must be in scope
      */
    protected inline def assertGoldenSnapshot[A](
        inline name: String,
        inline config: GoldenConfig[A] => GoldenConfig[A] = identity[GoldenConfig[A]]
    )(using
        inline gen: Gen[A],
        inline schema: Schema[A],
        inline frame: Frame,
        inline as: kyo.test.AssertScope
    ): Unit < (S & Async & Abort[Throwable] & Scope) =
        as.recordEvaluated()
        validateSnapshotName(name)
        val norm = goldenSamples(config, gen)
        goldenStoreAndCompare(name, norm)(using schema, frame, as)
    end assertGoldenSnapshot

    private def normalizeWith[A](config: SnapshotConfig[A] => SnapshotConfig[A], value: A): A =
        config(SnapshotConfig.apply[A]).modify.applyTo(value)

    /** Stores or compares one encoded document `doc` at `path` through `codec`, owning the codec-Text/Binary dispatch, the
      * update-mode write, the absent-write-then-`SnapshotNotFound` first-run path, and the three-way decode branch
      * (`Result.Failure` routes to `SnapshotSchemaEvolution`, `Result.Panic` rethrows unwrapped, `Result.Success` invokes
      * `onCompare`) shared by every snapshot flavor that stores through a `Schema[D]`.
      *
      * `onCompare` receives the decoded stored value plus, for a `Text` codec, the unified textual diff between the stored
      * and proposed encodings (`Maybe.Absent` for a `Binary` codec, which carries no textual diff).
      */
    private def storeAndCompare[D](
        codec: SnapshotCodec,
        path: String,
        doc: D
    )(onCompare: (stored: D, textDiff: Maybe[String]) => Unit)(using schema: Schema[D], frame: Frame): Unit =
        codec match
            case SnapshotCodec.Text(c, _) =>
                val proposed = schema.encodeString(doc)(using c, frame)
                if snapshotUpdateMode then
                    SnapshotStore.write(path, proposed)
                else
                    SnapshotStore.read(path) match
                        case Maybe.Absent =>
                            SnapshotStore.write(path, proposed)
                            throw SnapshotNotFound(path)(using frame)
                        case Maybe.Present(stored) =>
                            schema.decodeString(stored)(using c, frame) match
                                case Result.Failure(err) =>
                                    throw SnapshotSchemaEvolution(
                                        path,
                                        s"${err.getMessage}\n\n${SnapshotDiff.render(stored, proposed)}"
                                    )(using frame)
                                case Result.Panic(err) =>
                                    throw err
                                case Result.Success(storedValue) =>
                                    onCompare(storedValue, Maybe.Present(SnapshotDiff.render(stored, proposed)))
                    end match
                end if
            case SnapshotCodec.Binary(c, _) =>
                val proposed = schema.encode(doc)(using c, frame)
                if snapshotUpdateMode then
                    SnapshotStore.writeBytes(path, proposed)
                else
                    SnapshotStore.readBytes(path) match
                        case Maybe.Absent =>
                            SnapshotStore.writeBytes(path, proposed)
                            throw SnapshotNotFound(path)(using frame)
                        case Maybe.Present(stored) =>
                            schema.decode(stored)(using c, frame) match
                                case Result.Failure(err) =>
                                    throw SnapshotSchemaEvolution(path, err.getMessage)(using frame)
                                case Result.Panic(err) =>
                                    throw err
                                case Result.Success(storedValue) =>
                                    onCompare(storedValue, Maybe.Absent)
                    end match
                end if
        end match
    end storeAndCompare

    private def validateSnapshotName(name: String): Unit =
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
    end validateSnapshotName

    private def snapshotChangedPaths(ops: Chunk[Changeset.Patch], prefix: Chunk[String] = Chunk.empty): Chunk[String] =
        ops.flatMap {
            case Changeset.Patch.Nested(fp, nested) => snapshotChangedPaths(nested, prefix ++ fp)
            case patch                              => Chunk((prefix ++ patch.fieldPath).mkString("."))
        }

    private def goldenSamples[A](config: GoldenConfig[A] => GoldenConfig[A], gen: Gen[A]): Chunk[A] =
        val resolvedConfig = config(GoldenConfig.apply[A])
        if resolvedConfig.sampleCount < 1 then
            throw new IllegalArgumentException(s"golden sampleCount must be >= 1, got ${resolvedConfig.sampleCount}")
        end if
        gen.samples(resolvedConfig.seed, resolvedConfig.size, resolvedConfig.sampleCount).map(sample =>
            resolvedConfig.modify.applyTo(sample)
        )
    end goldenSamples

    private def goldenPath(name: String, bare: String): String =
        s"${snapshotDir}/${this.name}/${name}.golden.${bare}"

    private def goldenStoreAndCompare[A](
        name: String,
        norm: Chunk[A]
    )(using schema: Schema[A], frame: Frame, as: kyo.test.AssertScope): Unit =
        val codec        = snapshotCodec
        val bare         = codec.ext.stripPrefix("snap.")
        val path         = goldenPath(name, bare)
        val doc          = GoldenSamples(norm)
        val goldenSchema = summon[Schema[GoldenSamples[A]]]
        storeAndCompare[GoldenSamples[A]](codec, path, doc) { (storedDoc, textDiff) =>
            compareGolden(storedDoc.samples, norm, textDiff)
        }(using goldenSchema, frame)
    end goldenStoreAndCompare

    private def compareGolden[A](
        stored: Chunk[A],
        actual: Chunk[A],
        textDiff: Maybe[String]
    )(using schema: Schema[A], frame: Frame, as: kyo.test.AssertScope): Unit =
        if stored.length != actual.length then
            val failure = new AssertionFailed(
                s"golden holds ${stored.length} samples, run produced ${actual.length}; re-bless if intended",
                frame,
                Maybe.Present("Golden sample count mismatch"),
                Maybe.Absent
            )
            as.record(failure)
            throw failure
        end if
        val paths = compareSamples(stored, actual)
        if paths.nonEmpty then
            val diagram = textDiff match
                case Maybe.Present(diff) => s"changed fields: ${paths.mkString(", ")}\n\n$diff"
                case Maybe.Absent        => s"changed fields: ${paths.mkString(", ")}"
            val failure = new AssertionFailed(diagram, frame, Maybe.Present("Golden mismatch"), Maybe.Absent)
            as.record(failure)
            throw failure
        end if
    end compareGolden

    private def compareSamples[A](stored: Chunk[A], actual: Chunk[A])(using schema: Schema[A], frame: Frame): Chunk[String] =
        Chunk.from(0 until stored.length).flatMap { i =>
            val ops = Changeset[A](stored(i), actual(i))(using schema, frame).operations
            if ops.isEmpty then Chunk.empty[String]
            else snapshotChangedPaths(ops, Chunk(s"sample[$i]"))
        }

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
