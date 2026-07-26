package kyo.test.snapshot.internal

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kyo.Maybe
import kyo.Span

/** JVM and Native snapshot file I/O backed by `java.nio.file`.
  *
  * Scala Native ships a compatible implementation of `java.nio.file` so the same source compiles and runs on both platforms without
  * modification. This file is placed in the `jvm-native/` source set so both platform builds include it.
  */
private[snapshot] object SnapshotStorePlatform:

    def read(path: String): Maybe[String] =
        val p = Paths.get(path)
        if Files.exists(p) then Maybe.Present(Files.readString(p))
        else Maybe.Absent
    end read

    def write(path: String, content: String): Unit =
        val p      = Paths.get(path)
        val parent = p.getParent
        // Unsafe: java.nio.file.Path.getParent returns null for top-level paths; skip directory creation in that case
        if parent != null then
            val _ = Files.createDirectories(parent)
        val _ = Files.writeString(
            p,
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    end write

    def readBytes(path: String): Maybe[Span[Byte]] =
        val p = Paths.get(path)
        if Files.exists(p) then Maybe.Present(Span.fromUnsafe(Files.readAllBytes(p)))
        else Maybe.Absent
    end readBytes

    def writeBytes(path: String, content: Span[Byte]): Unit =
        val p      = Paths.get(path)
        val parent = p.getParent
        // Unsafe: java.nio.file.Path.getParent returns null for top-level paths; skip directory creation in that case
        if parent != null then
            val _ = Files.createDirectories(parent)
        val _ = Files.write(
            p,
            content.toArrayUnsafe,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    end writeBytes

end SnapshotStorePlatform
