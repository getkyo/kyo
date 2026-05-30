package kyo

/** Closed error ADT for kyo-tasty.
  *
  * No exception ever crosses the public API boundary; every failure path returns `Abort.fail(TastyError.X)`.
  */
enum TastyError derives CanEqual:
    case FileNotFound(path: String)
    case CorruptedFile(path: String, at: Long, reason: String)
    case UnsupportedVersion(found: Tasty.Version, supported: Tasty.Version)
    case InconsistentClasspath(file: String, expectedUuid: java.util.UUID, foundUuid: java.util.UUID)
    case MalformedSection(name: String, reason: String, byteOffset: Long)
    case SymbolNotFound(fqn: String)
    case ClassfileFormatError(path: String, reason: String, byteOffset: Long)
    case ClasspathClosed
    case ClasspathBuilding
    case SnapshotFormatError(path: String, reason: String, byteOffset: Long)
    case SnapshotVersionMismatch(found: Tasty.Version, supported: Tasty.Version)
    case SnapshotIoError(cause: String)
    case NotImplemented(feature: String)
end TastyError
