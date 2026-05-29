package kyo

import java.nio.file.Files
import java.nio.file.Paths

class OnceCellTest extends Test:

    /** Resolve a path relative to the build root.
      *
      * sbt cross-project sets user.dir to the platform sub-directory (e.g. kyo-tasty/jvm). Walking two levels up reaches the repository
      * root.
      */
    private def buildPath(relative: String): java.nio.file.Path =
        Paths.get(java.lang.System.getProperty("user.dir")).getParent.getParent.resolve(relative)

    // Test 1 (A3): canonical // Unsafe: comments present before each asInstanceOf call, pins A3.
    "OnceCellTest: canonical // Unsafe: comments present before each asInstanceOf call" in {
        val onceCellSrc = Files.readString(
            buildPath("kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/OnceCell.scala")
        )
        val lines = onceCellSrc.split("\n").toVector

        // Count lines whose trim starts with "// Unsafe:" and whose next non-blank line contains asInstanceOf.
        var count = 0
        val n     = lines.length
        var i     = 0
        while i < n do
            val trimmed = lines(i).trim
            if trimmed.startsWith("// Unsafe:") then
                var j = i + 1
                while j < n && lines(j).trim.isEmpty do j += 1
                if j < n && lines(j).contains("asInstanceOf") then
                    count += 1
            end if
            i += 1
        end while

        assert(count == 3, s"Expected 3 canonical // Unsafe: comments preceding asInstanceOf, found $count")
    }

end OnceCellTest
