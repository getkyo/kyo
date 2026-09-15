package kyo.internal.tasty.query

import kyo.*

/** JS platform-specific ZipHandle factory. Jar reading is not available on JS. */
private[kyo] object ZipHandlePlatform:

    def open(root: String)(using Frame): Maybe[ZipHandle] < (Sync & Scope & Abort[TastyError]) =
        Maybe.Absent

    /** No-op on JS: jar reads are not supported, so no pool is needed. */
    def withPool[A, S](body: A < S)(using Frame): A < (S & Sync & Scope) =
        body

    /** Jar entry reads are not supported on JS. */
    def readJarEntry(jarPath: String, entryName: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        Abort.fail(TastyError.FileNotFound(s"$jarPath!/$entryName: jar reading not supported on JS"))

    /** jrt:/ filesystem is not available on JS. */
    def readJrtEntry(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        Abort.fail(TastyError.FileNotFound(s"$path: jrt:/ reading not supported on JS"))

    /** jrt:/ filesystem is not available on JS. */
    def listJrtEntries(root: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
        Sync.defer(Chunk.empty)

end ZipHandlePlatform
