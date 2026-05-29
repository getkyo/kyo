package kyo.internal

/** JS platform implementation: file I/O not available, returns empty/false. */
private[kyo] object JsonRpcPortFileOps:

    def readFileIfExists(path: String): Option[String] = None

    def fileExists(path: String): Boolean = false

end JsonRpcPortFileOps
