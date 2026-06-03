package kyo

/** Closed error ADT for kyo-tasty.
  *
  * Every failure path in the public API is `Abort.fail(TastyError.X)`. No exception ever crosses the API
  * boundary: a bug in user TASTy bytes, a missing class, a malformed snapshot, or an unsupported feature all
  * resolve into one of the named cases below, and the caller can exhaustively pattern-match.
  *
  * **Grouping.** The cases group by the surface they cover.
  *
  *   - **File-level decode**: `FileNotFound`, `CorruptedFile`, `UnsupportedVersion`, `MalformedSection`,
  *     `ClassfileFormatError`, `UnknownTagInPosition`, `InconsistentClasspath`. Raised during `Classpath.init`;
  *     in `ErrorMode.SoftFail` they accumulate in `cp.errors`, in `ErrorMode.FailFast` they abort the open.
  *   - **Lookup**: `SymbolNotFound` (orphan `SymbolId`), `NotFound` (FQN absent), `InvalidFqn` (caller passed
  *     a syntactically invalid FQN to a `require*` method).
  *   - **Snapshot cache**: `SnapshotFormatError`, `SnapshotVersionMismatch`, `SnapshotIoError`,
  *     `DigestMismatch`. Raised by the `initCached` path when the cache file is corrupt, stale, or unreadable.
  *   - **Lifecycle**: `ClasspathClosed` (use-after-scope-exit), `ClasspathBuilding` (read during construction).
  *   - **Platform / reserved**: `UnsupportedPlatform` (JVM-only feature called on JS / Native),
  *     `NotImplemented` (a TASTy feature recognised but not yet decoded by this release).
  *
  * **Equality.** Cases derive `CanEqual` so two `TastyError` values can be compared with `==` without an
  * import; payload fields are compared structurally.
  *
  * **Why `enum`, not `KyoException`?** This is a deliberate departure from the project default. `KyoException`
  * exists to enrich values that get thrown across an effect-as-throwable boundary (capturing a `Frame`,
  * suppressing the stack trace, and switching on dev / prod for the formatted message). `TastyError` is the
  * opposite of that: it is the payload of `Abort[TastyError]` and is never thrown. Every callsite that surfaces
  * a failure uses `Abort.fail(TastyError.X)`; every callsite that observes a failure pattern-matches on a
  * `Result[TastyError, A]` (or runs an `Abort.run`). Because no value of this type ever crosses the
  * `Throwable` boundary, it does not need `Frame` plumbing, NoStackTrace, or dev-aware formatting; it needs to
  * be a pure ADT that the compiler can check for exhaustive matching. Modelling it as an `enum` gives exactly
  * that and nothing more, which is the kyo-philosophy-correct shape for errors carried on the effect row.
  */
