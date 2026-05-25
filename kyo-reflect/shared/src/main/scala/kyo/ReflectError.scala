package kyo

/** Closed error ADT for kyo-reflect.
  *
  * No exception ever crosses the public API boundary; every failure path returns `Abort.fail(ReflectError.X)`.
  */
enum ReflectError derives CanEqual:
    case FileNotFound(path: String)
    case CorruptedFile(path: String, at: Long, reason: String)
    case UnsupportedVersion(found: Reflect.Version, supported: Reflect.Version)
    case InconsistentClasspath(file: String, expectedUuid: String, foundUuid: String)
    case MalformedSection(name: String, reason: String)
    case SymbolNotFound(fqn: String)
    case ClassfileFormatError(path: String, reason: String)
    case ParameterizedTypeNotAllowed(tag: String)
    case ClasspathClosed
    case ClasspathBuilding
    case SnapshotFormatError(path: String, reason: String)
    case SnapshotVersionMismatch(found: Reflect.Version, supported: Reflect.Version)
    case SnapshotIoError(cause: String)
    case NotImplemented(feature: String)
end ReflectError
