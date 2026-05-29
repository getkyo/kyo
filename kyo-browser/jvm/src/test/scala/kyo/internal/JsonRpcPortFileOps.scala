package kyo.internal

import java.nio.file.Files
import java.nio.file.Paths

/** JVM platform implementation: reads source files from the filesystem. */
private[kyo] object JsonRpcPortFileOps:

    def readFileIfExists(path: String): Option[String] =
        val candidates = Seq(
            path,
            s"../$path",
            s"../../$path"
        )
        candidates
            .map(p => Paths.get(p))
            .find(p => Files.exists(p))
            .map(p => new String(Files.readAllBytes(p)))
    end readFileIfExists

    def fileExists(path: String): Boolean =
        val candidates = Seq(
            path,
            s"../$path",
            s"../../$path"
        )
        candidates.map(p => Paths.get(p)).exists(p => Files.exists(p))
    end fileExists

end JsonRpcPortFileOps