enum TastyError derives CanEqual:

    /** The TASTy file was not found at the given path. */
    case FileNotFound(path: String)

    /** The TASTy file is syntactically corrupt at `at` bytes from the start. */
    case CorruptedFile(path: String, at: Long, reason: String)

    /** The TASTy file's format version is outside the range this reader supports. */
    case UnsupportedVersion(found: Tasty.Version, supported: Tasty.Version)

    /** The classpath is internally inconsistent.
      *
      * Two uses:
      *   1. UUID mismatch: a TASTy file's UUID does not match the UUID recorded in the companion classfile. `file` is the .tasty file path.
      *      `expectedUuid` / `foundUuid` carry the mismatched UUIDs.
      *   2. FQN collision under `ErrorMode.FailFast`: two source roots each define a symbol under the same fully-qualified name. `file`
      *      carries the colliding FQN. `expectedUuid` and `foundUuid` are set to the zero UUID (sentinel) in this case.
      */
    case InconsistentClasspath(file: String, expectedUuid: java.util.UUID, foundUuid: java.util.UUID)

    /** A section of the TASTy or KRFL file could not be decoded at `byteOffset`. */
    case MalformedSection(name: String, reason: String, byteOffset: Long)

    /** A symbol with the fully-qualified name `fqn` is required but absent from the classpath. */
    case SymbolNotFound(fqn: String)

    /** A required symbol or module with name `fqn` was not found in the classpath.
      *
      * Raised by `Classpath.requireClass`, `requireTrait`, `requireObject`, `requireClassLike`, `requirePackage`, and `requireModule` when
      * the lookup returns Absent.
      */
    case NotFound(fqn: String)

    /** A Java classfile at `path` contains an undecodable constant-pool or attribute at `byteOffset`. */
    case ClassfileFormatError(path: String, reason: String, byteOffset: Long)

    /** The Classpath was closed before this operation completed.
      *
      * `context` carries the name of the failing operation and any relevant identifier (for example "decodeBody(sym.id=42)"), so the caller
      * can identify which classpath and which operation triggered the error.
      */
    case ClasspathClosed(context: String)

    /** The Classpath is still being built; a concurrent read was attempted before open completed.
      *
      * `context` carries the name of the failing operation and any relevant identifier (for example "finalizeMerge brokenFqnCount=3"), so
      * the caller can identify the source.
      */
    case ClasspathBuilding(context: String)

    /** A KRFL snapshot file at `path` could not be parsed at `byteOffset`. */
    case SnapshotFormatError(path: String, reason: String, byteOffset: Long)

    /** The KRFL snapshot was written by a kyo-tasty version whose major version differs from this reader's. */
    case SnapshotVersionMismatch(found: Tasty.Version, supported: Tasty.Version)

    /** An I/O error occurred while reading or writing a KRFL snapshot. */
    case SnapshotIoError(cause: String)

    /** Returned only when a TASTy feature is recognized but not yet implemented in this version of kyo-tasty.
      *
      * This variant is reserved for features that are genuinely deferred to a later kyo-tasty release. It is NEVER returned for "this
      * attribute does not apply to this symbol kind" -- that case is represented by Maybe.Absent on the relevant field. It is also NEVER
      * returned for "this TASTy tag is unrecognized" -- unrecognized tags produce Tree.Unknown for graceful degradation.
      *
      * Valid uses:
      *   - A snapshot section written by a future kyo-tasty version that this reader has not learned to consume yet (forward-compat
      *     deserialization fallback in SnapshotReader.parseErrorString).
      *
      * Invalid uses (use Maybe.Absent or a specific decode-error variant instead):
      *   - Symbol.body called on a Package symbol (no body bytes) -- use Maybe.Absent.
      *   - Symbol.body called on a Java symbol (no AST in classfiles) -- use Maybe.Absent.
      *   - Symbol.declaredType called on a symbol that has no declared type -- use Maybe.Absent.
      *   - An unrecognized TASTy tag during tree decode -- use Tree.Unknown for graceful degradation.
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
      * Thrown by the exhaustive tag-dispatch helpers (`TagKind.TypePositionTag.from`, etc.) when a raw byte does not match any known
      * tag in that position. This replaces the previous silent `case other => warn(...); Named(-1)` fallback pattern in
      * TypeUnpickler and TreeUnpickler, making unknown-tag encounters fail loudly at decode time rather than producing sentinel
      * symbols that propagate silently downstream.
      *
      * `tag` is the raw byte value as an Int. TASTy tag bytes are in the range 0-255; values outside that range are not produced by any
      * internal decode path but are not rejected at construction time (Int is kept to avoid a widening cascade in callers). Callers
      * constructing this variant directly should ensure tag is in 0-255.
      *
      * `position` is a human-readable label for the decode position (e.g. "type", "tree", "modifier").
      */
    case UnknownTagInPosition(tag: Int, position: String)

    /** The caller supplied a syntactically invalid fully-qualified name to a `requireX` method.
      *
      * Raised by `Classpath.requireClass`, `requireTrait`, `requireObject`, `requireClassLike`, `requirePackage`, `requireModule`, and
      * `requireSymbol` when the supplied `fqn` argument fails a pre-lookup validation check. Currently the only validation is that `fqn`
      * must be non-empty: an empty string is a caller-side programming error rather than an honest not-found result, and deserves a
      * distinct error so the caller can distinguish "I asked for the wrong thing" from "the classpath does not contain this class".
      *
      * `fqn` is the offending input. `reason` is a human-readable explanation (e.g. "fqn must be non-empty").
      */
    case InvalidFqn(fqn: String, reason: String)

    /** The embedded `inputDigest` in a KRFL snapshot file does not match the caller-supplied expected digest.
      *
      * Raised by `SnapshotReader.read` (and `readMapped`) when `verifyDigest = true` and the 8-byte FNV-1a hash stored at bytes 16-23 in
      * the snapshot header does not equal `expected`. This signals that the snapshot was written for a different set of inputs than the ones
      * the caller presented (stale cache, hash collision, or corrupt file).
      *
      * `expected` is the hex-encoded digest the caller expected. `actual` is the hex-encoded digest read from the snapshot.
      */
    case DigestMismatch(expected: String, actual: String)
end TastyError
