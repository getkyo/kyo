package kyo

import kyo.kernel.Loop

class PathRemovalTest extends kyo.test.Test[Any]:

    private def removeConcurrently(first: Path, second: Path)(using
        Frame
    ): (Result[FileSystemException, Unit], Result[FileSystemException, Unit]) < (Async & Scope) =
        for
            ready <- Latch.init(2)
            start <- Latch.init(1)
            left  <- Fiber.init {
                ready.release.andThen(start.await).andThen(Abort.run[FileSystemException](Path.run(first.removeAll)))
            }
            right <- Fiber.init {
                ready.release.andThen(start.await).andThen(Abort.run[FileSystemException](Path.run(second.removeAll)))
            }
            _            <- ready.await
            _            <- start.release
            firstResult  <- left.get
            secondResult <- right.get
        yield (firstResult, secondResult)

    "concurrent removeAll calls on the same tree both succeed" in {
        Loop.repeat(32) {
            Scope.run(Path.run {
                for
                    dir     <- Path.tempDir("kyo-remove-same")
                    _       <- Kyo.foreachDiscard(0 until 64)(i => (dir / s"file-$i").write("entry"))
                    results <- removeConcurrently(dir, dir)
                    exists  <- dir.exists
                yield
                    assert(results == (Result.unit, Result.unit), s"removal results: $results")
                    assert(!exists)
            })
        }
    }

    "concurrent parent and child removals preserve their sibling" in {
        Loop.repeat(16) {
            Scope.run(Path.run {
                for
                    dir <- Path.tempDir("kyo-remove-overlap")
                    parent  = dir / "parent"
                    child   = parent / "child"
                    sibling = dir / "keep.txt"
                    _            <- sibling.write("keep me")
                    _            <- Kyo.foreachDiscard(0 until 64)(i => (child / s"file-$i").write("entry"))
                    results      <- removeConcurrently(parent, child)
                    parentExists <- parent.exists
                    contents     <- sibling.read
                yield
                    assert(results == (Result.unit, Result.unit), s"removal results: $results")
                    assert(!parentExists)
                    assert(contents == "keep me")
            })
        }
    }

    "concurrent removals of separate trees preserve their sibling" in {
        Loop.repeat(16) {
            Scope.run(Path.run {
                for
                    dir <- Path.tempDir("kyo-remove-separate")
                    first   = dir / "first"
                    second  = dir / "second"
                    sibling = dir / "keep.txt"
                    _            <- sibling.write("keep me")
                    _            <- Kyo.foreachDiscard(0 until 64)(i => (first / s"file-$i").write("entry"))
                    _            <- Kyo.foreachDiscard(0 until 64)(i => (second / s"file-$i").write("entry"))
                    results      <- removeConcurrently(first, second)
                    firstExists  <- first.exists
                    secondExists <- second.exists
                    contents     <- sibling.read
                yield
                    assert(results == (Result.unit, Result.unit), s"removal results: $results")
                    assert(!firstExists)
                    assert(!secondExists)
                    assert(contents == "keep me")
            })
        }
    }
end PathRemovalTest
