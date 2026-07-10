package kyo

class PathSizeTest extends kyo.test.Test[Any]:

    "size of a freshly-written file equals the written byte count" in {
        Scope.run(Path.run {
            for
                dir <- Path.tempDir("kyo-path-size-test")
                file = dir / "sz.bin"
                _ <- file.writeBytes(Span.from(Array.fill(2048)(0x41.toByte)))
                n <- file.size
                _ <- dir.removeAll
            yield assert(n == 2048L)
            end for
        })
    }

    "size of a missing path fails with FileReadException via Abort" in {
        val file = Path("/nonexistent-kyo-path-size-test-xyz")
        Abort.run[FileException](Path.runReadOnly(file.size)).map {
            case Result.Failure(_: FileReadException) => succeed
            case other                                => fail(s"Expected FileReadException, got $other")
        }
    }

end PathSizeTest
