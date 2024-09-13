package kyo

import scala.util.Try

class ChunkTest extends Test:

    "Chunk.from" - {
        "from Array" - {
            "creates a Chunk.Indexed from a non-empty Array" in {
                val array = Array("a", "b", "c")
                val chunk = Chunk.from(array)
                assert(chunk.isInstanceOf[Chunk.Indexed[String]])
                assert(chunk.toSeq == Seq("a", "b", "c"))
            }

            "creates an empty Chunk from an empty Array" in {
                val array = Array.empty[String]
                val chunk = Chunk.from(array)
                assert(chunk.isEmpty)
            }

            "creates a new copy of the Array" in {
                val array = Array("a", "b", "c")
                val chunk = Chunk.from(array)
                array(0) = "x"
                assert(chunk(0) == "a")
            }
        }

        "from Seq" - {
            "creates a Chunk.Indexed from a non-empty Seq" in {
                val seq   = Seq("a", "b", "c")
                val chunk = Chunk.from(seq)
                assert(chunk.isInstanceOf[Chunk.Indexed[String]])
                assert(chunk.toSeq == Seq("a", "b", "c"))
            }

            "creates an empty Chunk from an empty Seq" in {
                val seq   = Seq.empty[String]
                val chunk = Chunk.from(seq)
                assert(chunk.isEmpty)
            }

            "handles different Seq types" - {
                "List" in {
                    val list  = List(1, 2, 3)
                    val chunk = Chunk.from(list)
                    assert(chunk.toSeq == Seq(1, 2, 3))
                }

                "Vector" in {
                    val vector = Vector(1, 2, 3)
                    val chunk  = Chunk.from(vector)
                    assert(chunk.toSeq == Seq(1, 2, 3))
                }
            }

            "returns the same instance for Chunk.Indexed input" in {
                val original = Chunk(1, 2, 3)
                val result   = Chunk.from(original)
                assert(result eq original)
            }

            "creates a Chunk.FromSeq for non-Chunk IndexedSeq input" in {
                val vector = Vector(1, 2, 3)
                val chunk  = Chunk.from(vector)
                assert(chunk.isInstanceOf[Chunk.internal.FromSeq[Int]])
            }

            "creates a Chunk.Compact for non-IndexedSeq input" in {
                val list  = List(1, 2, 3)
                val chunk = Chunk.from(list)
                assert(chunk.isInstanceOf[Chunk.internal.Compact[Int]])
            }
        }
    }

    "append" - {
        "appends a value to an empty chunk" in {
            val chunk = Chunk.empty[Int].append(1)
            assert(chunk == Chunk(1))
        }

        "appends a value to a non-empty chunk" in {
            val chunk = Chunk(1, 2, 3).append(4)
            assert(chunk == Chunk(1, 2, 3, 4))
        }
    }

    "head" - {
        "returns the first element of a non-empty chunk" in {
            val chunk = Chunk(1, 2, 3).toIndexed
            assert(chunk.head == 1)
        }

        "throws NoSuchElementException for an empty chunk" in {
            val chunk = Chunk.empty[Int].toIndexed
            assert(Try(chunk.head).isFailure)
        }
    }

    "tail" - {
        "returns the tail of a non-empty chunk" in {
            val chunk = Chunk(1, 2, 3).toIndexed
            assert(chunk.tail == Chunk(2, 3))
        }

        "returns an empty chunk for a chunk with a single element" in {
            val chunk = Chunk(1).toIndexed
            assert(chunk.tail.isEmpty)
        }

        "returns an empty chunk for an empty chunk" in {
            val chunk = Chunk.empty[Int].toIndexed
            assert(chunk.tail.isEmpty)
        }

        "returns the correct tail for a Drop chunk" in {
            val chunk = Chunk(1, 2, 3, 4, 5).dropLeft(2).toIndexed
            assert(chunk.tail == Chunk(4, 5))
        }

        "of appends" in {
            val chunk = Chunk.empty[Int].append(1).append(2).append(3).toIndexed
            assert(chunk.tail == Chunk(2, 3))
            assert(chunk.tail.tail == Chunk(3))
        }
    }

    "apply" - {
        "returns correct elements for Chunk.Compact" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            assert(chunk(0) == 1)
            assert(chunk(2) == 3)
            assert(chunk(4) == 5)
        }

        "returns correct elements for Chunk.FromSeq" in {
            val chunk = Chunk.from(Vector(1, 2, 3, 4, 5))
            assert(chunk(0) == 1)
            assert(chunk(2) == 3)
            assert(chunk(4) == 5)
        }

        "returns correct elements for Chunk.Drop" in {
            val chunk = Chunk(1, 2, 3, 4, 5).dropLeft(2)
            assert(chunk(0) == 3)
            assert(chunk(1) == 4)
            assert(chunk(2) == 5)
        }

        "returns correct elements for Chunk.Append" in {
            val chunk = Chunk(1, 2, 3).append(4).append(5)
            assert(chunk(0) == 1)
            assert(chunk(3) == 4)
            assert(chunk(4) == 5)
        }

        "throws IndexOutOfBoundsException for negative index" in {
            val chunk = Chunk(1, 2, 3)
            assertThrows[IndexOutOfBoundsException] {
                chunk(-1)
            }
        }

        "throws IndexOutOfBoundsException for index >= size" in {
            val chunk = Chunk(1, 2, 3)
            assertThrows[IndexOutOfBoundsException] {
                chunk(3)
            }
        }

        "works with nested Drop chunks" in {
            val chunk = Chunk(1, 2, 3, 4, 5).dropLeft(1).dropLeft(1)
            assert(chunk(0) == 3)
            assert(chunk(1) == 4)
            assert(chunk(2) == 5)
        }
    }

    "take" - {
        "returns the first n elements of a chunk" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            assert(chunk.take(3) == Chunk(1, 2, 3))
        }

        "returns the entire chunk if n is greater than the chunk size" in {
            val chunk = Chunk(1, 2, 3)
            assert(chunk.take(5) == Chunk(1, 2, 3))
        }

        "returns an empty chunk if n is zero or negative" in {
            val chunk = Chunk(1, 2, 3)
            assert(chunk.take(0).isEmpty)
            assert(chunk.take(-1).isEmpty)
        }
    }

    "dropLeft" - {
        "drops the first n elements of a chunk" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            assert(chunk.dropLeft(2) == Chunk(3, 4, 5))
        }

        "returns an empty chunk if n is greater than or equal to the chunk size" in {
            val chunk = Chunk(1, 2, 3)
            assert(chunk.dropLeft(3).isEmpty)
            assert(chunk.dropLeft(4).isEmpty)
        }

        "returns the entire chunk if n is zero or negative" in {
            val chunk = Chunk(1, 2, 3)
            assert(chunk.dropLeft(0) == Chunk(1, 2, 3))
            assert(chunk.dropLeft(-1) == Chunk(1, 2, 3))
        }
    }

    "dropRight" - {
        "drops the last n elements of a chunk" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            assert(chunk.dropRight(2) == Chunk(1, 2, 3))
        }

        "returns an empty chunk if n is greater than or equal to the chunk size" in {
            val chunk = Chunk(1, 2, 3)
            assert(chunk.dropRight(3).isEmpty)
            assert(chunk.dropRight(4).isEmpty)
        }

        "returns the entire chunk if n is zero or negative" in {
            val chunk = Chunk(1, 2, 3)
            assert(chunk.dropRight(0) == Chunk(1, 2, 3))
            assert(chunk.dropRight(-1) == Chunk(1, 2, 3))
        }
    }

    "slice" - {
        "returns a slice of a chunk" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            assert(chunk.slice(1, 4) == Chunk(2, 3, 4))
        }

        "returns an empty chunk if from is greater than or equal to until" in {
            val chunk = Chunk(1, 2, 3)
            assert(chunk.slice(2, 2).isEmpty)
            assert(chunk.slice(3, 2).isEmpty)
        }

        "returns an empty chunk if the chunk is empty" in {
            val chunk = Chunk.empty[Int]
            assert(chunk.slice(0, 1).isEmpty)
        }

        "returns the full chunk if `from` is negative and `until` is greater than the size" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            assert(chunk.slice(-1, 6) == Chunk(1, 2, 3, 4, 5))
        }

        "returns the full chunk if `from` is 0 and `until` is greater than the size" in {
            val chunk = Chunk(1, 2, 3)
            assert(chunk.slice(0, 5) == Chunk(1, 2, 3))
        }

        "returns the correct slice when multiple slice operations are chained" in {
            val chunk  = Chunk(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
            val result = chunk.slice(2, 8).slice(1, 5).slice(1, 3)
            assert(result == Chunk(5, 6))
        }
    }

    "map" - {
        "with a pure function" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.map(_ * 2)
            assert(result == Chunk(2, 4, 6, 8, 10))
        }
        "with an empty chunk" in {
            val chunk  = Chunk.empty[Int]
            val result = chunk.map(_ * 2)
            assert(result.isEmpty)
        }
    }

    "filter" - {
        "with a pure predicate" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.filter(_ % 2 == 0)
            assert(result == Chunk(2, 4))
        }

        "with a predicate that filters out all elements" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.filter(_ => false)
            assert(result.isEmpty)
        }
    }

    "foldLeft" - {
        "with a pure function" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.foldLeft(0)(_ + _)
            assert(result == 15)
        }

        "with an empty chunk" in {
            val chunk  = Chunk.empty[Int]
            val result = chunk.foldLeft(0)(_ + _)
            assert(result == 0)
        }
    }

    "toSeq" - {
        "converts a Chunk to an IndexedSeq" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            assert(chunk.toSeq == Chunk(1, 2, 3, 4, 5))
        }

        "converts an empty Chunk to an empty IndexedSeq" in {
            val chunk = Chunk.empty[Int]
            assert(chunk.toSeq.isEmpty)
        }
    }

    "mixed" - {
        "chained operations" in {
            val chunk  = Chunk(1, 2, 3, 4, 5).toIndexed
            val result = chunk.append(6).toIndexed.tail.take(3).dropLeft(1)
            assert(result == Chunk(3, 4))
        }

        "empty chunk operations" in {
            val chunk = Chunk.empty[Int]
            assert(chunk.append(1).toIndexed.head == 1)
            assert(chunk.toIndexed.tail.isEmpty)
            assert(chunk.take(2).isEmpty)
            assert(chunk.dropLeft(1).isEmpty)
            assert(chunk.slice(0, 1).isEmpty)
        }

        "single element chunk operations" in {
            val chunk = Chunk(1)
            assert(chunk.append(2) == Chunk(1, 2))
            assert(chunk.toIndexed.tail.isEmpty)
            assert(chunk.take(1) == Chunk(1))
            assert(chunk.dropLeft(1).isEmpty)
            assert(chunk.slice(0, 1) == Chunk(1))
        }

        "out of bounds indexing" in {
            val chunk = Chunk(1, 2, 3)
            assert(chunk.dropLeft(5).isEmpty)
            assert(chunk.take(5) == Chunk(1, 2, 3))
            assert(chunk.slice(1, 5) == Chunk(2, 3))
            assert(chunk.slice(5, 6).isEmpty)
        }

        "negative indexing" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            assert(chunk.dropLeft(-1) == Chunk(1, 2, 3, 4, 5))
            assert(chunk.take(-1).isEmpty)
            assert(chunk.slice(-2, 3) == Chunk(1, 2, 3))
            assert(chunk.slice(2, -1).isEmpty)
        }

        "chained append operations" in {
            val chunk = Chunk.empty[Int].append(1).append(2).append(3)
            assert(chunk == Chunk(1, 2, 3))
        }

        "chained slice operations" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            assert(chunk.slice(1, 4).slice(1, 2) == Chunk(3))
        }

        "slice beyond chunk size" in {
            val chunk = Chunk(1, 2, 3)
            assert(chunk.slice(1, 10) == Chunk(2, 3))
            assert(chunk.slice(5, 10).isEmpty)
        }

        "mapping and filtering an empty chunk" in {
            val chunk = Chunk.empty[Int]
            val result = chunk
                .map(_ * 2)
                .filter(_ % 2 == 0)
            assert(result.isEmpty)
        }

        "filter and map" in {
            val chunk = Chunk(1, 2, 3, 4, 5, 6)
            val result = chunk
                .filter(_ % 2 == 0)
                .map(_ + 1)
            assert(result == Chunk(3, 5, 7))
        }

        "multiple appends followed by head and tail" - {
            "returns the correct elements when calling head and tail repeatedly" in {
                val chunk = Chunk.empty[Int]
                    .append(1)
                    .append(2)

                assert(chunk.toIndexed.head == 1)
                assert(chunk.toIndexed.tail.head == 2)
                assert(chunk.toIndexed.tail.tail.isEmpty)
            }

            "returns the correct elements when calling head and tail in a loop" in {
                val chunk = Chunk.empty[Int]
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
                val chunk = Chunk.empty[Int]
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
            val chunk = Chunk(1, 2, 3, 4, 5)
                .dropLeft(2)
                .dropLeft(1)
                .toIndexed

            assert(chunk == Chunk(4, 5))
            assert(chunk.tail == Chunk(5))
            assert(chunk.tail.tail.isEmpty)
        }

        "chained take, dropLeft, and dropRight operations" in {
            val chunk = Chunk(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
            assert(chunk.take(7).dropLeft(2).dropRight(2) == Chunk(3, 4, 5))
        }

        "empty chunk with take, dropLeft, and dropRight" in {
            val chunk = Chunk.empty[Int]
            assert(chunk.take(5).isEmpty)
            assert(chunk.dropLeft(2).isEmpty)
            assert(chunk.dropRight(3).isEmpty)
        }

        "single element chunk with take, dropLeft, and dropRight" in {
            val chunk = Chunk(1)
            assert(chunk.take(1) == Chunk(1))
            assert(chunk.take(2) == Chunk(1))
            assert(chunk.dropLeft(0) == Chunk(1))
            assert(chunk.dropLeft(1).isEmpty)
            assert(chunk.dropRight(0) == Chunk(1))
            assert(chunk.dropRight(1).isEmpty)
        }
    }

    "flattenChunk" - {
        "flattens a chunk of chunks in original order" in {
            val chunk: Chunk[Chunk[Int]] = Chunk(Chunk(1, 2), Chunk(3, 4, 5), Chunk(6, 7, 8, 9))
            assert(chunk.flattenChunk == Chunk(1, 2, 3, 4, 5, 6, 7, 8, 9))
        }

        "returns an empty chunk when flattening an empty chunk of chunks" in {
            val chunk = Chunk.empty[Chunk[Int]]
            assert(chunk.flattenChunk.isEmpty)
        }

        "returns an empty chunk when flattening a chunk of empty chunks" in {
            val chunk = Chunk(Chunk.empty[Int], Chunk.empty[Int])
            assert(chunk.flattenChunk.isEmpty)
        }

        "preserves the order of elements within each chunk" in {
            val chunk = Chunk(Chunk(1, 2, 3), Chunk(4, 5, 6))
            assert(chunk.flattenChunk == Chunk(1, 2, 3, 4, 5, 6))
        }

        "handles a chunk containing a single chunk" in {
            val chunk = Chunk(Chunk(1, 2, 3))
            assert(chunk.flattenChunk == Chunk(1, 2, 3))
        }

        "handles chunks of different sizes" in {
            val chunk = Chunk(Chunk(1), Chunk(2, 3), Chunk(4, 5, 6, 7))
            assert(chunk.flattenChunk == Chunk(1, 2, 3, 4, 5, 6, 7))
        }

        "appends" in {
            val chunk: Chunk[Chunk[Int]] =
                Chunk(Chunk.empty[Int].append(1).append(2), Chunk(3, 4).append(5), Chunk(6, 7).append(8).append(9))
            assert(chunk.flattenChunk == Chunk(1, 2, 3, 4, 5, 6, 7, 8, 9))
        }
    }

    "toArray" - {
        "returns an array with the elements of a Chunk.Compact" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            val array = chunk.toArray
            assert(array.toSeq == Seq(1, 2, 3, 4, 5))
        }

        "returns an array with the elements of a Chunk.FromSeq" in {
            val chunk = Chunk.from(IndexedSeq(1, 2, 3))
            val array = chunk.toArray
            assert(array.toSeq == Seq(1, 2, 3))
        }

        "returns an array with the elements of a Chunk.Drop" in {
            val chunk = Chunk(1, 2, 3, 4, 5).dropLeft(2)
            val array = chunk.toArray
            assert(array.toSeq == Seq(3, 4, 5))
        }

        "returns an array with the elements of a Chunk.Append" in {
            val chunk = Chunk(1, 2, 3).append(4).append(5)
            val array = chunk.toArray
            assert(array.toSeq == Seq(1, 2, 3, 4, 5))
        }

        "returns an empty array for an empty Chunk" in {
            val chunk = Chunk.empty[Int]
            val array = chunk.toArray
            assert(array.isEmpty)
        }

        "returns a new array instance for each call" in {
            val chunk  = Chunk(1, 2, 3)
            val array1 = chunk.toArray
            val array2 = chunk.toArray
            assert(array1 ne array2)
        }
    }

    "copyTo" - {
        "copies elements to an array" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            val array = new Array[Int](5)
            chunk.copyTo(array, 0)
            assert(array.toSeq == Seq(1, 2, 3, 4, 5))
        }

        "copies elements to a specific position in the array" in {
            val chunk = Chunk(1, 2, 3)
            val array = new Array[Int](5)
            chunk.copyTo(array, 2)
            assert(array.toSeq == Seq(0, 0, 1, 2, 3))
        }

        "copies elements from a Chunk.Compact" in {
            val chunk = Chunk(1, 2, 3)
            val array = new Array[Int](3)
            chunk.copyTo(array, 0)
            assert(array.toSeq == Seq(1, 2, 3))
        }

        "copies elements from a Chunk.FromSeq" in {
            val chunk = Chunk.from(IndexedSeq(1, 2, 3))
            val array = new Array[Int](3)
            chunk.copyTo(array, 0)
            assert(array.toSeq == Seq(1, 2, 3))
        }

        "copies elements from a Chunk.Drop" in {
            val chunk = Chunk(1, 2, 3, 4, 5).dropLeft(2)
            val array = new Array[Int](3)
            chunk.copyTo(array, 0)
            assert(array.toSeq == Seq(3, 4, 5))
        }

        "copies elements from a Chunk.Append" in {
            val chunk = Chunk(1, 2, 3).append(4)
            val array = new Array[Int](4)
            chunk.copyTo(array, 0)
            assert(array.toSeq == Seq(1, 2, 3, 4))
        }
    }

    "copyTo with size" - {
        "copies a specified number of elements to an array" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            val array = new Array[Int](3)
            chunk.copyTo(array, 0, 3)
            assert(array.toSeq == Seq(1, 2, 3))
        }

        "copies elements from a specific position and size" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            val array = new Array[Int](2)
            chunk.copyTo(array, 0, 2)
            assert(array.toSeq == Seq(1, 2))
        }

        "copies elements from a Chunk.Compact with size limit" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            val array = new Array[Int](3)
            chunk.copyTo(array, 0, 3)
            assert(array.toSeq == Seq(1, 2, 3))
        }
    }

    "last" - {
        "returns the last element of a non-empty chunk" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            assert(chunk.last == 5)
        }

        "throws NoSuchElementException for an empty chunk" in {
            val chunk = Chunk.empty[Int]
            assert(Try(chunk.last).isFailure)
        }

        "returns the last element after appending" in {
            val chunk = Chunk(1, 2, 3).append(4)
            assert(chunk.last == 4)
        }

        "returns the correct last element for a Drop chunk with dropRight" in {
            val chunk = Chunk(1, 2, 3, 4, 5).dropRight(2)
            assert(chunk.last == 3)
        }

        "throws NoSuchElementException for a Drop chunk with dropRight equal to size" in {
            val chunk = Chunk(1, 2, 3).dropRight(3)
            assert(Try(chunk.last).isFailure)
        }

        "returns the correct last element for a Drop chunk with both dropLeft and dropRight" in {
            val chunk = Chunk(1, 2, 3, 4, 5).dropLeft(1).dropRight(2)
            assert(chunk.last == 3)
        }
        "one element" in {
            val chunk = Chunk(1)
            assert(chunk.last == 1)
        }
    }

    "concat" - {
        "concatenates two non-empty chunks" in {
            val chunk1 = Chunk(1, 2, 3)
            val chunk2 = Chunk(4, 5, 6)
            val result = chunk1.concat(chunk2)
            assert(result == Chunk(1, 2, 3, 4, 5, 6))
        }

        "concatenates a non-empty chunk with an empty chunk" in {
            val chunk1 = Chunk(1, 2, 3)
            val chunk2 = Chunk.empty[Int]
            val result = chunk1.concat(chunk2)
            assert(result == Chunk(1, 2, 3))
        }

        "concatenates an empty chunk with a non-empty chunk" in {
            val chunk1 = Chunk.empty[Int]
            val chunk2 = Chunk(1, 2, 3)
            val result = chunk1.concat(chunk2)
            assert(result == Chunk(1, 2, 3))
        }

        "returns an empty chunk when concatenating two empty chunks" in {
            val chunk1 = Chunk.empty[Int]
            val chunk2 = Chunk.empty[Int]
            val result = chunk1.concat(chunk2)
            assert(result.isEmpty)
        }

        "handles chunks of different types" in {
            val chunk1 = Chunk("a", "b", "c")
            val chunk2 = Chunk("d", "e", "f")
            val result = chunk1.concat(chunk2)
            assert(result == Chunk("a", "b", "c", "d", "e", "f"))
        }

        "handles multiple concatenations" in {
            val chunk1 = Chunk(1, 2)
            val chunk2 = Chunk(3, 4)
            val chunk3 = Chunk(5, 6)
            val chunk4 = Chunk(7, 8)
            val result = chunk1.concat(chunk2).concat(chunk3).concat(chunk4)
            assert(result == Chunk(1, 2, 3, 4, 5, 6, 7, 8))
        }

        "concatenates a Chunk.Compact with a Chunk.FromSeq" in {
            val chunk1 = Chunk(1, 2, 3)
            val chunk2 = Chunk.from(IndexedSeq(4, 5, 6))
            val result = chunk1.concat(chunk2)
            assert(result == Chunk(1, 2, 3, 4, 5, 6))
        }

        "concatenates a Chunk.FromSeq with a Chunk.Compact" in {
            val chunk1 = Chunk.from(IndexedSeq(1, 2, 3))
            val chunk2 = Chunk(4, 5, 6)
            val result = chunk1.concat(chunk2)
            assert(result == Chunk(1, 2, 3, 4, 5, 6))
        }

        "concatenates a Chunk.Drop with a Chunk.Compact" in {
            val chunk1 = Chunk(1, 2, 3, 4, 5).dropLeft(2)
            val chunk2 = Chunk(6, 7, 8)
            val result = chunk1.concat(chunk2)
            assert(result == Chunk(3, 4, 5, 6, 7, 8))
        }
    }

    "takeWhile" - {
        "returns elements while the predicate is true" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.takeWhile(_ < 4)
            assert(result == Chunk(1, 2, 3))
        }

        "returns all elements if the predicate is always true" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.takeWhile(_ => true)
            assert(result == Chunk(1, 2, 3, 4, 5))
        }

        "returns an empty chunk if the predicate is always false" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.takeWhile(_ => false)
            assert(result.isEmpty)
        }
    }

    "dropWhile" - {
        "drops elements while the predicate is true" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.dropWhile(_ < 3)
            assert(result == Chunk(3, 4, 5))
        }

        "drops all elements if the predicate is always true" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.dropWhile(_ => true)
            assert(result.isEmpty)
        }

        "drops no elements if the predicate is always false" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.dropWhile(_ => false)
            assert(result == Chunk(1, 2, 3, 4, 5))
        }

        "handles a single element chunk" in {
            val chunk = Chunk(1)
            assert(chunk.changes == Chunk(1))
        }

        "handles a chunk with all duplicate elements" in {
            val chunk = Chunk(1, 1, 1, 1, 1)
            assert(chunk.changes == Chunk(1))
        }

        "with initial values" in {
            val chunk = Chunk(1, 1, 1, 1, 1)
            assert(chunk.changes(Maybe(0)) == Chunk(1))
            assert(chunk.changes(Maybe(1)) == Chunk.empty[Int])
        }
    }

    "collect" - {
        "with a partial function" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.collect { case x if x % 2 == 0 => x * 2 }
            assert(result == Chunk(4, 8))
        }

        "with an empty chunk" in {
            val chunk  = Chunk.empty[Int]
            val result = chunk.collect { case x if x % 2 == 0 => x * 2 }
            assert(result.isEmpty)
        }
    }

    "foreach" - {
        "with a pure function" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            var sum   = 0
            chunk.foreach(x => sum += x)
            assert(sum == 15)
        }

        "with an empty chunk" in {
            val chunk = Chunk.empty[Int]
            var sum   = 0
            chunk.foreach(x => sum += x)
            assert(sum == 0)
        }
    }

    "hashCode and equals" - {
        "equal chunks have the same hashCode" in {
            val chunk1 = Chunk(1, 2, 3)
            val chunk2 = Chunk(1, 2, 3)
            assert(chunk1.hashCode() == chunk2.hashCode())
        }

        "equal chunks are equal" in {
            val chunk1 = Chunk(1, 2, 3)
            val chunk2 = Chunk(1, 2, 3)
            assert(chunk1 == chunk2)
        }

        "different chunks have different hashCodes" in {
            val chunk1 = Chunk(1, 2, 3)
            val chunk2 = Chunk(3, 2, 1)
            assert(chunk1.hashCode() != chunk2.hashCode())
        }

        "different chunks are not equal" in {
            val chunk1 = Chunk(1, 2, 3)
            val chunk2 = Chunk(3, 2, 1)
            assert(chunk1 != chunk2)
        }

        "empty chunks have the same hashCode" in {
            val chunk1 = Chunk.empty[Int]
            val chunk2 = Chunk.empty[Int]
            assert(chunk1.hashCode() == chunk2.hashCode())
        }

        "empty chunks are equal" in {
            val chunk1 = Chunk.empty[Int]
            val chunk2 = Chunk.empty[Int]
            assert(chunk1 == chunk2)
        }
    }

    "Chunk.empty" in {
        assert(Chunk.empty[Int].toSeq.isEmpty)
    }

    "toString" - {
        "prints the elements of a Chunk.Compact" in {
            val chunk = Chunk.empty[Int].append(1).append(2).append(3).toIndexed
            assert(chunk.toString == "Chunk.Indexed(1, 2, 3)")
        }

        "prints the elements of a Chunk.FromSeq" in {
            val chunk = Chunk.from(IndexedSeq(1, 2, 3))
            assert(chunk.toString == "Chunk.Indexed(1, 2, 3)")
        }

        "prints the elements of a Chunk.Drop" in {
            val chunk = Chunk(1, 2, 3, 4, 5).dropLeft(2)
            assert(chunk.toString == "Chunk(3, 4, 5)")
        }

        "prints the elements of a Chunk.Append" in {
            val chunk = Chunk(1, 2, 3).append(4)
            assert(chunk.toString == "Chunk(1, 2, 3, 4)")
        }
    }
end ChunkTest
