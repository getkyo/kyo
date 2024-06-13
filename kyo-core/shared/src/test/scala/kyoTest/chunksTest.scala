package kyoTest
import kyo.*
import scala.util.Try

class chunksTest extends KyoTest:

    "append" - {
        "appends a value to an empty chunk" in {
            val chunk = Chunks.init[Int].append(1)
            assert(chunk == Chunks.init(1))
        }

        "appends a value to a non-empty chunk" in {
            val chunk = Chunks.init(1, 2, 3).append(4)
            assert(chunk == Chunks.init(1, 2, 3, 4))
        }
    }

    "head" - {
        "returns the first element of a non-empty chunk" in {
            val chunk = Chunks.init(1, 2, 3).toIndexed
            assert(chunk.head == 1)
        }

        "throws NoSuchElementException for an empty chunk" in {
            val chunk = Chunks.init[Int].toIndexed
            assert(Try(chunk.head).isFailure)
        }
    }

    "tail" - {
        "returns the tail of a non-empty chunk" in {
            val chunk = Chunks.init(1, 2, 3).toIndexed
            assert(chunk.tail == Chunks.init(2, 3))
        }

        "returns an empty chunk for a chunk with a single element" in {
            val chunk = Chunks.init(1).toIndexed
            assert(chunk.tail.isEmpty)
        }

        "returns an empty chunk for an empty chunk" in {
            val chunk = Chunks.init[Int].toIndexed
            assert(chunk.tail.isEmpty)
        }

        "returns the correct tail for a Drop chunk" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5).dropLeft(2).toIndexed
            assert(chunk.tail == Chunks.init(4, 5))
        }

        "of appends" in {
            val chunk = Chunks.init[Int].append(1).append(2).append(3).toIndexed
            assert(chunk.tail == Chunks.init(2, 3))
            assert(chunk.tail.tail == Chunks.init(3))
        }
    }

    "take" - {
        "returns the first n elements of a chunk" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            assert(chunk.take(3) == Chunks.init(1, 2, 3))
        }

        "returns the entire chunk if n is greater than the chunk size" in {
            val chunk = Chunks.init(1, 2, 3)
            assert(chunk.take(5) == Chunks.init(1, 2, 3))
        }

        "returns an empty chunk if n is zero or negative" in {
            val chunk = Chunks.init(1, 2, 3)
            assert(chunk.take(0).isEmpty)
            assert(chunk.take(-1).isEmpty)
        }
    }

    "dropLeft" - {
        "drops the first n elements of a chunk" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            assert(chunk.dropLeft(2) == Chunks.init(3, 4, 5))
        }

        "returns an empty chunk if n is greater than or equal to the chunk size" in {
            val chunk = Chunks.init(1, 2, 3)
            assert(chunk.dropLeft(3).isEmpty)
            assert(chunk.dropLeft(4).isEmpty)
        }

        "returns the entire chunk if n is zero or negative" in {
            val chunk = Chunks.init(1, 2, 3)
            assert(chunk.dropLeft(0) == Chunks.init(1, 2, 3))
            assert(chunk.dropLeft(-1) == Chunks.init(1, 2, 3))
        }
    }

    "dropRight" - {
        "drops the last n elements of a chunk" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            assert(chunk.dropRight(2) == Chunks.init(1, 2, 3))
        }

        "returns an empty chunk if n is greater than or equal to the chunk size" in {
            val chunk = Chunks.init(1, 2, 3)
            assert(chunk.dropRight(3).isEmpty)
            assert(chunk.dropRight(4).isEmpty)
        }

        "returns the entire chunk if n is zero or negative" in {
            val chunk = Chunks.init(1, 2, 3)
            assert(chunk.dropRight(0) == Chunks.init(1, 2, 3))
            assert(chunk.dropRight(-1) == Chunks.init(1, 2, 3))
        }
    }

    "slice" - {
        "returns a slice of a chunk" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            assert(chunk.slice(1, 4) == Chunks.init(2, 3, 4))
        }

        "returns an empty chunk if from is greater than or equal to until" in {
            val chunk = Chunks.init(1, 2, 3)
            assert(chunk.slice(2, 2).isEmpty)
            assert(chunk.slice(3, 2).isEmpty)
        }

        "returns an empty chunk if the chunk is empty" in {
            val chunk = Chunks.init[Int]
            assert(chunk.slice(0, 1).isEmpty)
        }

        "returns the full chunk if `from` is negative and `until` is greater than the size" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            assert(chunk.slice(-1, 6) == Chunks.init(1, 2, 3, 4, 5))
        }

        "returns the full chunk if `from` is 0 and `until` is greater than the size" in {
            val chunk = Chunks.init(1, 2, 3)
            assert(chunk.slice(0, 5) == Chunks.init(1, 2, 3))
        }

        "returns the correct slice when multiple slice operations are chained" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
            val result = chunk.slice(2, 8).slice(1, 5).slice(1, 3)
            assert(result == Chunks.init(5, 6))
        }
    }

    "map" - {
        "with a pure function" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.map(_ * 2)
            assert(result.pure == Chunks.init(2, 4, 6, 8, 10))
        }

        "with a function using IOs" in {
            val chunk    = Chunks.init(1, 2, 3, 4, 5)
            val result   = chunk.map(n => IOs(n * 2))
            val expected = Chunks.init(2, 4, 6, 8, 10)
            assert(IOs.run(result).pure == expected)
        }

        "with a function returning pure values and effectful computations" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.map { n =>
                if n % 2 == 0 then IOs(n * 2) else n * 2
            }
            val expected = Chunks.init(2, 4, 6, 8, 10)
            assert(IOs.run(result).pure == expected)
        }

        "with an empty chunk" in {
            val chunk  = Chunks.init[Int]
            val result = chunk.map(_ * 2)
            assert(result.pure.isEmpty)
        }
    }

    "filter" - {
        "with a pure predicate" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.filter(_ % 2 == 0)
            assert(result.pure == Chunks.init(2, 4))
        }

        "with a predicate using IOs" in {
            val chunk    = Chunks.init(1, 2, 3, 4, 5)
            val result   = chunk.filter(n => IOs(n % 2 == 0))
            val expected = Chunks.init(2, 4)
            assert(IOs.run(result).pure == expected)
        }

        "with a predicate returning pure values and effectful computations" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.filter { n =>
                if n % 2 == 0 then IOs(true) else false
            }
            val expected = Chunks.init(2, 4)
            assert(IOs.run(result).pure == expected)
        }

        "with a predicate that filters out all elements" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.filter(_ => IOs(false))
            assert(IOs.run(result).pure.isEmpty)
        }
    }

    "foldLeft" - {
        "with a pure function" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.foldLeft(0)(_ + _)
            assert(result.pure == 15)
        }

        "with a function using IOs" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.foldLeft(0)((acc, n) => IOs(acc + n))
            assert(IOs.run(result).pure == 15)
        }

        "with a function returning pure values and effectful computations" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.foldLeft(0) { (acc, n) =>
                if n % 2 == 0 then IOs(acc + n) else acc + n
            }
            assert(IOs.run(result).pure == 15)
        }

        "with an empty chunk" in {
            val chunk  = Chunks.init[Int]
            val result = chunk.foldLeft(0)(_ + _)
            assert(result.pure == 0)
        }
    }

    "toSeq" - {
        "converts a Chunk to an IndexedSeq" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            assert(chunk.toSeq == IndexedSeq(1, 2, 3, 4, 5))
        }

        "converts an empty Chunk to an empty IndexedSeq" in {
            val chunk = Chunks.init[Int]
            assert(chunk.toSeq.isEmpty)
        }
    }

    "mixed" - {
        "chained operations" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5).toIndexed
            val result = chunk.append(6).toIndexed.tail.take(3).dropLeft(1)
            assert(result == Chunks.init(3, 4))
        }

        "empty chunk operations" in {
            val chunk = Chunks.init[Int]
            assert(chunk.append(1).toIndexed.head == 1)
            assert(chunk.toIndexed.tail.isEmpty)
            assert(chunk.take(2).isEmpty)
            assert(chunk.dropLeft(1).isEmpty)
            assert(chunk.slice(0, 1).isEmpty)
        }

        "single element chunk operations" in {
            val chunk = Chunks.init(1)
            assert(chunk.append(2) == Chunks.init(1, 2))
            assert(chunk.toIndexed.tail.isEmpty)
            assert(chunk.take(1) == Chunks.init(1))
            assert(chunk.dropLeft(1).isEmpty)
            assert(chunk.slice(0, 1) == Chunks.init(1))
        }

        "out of bounds indexing" in {
            val chunk = Chunks.init(1, 2, 3)
            assert(chunk.dropLeft(5).isEmpty)
            assert(chunk.take(5) == Chunks.init(1, 2, 3))
            assert(chunk.slice(1, 5) == Chunks.init(2, 3))
            assert(chunk.slice(5, 6).isEmpty)
        }

        "negative indexing" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            assert(chunk.dropLeft(-1) == Chunks.init(1, 2, 3, 4, 5))
            assert(chunk.take(-1).isEmpty)
            assert(chunk.slice(-2, 3) == Chunks.init(1, 2, 3))
            assert(chunk.slice(2, -1).isEmpty)
        }

        "chained append operations" in {
            val chunk = Chunks.init[Int].append(1).append(2).append(3)
            assert(chunk == Chunks.init(1, 2, 3))
        }

        "chained slice operations" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            assert(chunk.slice(1, 4).slice(1, 2) == Chunks.init(3))
        }

        "slice beyond chunk size" in {
            val chunk = Chunks.init(1, 2, 3)
            assert(chunk.slice(1, 10) == Chunks.init(2, 3))
            assert(chunk.slice(5, 10).isEmpty)
        }

        "mapping and filtering an empty chunk" in {
            val chunk = Chunks.init[Int]
            val result = chunk
                .map(_ * 2).pure
                .filter(_ % 2 == 0)
            assert(result.pure.isEmpty)
        }

        "filter and map" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5, 6)
            val result = chunk
                .filter(_ % 2 == 0).pure
                .map(_ + 1).pure
            assert(result == Chunks.init(3, 5, 7))
        }

        "multiple appends followed by head and tail" - {
            "returns the correct elements when calling head and tail repeatedly" in {
                val chunk = Chunks.init[Int]
                    .append(1)
                    .append(2)

                assert(chunk.toIndexed.head == 1)
                assert(chunk.toIndexed.tail.head == 2)
                assert(chunk.toIndexed.tail.tail.isEmpty)
            }

            "returns the correct elements when calling head and tail in a loop" in {
                val chunk = Chunks.init[Int]
                    .append(1)
                    .append(2)
                    .append(3)
                    .append(4)
                    .append(5)

                var current = chunk.toIndexed
                val result  = collection.mutable.ListBuffer[Int]()

                while !current.isEmpty do
                    result += current.head
                    current = current.tail

                assert(result.toList == List(1, 2, 3, 4, 5))
            }

            "handles empty chunks when calling head and tail" in {
                val chunk = Chunks.init[Int]
                    .append(1)
                    .append(2)
                    .toIndexed
                    .tail
                    .tail

                assert(chunk.isEmpty)
                assert(Try(chunk.head).isFailure)
                assert(chunk.tail.isEmpty)
            }
        }
        "handles nested Drop chunks" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
                .dropLeft(2)
                .dropLeft(1)
                .toIndexed

            assert(chunk == Chunks.init(4, 5))
            assert(chunk.tail == Chunks.init(5))
            assert(chunk.tail.tail.isEmpty)
        }

        "chained take, dropLeft, and dropRight operations" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
            assert(chunk.take(7).dropLeft(2).dropRight(2) == Chunks.init(3, 4, 5))
        }

        "empty chunk with take, dropLeft, and dropRight" in {
            val chunk = Chunks.init[Int]
            assert(chunk.take(5).isEmpty)
            assert(chunk.dropLeft(2).isEmpty)
            assert(chunk.dropRight(3).isEmpty)
        }

        "single element chunk with take, dropLeft, and dropRight" in {
            val chunk = Chunks.init(1)
            assert(chunk.take(1) == Chunks.init(1))
            assert(chunk.take(2) == Chunks.init(1))
            assert(chunk.dropLeft(0) == Chunks.init(1))
            assert(chunk.dropLeft(1).isEmpty)
            assert(chunk.dropRight(0) == Chunks.init(1))
            assert(chunk.dropRight(1).isEmpty)
        }
    }

    "flatten" - {
        "flattens a chunk of chunks in original order" in {
            val chunk: Chunk[Chunk[Int]] = Chunks.init(Chunks.init(1, 2), Chunks.init(3, 4, 5), Chunks.init(6, 7, 8, 9))
            assert(chunk.flatten == Chunks.init(1, 2, 3, 4, 5, 6, 7, 8, 9))
        }

        "returns an empty chunk when flattening an empty chunk of chunks" in {
            val chunk = Chunks.init[Chunk[Int]]
            assert(chunk.flatten.isEmpty)
        }

        "returns an empty chunk when flattening a chunk of empty chunks" in {
            val chunk = Chunks.init(Chunks.init[Int], Chunks.init[Int])
            assert(chunk.flatten.isEmpty)
        }

        "preserves the order of elements within each chunk" in {
            val chunk = Chunks.init(Chunks.init(1, 2, 3), Chunks.init(4, 5, 6))
            assert(chunk.flatten == Chunks.init(1, 2, 3, 4, 5, 6))
        }

        "handles a chunk containing a single chunk" in {
            val chunk = Chunks.init(Chunks.init(1, 2, 3))
            assert(chunk.flatten == Chunks.init(1, 2, 3))
        }

        "handles chunks of different sizes" in {
            val chunk = Chunks.init(Chunks.init(1), Chunks.init(2, 3), Chunks.init(4, 5, 6, 7))
            assert(chunk.flatten == Chunks.init(1, 2, 3, 4, 5, 6, 7))
        }

        "appends" in {
            val chunk: Chunk[Chunk[Int]] =
                Chunks.init(Chunks.init[Int].append(1).append(2), Chunks.init(3, 4).append(5), Chunks.init(6, 7).append(8).append(9))
            assert(chunk.flatten == Chunks.init(1, 2, 3, 4, 5, 6, 7, 8, 9))
        }
    }

    "toArray" - {
        "returns an array with the elements of a Chunk.Compact" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            val array = chunk.toArray
            assert(array.toSeq == Seq(1, 2, 3, 4, 5))
        }

        "returns an array with the elements of a Chunk.FromSeq" in {
            val chunk = Chunks.initSeq(IndexedSeq(1, 2, 3))
            val array = chunk.toArray
            assert(array.toSeq == Seq(1, 2, 3))
        }

        "returns an array with the elements of a Chunk.Drop" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5).dropLeft(2)
            val array = chunk.toArray
            assert(array.toSeq == Seq(3, 4, 5))
        }

        "returns an array with the elements of a Chunk.Append" in {
            val chunk = Chunks.init(1, 2, 3).append(4).append(5)
            val array = chunk.toArray
            assert(array.toSeq == Seq(1, 2, 3, 4, 5))
        }

        "returns an empty array for an empty Chunk" in {
            val chunk = Chunks.init[Int]
            val array = chunk.toArray
            assert(array.isEmpty)
        }

        "returns a new array instance for each call" in {
            val chunk  = Chunks.init(1, 2, 3)
            val array1 = chunk.toArray
            val array2 = chunk.toArray
            assert(array1 ne array2)
        }
    }

    "copyTo" - {
        "copies elements to an array" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            val array = new Array[Int](5)
            chunk.copyTo(array, 0)
            assert(array.toSeq == Seq(1, 2, 3, 4, 5))
        }

        "copies elements to a specific position in the array" in {
            val chunk = Chunks.init(1, 2, 3)
            val array = new Array[Int](5)
            chunk.copyTo(array, 2)
            assert(array.toSeq == Seq(0, 0, 1, 2, 3))
        }

        "copies elements from a Chunk.Compact" in {
            val chunk = Chunks.init(1, 2, 3)
            val array = new Array[Int](3)
            chunk.copyTo(array, 0)
            assert(array.toSeq == Seq(1, 2, 3))
        }

        "copies elements from a Chunk.FromSeq" in {
            val chunk = Chunks.initSeq(IndexedSeq(1, 2, 3))
            val array = new Array[Int](3)
            chunk.copyTo(array, 0)
            assert(array.toSeq == Seq(1, 2, 3))
        }

        "copies elements from a Chunk.Drop" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5).dropLeft(2)
            val array = new Array[Int](3)
            chunk.copyTo(array, 0)
            assert(array.toSeq == Seq(3, 4, 5))
        }

        "copies elements from a Chunk.Append" in {
            val chunk = Chunks.init(1, 2, 3).append(4)
            val array = new Array[Int](4)
            chunk.copyTo(array, 0)
            assert(array.toSeq == Seq(1, 2, 3, 4))
        }
    }

    "copyTo with size" - {
        "copies a specified number of elements to an array" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            val array = new Array[Int](3)
            chunk.copyTo(array, 0, 3)
            assert(array.toSeq == Seq(1, 2, 3))
        }

        "copies elements from a specific position and size" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            val array = new Array[Int](2)
            chunk.copyTo(array, 0, 2)
            assert(array.toSeq == Seq(1, 2))
        }

        "copies elements from a Chunk.Compact with size limit" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            val array = new Array[Int](3)
            chunk.copyTo(array, 0, 3)
            assert(array.toSeq == Seq(1, 2, 3))
        }
    }

    "last" - {
        "returns the last element of a non-empty chunk" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            assert(chunk.last == 5)
        }

        "throws NoSuchElementException for an empty chunk" in {
            val chunk = Chunks.init[Int]
            assert(Try(chunk.last).isFailure)
        }

        "returns the last element after appending" in {
            val chunk = Chunks.init(1, 2, 3).append(4)
            assert(chunk.last == 4)
        }

        "returns the correct last element for a Drop chunk with dropRight" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5).dropRight(2)
            assert(chunk.last == 3)
        }

        "throws NoSuchElementException for a Drop chunk with dropRight equal to size" in {
            val chunk = Chunks.init(1, 2, 3).dropRight(3)
            assert(Try(chunk.last).isFailure)
        }

        "returns the correct last element for a Drop chunk with both dropLeft and dropRight" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5).dropLeft(1).dropRight(2)
            assert(chunk.last == 3)
        }
        "one element" in {
            val chunk = Chunks.init(1)
            assert(chunk.last == 1)
        }
    }

    "concat" - {
        "concatenates two non-empty chunks" in {
            val chunk1 = Chunks.init(1, 2, 3)
            val chunk2 = Chunks.init(4, 5, 6)
            val result = chunk1.concat(chunk2)
            assert(result == Chunks.init(1, 2, 3, 4, 5, 6))
        }

        "concatenates a non-empty chunk with an empty chunk" in {
            val chunk1 = Chunks.init(1, 2, 3)
            val chunk2 = Chunks.init[Int]
            val result = chunk1.concat(chunk2)
            assert(result == Chunks.init(1, 2, 3))
        }

        "concatenates an empty chunk with a non-empty chunk" in {
            val chunk1 = Chunks.init[Int]
            val chunk2 = Chunks.init(1, 2, 3)
            val result = chunk1.concat(chunk2)
            assert(result == Chunks.init(1, 2, 3))
        }

        "returns an empty chunk when concatenating two empty chunks" in {
            val chunk1 = Chunks.init[Int]
            val chunk2 = Chunks.init[Int]
            val result = chunk1.concat(chunk2)
            assert(result.isEmpty)
        }

        "handles chunks of different types" in {
            val chunk1 = Chunks.init("a", "b", "c")
            val chunk2 = Chunks.init("d", "e", "f")
            val result = chunk1.concat(chunk2)
            assert(result == Chunks.init("a", "b", "c", "d", "e", "f"))
        }

        "handles multiple concatenations" in {
            val chunk1 = Chunks.init(1, 2)
            val chunk2 = Chunks.init(3, 4)
            val chunk3 = Chunks.init(5, 6)
            val chunk4 = Chunks.init(7, 8)
            val result = chunk1.concat(chunk2).concat(chunk3).concat(chunk4)
            assert(result == Chunks.init(1, 2, 3, 4, 5, 6, 7, 8))
        }

        "concatenates a Chunk.Compact with a Chunk.FromSeq" in {
            val chunk1 = Chunks.init(1, 2, 3)
            val chunk2 = Chunks.initSeq(IndexedSeq(4, 5, 6))
            val result = chunk1.concat(chunk2)
            assert(result == Chunks.init(1, 2, 3, 4, 5, 6))
        }

        "concatenates a Chunk.FromSeq with a Chunk.Compact" in {
            val chunk1 = Chunks.initSeq(IndexedSeq(1, 2, 3))
            val chunk2 = Chunks.init(4, 5, 6)
            val result = chunk1.concat(chunk2)
            assert(result == Chunks.init(1, 2, 3, 4, 5, 6))
        }

        "concatenates a Chunk.Drop with a Chunk.Compact" in {
            val chunk1 = Chunks.init(1, 2, 3, 4, 5).dropLeft(2)
            val chunk2 = Chunks.init(6, 7, 8)
            val result = chunk1.concat(chunk2)
            assert(result == Chunks.init(3, 4, 5, 6, 7, 8))
        }
    }

    "takeWhile" - {
        "returns elements while the predicate is true" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.takeWhile(_ < 4).pure
            assert(result == Chunks.init(1, 2, 3))
        }

        "returns all elements if the predicate is always true" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.takeWhile(_ => true).pure
            assert(result == Chunks.init(1, 2, 3, 4, 5))
        }

        "returns an empty chunk if the predicate is always false" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.takeWhile(_ => false).pure
            assert(result.isEmpty)
        }

        "handles effectful predicates" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.takeWhile(n => IOs(n < 4))
            assert(IOs.run(result).pure == Chunks.init(1, 2, 3))
        }
    }

    "dropWhile" - {
        "drops elements while the predicate is true" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.dropWhile(_ < 3).pure
            assert(result == Chunks.init(3, 4, 5))
        }

        "drops all elements if the predicate is always true" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.dropWhile(_ => true).pure
            assert(result.isEmpty)
        }

        "drops no elements if the predicate is always false" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.dropWhile(_ => false).pure
            assert(result == Chunks.init(1, 2, 3, 4, 5))
        }

        "handles effectful predicates" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.dropWhile(n => IOs(n < 3))
            assert(IOs.run(result).pure == Chunks.init(3, 4, 5))
        }
    }

    "changes" - {
        "returns a chunk with consecutive duplicates removed" in {
            val chunk = Chunks.init(1, 1, 2, 3, 3, 3, 4, 4, 5)
            assert(chunk.changes == Chunks.init(1, 2, 3, 4, 5))
        }

        "returns the original chunk if there are no consecutive duplicates" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            assert(chunk.changes == Chunks.init(1, 2, 3, 4, 5))
        }

        "returns an empty chunk for an empty input chunk" in {
            val chunk = Chunks.init[Int]
            assert(chunk.changes.isEmpty)
        }

        "handles a single element chunk" in {
            val chunk = Chunks.init(1)
            assert(chunk.changes == Chunks.init(1))
        }

        "handles a chunk with all duplicate elements" in {
            val chunk = Chunks.init(1, 1, 1, 1, 1)
            assert(chunk.changes == Chunks.init(1))
        }

        "with initial values" in {
            val chunk = Chunks.init(1, 1, 1, 1, 1)
            assert(chunk.changes(0) == Chunks.init(1))
            assert(chunk.changes(1) == Chunks.init[Int])
        }
    }

    "collect" - {
        "with a partial function" in {
            val chunk  = Chunks.init(1, 2, 3, 4, 5)
            val result = chunk.collect { case x if x % 2 == 0 => x * 2 }.pure
            assert(result == Chunks.init(4, 8))
        }

        "with an empty chunk" in {
            val chunk  = Chunks.init[Int]
            val result = chunk.collect { case x if x % 2 == 0 => x * 2 }.pure
            assert(result.isEmpty)
        }
    }

    "collectUnit" - {
        "with a partial function" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            var sum   = 0
            IOs.run(chunk.collectUnit { case x if x % 2 == 0 => IOs(sum += x) })
            assert(sum == 6)
        }

        "with an empty chunk" in {
            val chunk = Chunks.init[Int]
            var sum   = 0
            IOs.run(chunk.collectUnit { case x if x % 2 == 0 => IOs(sum += x) })
            assert(sum == 0)
        }
    }

    "foreach" - {
        "with a pure function" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            var sum   = 0
            chunk.foreach(x => sum += x).pure
            assert(sum == 15)
        }

        "with a function using IOs" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5)
            var sum   = 0
            IOs.run(chunk.foreach(x => IOs(sum += x)))
            assert(sum == 15)
        }

        "with an empty chunk" in {
            val chunk = Chunks.init[Int]
            var sum   = 0
            chunk.foreach(x => sum += x).pure
            assert(sum == 0)
        }
    }

    "hashCode and equals" - {
        "equal chunks have the same hashCode" in {
            val chunk1 = Chunks.init(1, 2, 3)
            val chunk2 = Chunks.init(1, 2, 3)
            assert(chunk1.hashCode() == chunk2.hashCode())
        }

        "equal chunks are equal" in {
            val chunk1 = Chunks.init(1, 2, 3)
            val chunk2 = Chunks.init(1, 2, 3)
            assert(chunk1 == chunk2)
        }

        "different chunks have different hashCodes" in {
            val chunk1 = Chunks.init(1, 2, 3)
            val chunk2 = Chunks.init(3, 2, 1)
            assert(chunk1.hashCode() != chunk2.hashCode())
        }

        "different chunks are not equal" in {
            val chunk1 = Chunks.init(1, 2, 3)
            val chunk2 = Chunks.init(3, 2, 1)
            assert(chunk1 != chunk2)
        }

        "empty chunks have the same hashCode" in {
            val chunk1 = Chunks.init[Int]
            val chunk2 = Chunks.init[Int]
            assert(chunk1.hashCode() == chunk2.hashCode())
        }

        "empty chunks are equal" in {
            val chunk1 = Chunks.init[Int]
            val chunk2 = Chunks.init[Int]
            assert(chunk1 == chunk2)
        }
    }

    "Chunk.empty" in {
        assert(Chunk.empty[Int].toSeq.isEmpty)
    }

    "Chunks.collect" - {
        "collects elements from a chunk of effectful computations" in {
            val chunk  = Chunks.init(IOs(1), IOs(2), IOs(3))
            val result = Chunks.collect(chunk)
            assert(IOs.run(result).pure == Chunks.init(1, 2, 3))
        }

        "collects elements from a chunk of effectful computations with different effect types" in {
            val chunk  = Chunks.init(IOs(1), Options.get(Some(2)), Options.get(Some(3)))
            val result = Chunks.collect(chunk)
            assert(IOs.run(Options.run(result)).pure == Some(Chunks.init(1, 2, 3)))
        }
    }

    "toString" - {
        "prints the elements of a Chunk.Compact" in {
            val chunk = Chunks.init[Int].append(1).append(2).append(3).toIndexed
            assert(chunk.toString == "Chunk.Indexed(1, 2, 3)")
        }

        "prints the elements of a Chunk.FromSeq" in {
            val chunk = Chunks.initSeq(IndexedSeq(1, 2, 3))
            assert(chunk.toString == "Chunk.Indexed(1, 2, 3)")
        }

        "prints the elements of a Chunk.Drop" in {
            val chunk = Chunks.init(1, 2, 3, 4, 5).dropLeft(2)
            assert(chunk.toString == "Chunk(3, 4, 5)")
        }

        "prints the elements of a Chunk.Append" in {
            val chunk = Chunks.init(1, 2, 3).append(4)
            assert(chunk.toString == "Chunk(1, 2, 3, 4)")
        }
    }

end chunksTest
