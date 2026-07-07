package kyo

class PathSeparatorsTest extends kyo.test.Test[Any]:

    "pathSeparator returns a non-empty String matching a known platform value" in {
        val sep = Path.pathSeparator
        assert(sep.nonEmpty)
        assert(Set(":", ";").contains(sep))
    }

    "fileSeparator returns a non-empty String matching a known platform value" in {
        val sep = Path.fileSeparator
        assert(sep.nonEmpty)
        assert(Set("/", "\\").contains(sep))
    }

    "pathSeparator and fileSeparator are stable across repeated reads" in {
        val ps0 = Path.pathSeparator
        val fs0 = Path.fileSeparator
        for _ <- 1 to 5 do
            assert(Path.pathSeparator == ps0)
            assert(Path.fileSeparator == fs0)
        succeed
    }

    "pathSeparator equals java.io.File.pathSeparator on JVM".onlyJvm in {
        assert(Path.pathSeparator == java.io.File.pathSeparator)
    }

    "fileSeparator equals java.io.File.separator on JVM".onlyJvm in {
        assert(Path.fileSeparator == java.io.File.separator)
    }

end PathSeparatorsTest
