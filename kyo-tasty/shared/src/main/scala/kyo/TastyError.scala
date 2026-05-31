package kyo

/** Closed error ADT for kyo-tasty.
  *
  * No exception ever crosses the public API boundary; every failure path returns `Abort.fail(TastyError.X)`.
  */
enum TastyError derives CanEqual:

    /** The TASTy file was not found at the given path. */
    case FileNotFound(path: String)

    /** The TASTy file is syntactically corrupt at `at` bytes from the start. */
    case CorruptedFile(path: String, at: Long, reason: String)

    /** The TASTy file's format version is outside the range this reader supports. */
    case UnsupportedVersion(found: Tasty.Version, supported: Tasty.Version)

    /** The TASTy file's UUID does not match the UUID recorded in the classfile. */
    case InconsistentClasspath(file: String, expectedUuid: java.util.UUID, foundUuid: java.util.UUID)

    /** A section of the TASTy or KRFL file could not be decoded at `byteOffset`. */
    case MalformedSection(name: String, reason: String, byteOffset: Long)

    /** A symbol with the fully-qualified name `fqn` is required but absent from the classpath. */
    case SymbolNotFound(fqn: String)

    /** A Java classfile at `path` contains an undecodable constant-pool or attribute at `byteOffset`. */
    case ClassfileFormatError(path: String, reason: String, byteOffset: Long)

    /** The Classpath was closed before this operation completed. */
    case ClasspathClosed

    /** The Classpath is still being built; a concurrent read was attempted before open completed. */
    case ClasspathBuilding

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
end TastyError
