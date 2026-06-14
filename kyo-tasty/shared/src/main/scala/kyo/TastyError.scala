package kyo

/** Closed error ADT for kyo-tasty.
  *
  * Every failure path in the public API surfaces as `Abort.fail(TastyError.X)`; no exception crosses the API
  * boundary. Callers pattern-match exhaustively on the cases below.
  *
  * The cases group by surface:
  *
  *   - File-level decode: `FileNotFound`, `CorruptedFile`, `UnsupportedVersion`, `MalformedSection`,
  *     `ClassfileFormatError`, `UnknownTagInPosition`, `InconsistentClasspath`, `FullNameCollisionError`. Raised during
  *     `Tasty.withClasspath`. Under `ErrorMode.SoftFail` they accumulate in `classpath.errors`; under
  *     `ErrorMode.FailFast` they abort the open.
  *   - Lookup: `SymbolNotFound` (orphan `SymbolId`), `NotFound` (fully-qualified name absent), `InvalidFullName` (syntactically
  *     invalid fully-qualified name passed to a `require*` method).
  *   - Snapshot cache: `SnapshotFormatError`, `SnapshotVersionMismatch`, `SnapshotIoError`, `DigestMismatch`.
  *     Raised by the `Tasty.withClasspath(roots, Present(cacheDir))` path when the cache file is corrupt,
  *     stale, or unreadable.
  *   - Lifecycle: `ClasspathClosed` (use-after-scope-exit), `ClasspathBuilding` (read during construction).
  *   - Platform: `UnsupportedPlatform` (JVM-only feature on JS / Native), `NotImplemented` (TASTy feature
  *     recognised but not yet decoded).
  *
  * `derives CanEqual` so two `TastyError` values compare with `==` directly; payload fields are compared
  * structurally.
  *
  * Modelled as an `enum` rather than a `KyoException` because no value of this type is ever thrown. It is the
  * payload of `Abort[TastyError]`, observed by pattern-matching on `Result[TastyError, A]`. The pure ADT shape
  * gives compiler-checked exhaustive matching without `Frame` plumbing or dev-aware formatting.
  */
