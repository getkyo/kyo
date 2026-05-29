package kyo.internal

/** Native platform implementation: file I/O not available via java.nio.file, returns empty/false. */
private[kyo] object JsonRpcPortFileOps:

    def readFileIfExists(path: String): Option[String] = None

    def fileExists(path: String): Boolean = false

end JsonRpcPortFileOps
