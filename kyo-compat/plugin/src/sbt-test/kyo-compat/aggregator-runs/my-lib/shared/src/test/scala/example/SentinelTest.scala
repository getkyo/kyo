package example

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}

// Each (backend × platform) cell of myLib runs this test in its own
// forked JVM. The fork inherits two sysprops set in build.sbt:
//   - cell.name     : the cell's `name.value` (e.g. "my-lib-future")
//   - sentinel.dir  : an absolute path to the shared sentinels dir
// The test writes a sentinel file named `<cell.name>` into `<sentinel.dir>`.
// Afterwards `checkAllCellsRan` enumerates the sentinels directory and
// asserts the exact expected set is present — proving the aggregator's
// `Test/test` fan-out reached every cell (no skip, no double-fire).
class SentinelTest extends AnyFunSuite:
    test("writes sentinel for this cell"):
        val cellName = System.getProperty("cell.name")
        val dir      = System.getProperty("sentinel.dir")
        assert(cellName != null && dir != null, "missing sysprops")
        val parent = Paths.get(dir)
        Files.createDirectories(parent)
        Files.writeString(parent.resolve(cellName), "ran")