enum TastyError derives CanEqual:

    /** The TASTy file was not found at the given path. */
    case FileNotFound(path: String)

    /** The TASTy file is syntactically corrupt at `at` bytes from the start. */
    case CorruptedFile(path: String, at: Long, reason: String)

    /** The TASTy file's format version is outside the range this reader supports. */
    case UnsupportedVersion(found: Tasty.Version, supported: Tasty.Version)

    /** A TASTy file's UUID does not match the UUID recorded in the companion classfile.
      *
      * Raised during `Tasty.withClasspath` when the embedded UUID in a `.tasty` file disagrees with the UUID encoded
      * in its sibling `.class` file's `TASTY` attribute. `file` is the `.tasty` file path, and
      * `expectedUuid` / `foundUuid` carry the two mismatched UUIDs as `Tasty.Uuid` values.
      *
      * For fully-qualified-name-level collisions across roots, see `FullNameCollisionError`.
      */
    case InconsistentClasspath(file: String, expectedUuid: Tasty.Uuid, foundUuid: Tasty.Uuid)

    /** The caller supplied a string that is not a 36-character hex UUID to `Tasty.Uuid.parse`.
      *
      * `input` is the offending input verbatim. Raised exclusively at parse time; this variant is never
      * written to the snapshot wire format.
      */
    case InvalidUuid(input: String)

    /** Two source roots define a symbol under the same fully-qualified name.
      *
      * Raised by `Tasty.withClasspath` under `ErrorMode.FailFast` on the first observed collision. The colliding
      * fully-qualified name is carried in `fullName`. Under `ErrorMode.SoftFail` the collision is recorded as a
      * `Classpath.FullNameCollision` diagnostic and the deterministic last-write-wins winner is kept; this variant
      * is only raised in FailFast mode.
      */
    case FullNameCollisionError(fullName: String)

    /** A section of the TASTy or KRFL file could not be decoded at `byteOffset`. */
    case MalformedSection(name: String, reason: String, byteOffset: Long)

    /** A symbol with the fully-qualified name `fullName` is required but absent from the classpath. */
    case SymbolNotFound(fullName: String)

    /** A required symbol or module with name `fullName` was not found in the classpath.
      *
      * Raised by `Classpath.requireClass`, `requireTrait`, `requireObject`, `requireClassLike`, `requirePackage`, and `requireModule` when
      * the lookup returns Absent.
      */
    case NotFound(fullName: String)

    /** A Java classfile at `path` contains an undecodable constant-pool or attribute at `byteOffset`. */
    case ClassfileFormatError(path: String, reason: String, byteOffset: Long)

    /** The Classpath was closed before this operation completed.
      *
      * `context` carries the name of the failing operation and any relevant identifier (for example "decodeBody(symbol.id=42)"), so the caller
      * can identify which classpath and which operation triggered the error.
      */
    case ClasspathClosed(context: String)

    /** The Classpath is still being built; a concurrent read was attempted before open completed.
      *
      * `context` carries the name of the failing operation and any relevant identifier (for example "finalizeMerge brokenFullNameCount=3"), so
      * the caller can identify the source.
      */
    case ClasspathBuilding(context: String)

    /** A KRFL snapshot file at `path` could not be parsed at `byteOffset`. */
    case SnapshotFormatError(path: String, reason: String, byteOffset: Long)

    /** The KRFL snapshot was written by a kyo-tasty version whose major version differs from this reader's. */
    case SnapshotVersionMismatch(found: Tasty.Version, supported: Tasty.Version)

    /** An I/O error occurred while reading or writing a KRFL snapshot. */
    case SnapshotIoError(cause: String)

    /** Reserved for TASTy features recognised by this reader but not yet implemented.
      *
      * Not returned for "this attribute does not apply to this symbol kind" (that case is `Maybe.Absent` on the relevant field),
      * nor for "this TASTy tag is unrecognised" (unrecognised tags produce `Tree.Unknown` for graceful degradation).
      *
      * Valid use: a snapshot section written by a kyo-tasty version this reader does not consume.
      */
    case NotImplemented(feature: String)

    /** A platform-specific feature was invoked on a platform that does not support it.
      *
      * The primary use case is `Classpath.initWithPlatformModules`, which resolves `jrt-fs.jar` from `java.home` and is therefore
      * JVM-only. Calling it on Scala.js or Scala Native raises this error. The `feature` string identifies the unsupported operation.
      */
    case UnsupportedPlatform(feature: String)

    /** An unknown tag byte was read at a position where only a known set of tags is valid.
      *
      * Raised by the exhaustive tag-dispatch helpers (`TagKind.TypePositionTag.from`, etc.) when a raw byte does not match any
      * known tag in that position. Unknown-tag encounters fail loudly at decode time rather than producing sentinel
      * symbols that propagate silently downstream.
      *
      * `tag` is the raw byte value as an Int. TASTy tag bytes are in the range 0-255; values outside that range are not produced by any
      * internal decode path but are not rejected at construction time. Callers constructing this variant directly should ensure
      * tag is in 0-255.
      *
      * `position` is a human-readable label for the decode position (e.g. "type", "tree", "modifier").
      */
    case UnknownTagInPosition(tag: Int, position: String)

    /** The caller supplied a syntactically invalid fully-qualified name to a `requireX` method.
      *
      * Raised by `Classpath.requireClass`, `requireTrait`, `requireObject`, `requireClassLike`, `requirePackage`, `requireModule`, and
      * `requireSymbol` when the supplied `fullName` argument fails a pre-lookup validation check. Currently the only validation is that `fullName`
      * must be non-empty: an empty string is a caller-side programming error rather than an honest not-found result, and deserves a
      * distinct error so the caller can distinguish "I asked for the wrong thing" from "the classpath does not contain this class".
      *
      * `fullName` is the offending input. `reason` is a human-readable explanation (e.g. "fullName must be non-empty").
      */
    case InvalidFullName(fullName: String, reason: String)

    /** The embedded `inputDigest` in a KRFL snapshot file does not match the caller-supplied expected digest.
      *
      * Raised by `SnapshotReader.read` (and `readMapped`) when `expectedDigest` is Present and the 8-byte xxh64-custom hash stored at bytes
      * 16-23 in the snapshot header does not equal the expected value. This signals that the snapshot was written for a different set of
      * inputs than the ones the caller presented (stale cache, hash collision, or corrupt file).
      *
      * `expected` is the hex-encoded digest the caller expected. `actual` is the hex-encoded digest read from the snapshot.
      */
    case DigestMismatch(expected: String, actual: String)

    /** A parent-walk shape not covered by the subtyping engine's matcher.
      *
      * Produced when `Classpath.isSubtypeOf` or `Tasty.isSubtypeOf` meets a parent whose TASTy type
      * shape is not `Named(id)` or `Applied(Named(id), _)`. `Classpath.isSubtypeOf` returns this as
      * `Result.Failure`; `Tasty.isSubtypeOf` surfaces it via `Abort.fail`. Produced at runtime during
      * subtype checks, not during classpath loading.
      *
      * `shape` is a short label of the unhandled constructor (e.g. `"Applied(TermRef,_)"`); `lhs` and `rhs`
      * carry the actual type values; `file` is the TASTy file the symbol was loaded from.
      *
      * After a KRFL snapshot round-trip, `lhs` and `rhs` decode to `Type.Nothing` when the original `Type` is
      * not in the snapshot encoder's covered set (Named, Any, Nothing, Applied, TermRef, TypeRef, Tuple,
      * Function, ContextFunction, ByName, Repeated, Array). Refinement, AndType, OrType, MatchType, and
      * FlexibleType read back as `Type.Nothing`.
      */
    case UnhandledSubtypingCase(shape: String, lhs: kyo.Tasty.Type, rhs: kyo.Tasty.Type, file: String)

    /** A loading-phase symbol placeholder survived to `finalizeMerge` without being resolved.
      *
      * Accumulated in `decodeCtx.errors` (not a hard failure) when a `LoadingSymbol.Placeholder` instance
      * is encountered at the conversion boundary in `ClasspathOrchestrator.materializeSymbols`. This means
      * a cross-file symbol reference whose defining file was absent from the loaded classpath was never
      * replaced by a real `LoadingSymbol.Materialising` during Pass C.
      *
      * `name` is the symbol's simple name (or fully-qualified name when available). `idx` is the 0-based position of the
      * placeholder in the loading array at the time the error was recorded.
      */
    case UnresolvedReference(name: String, idx: Int)

    /** A producer at `internal/tasty/symbol/TypedSymbolFactory.scala` cannot resolve a declared
      * type and is operating in `ErrorMode.SoftFail`. Accumulated in `classpath.errors`.
      *
      * `file` is the source file for the symbol (from `sourcePosition.sourceFile`) or `"<unknown>"` when
      * absent. `byteOffset` is `0L` because the byte position is not recoverable at materialization time
      * (merge runs after all files are decoded). `reason` identifies which symbol kind triggered the
      * absent declared type.
      */
    case UnknownType(file: String, byteOffset: Long, reason: String)

    /** A Symbol must be materialised but the declared type is absent; raised at the producer
      * under `ErrorMode.FailFast`. Accumulated in `classpath.errors` under `ErrorMode.SoftFail`.
      *
      * `symbolId` is the id of the symbol whose declared type could not be resolved. `file` is the source
      * file for the symbol or `"<unknown>"` when absent.
      */
    case MissingDeclaredType(symbolId: kyo.Tasty.SymbolId, file: String)
end TastyError
