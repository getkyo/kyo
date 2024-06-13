package kyo2

import scala.util.Try

class ChunkTest extends Test:

    "append" - {
        "appends a value to an empty chunk" in {
            val chunk = kyo2.Chunk.empty[Int].append(1)
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
            val chunk = kyo2.Chunk.empty[Int].toIndexed
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
            val chunk = kyo2.Chunk.empty[Int].toIndexed
            assert(chunk.tail.isEmpty)
        }

        "returns the correct tail for a Drop chunk" in {
            val chunk = Chunk(1, 2, 3, 4, 5).dropLeft(2).toIndexed
            assert(chunk.tail == Chunk(4, 5))
        }

        "of appends" in {
            val chunk = kyo2.Chunk.empty[Int].append(1).append(2).append(3).toIndexed
            assert(chunk.tail == Chunk(2, 3))
            assert(chunk.tail.tail == Chunk(3))
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
            val chunk = kyo2.Chunk.empty[Int]
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
            assert(result.eval == Chunk(2, 4, 6, 8, 10))
        }
        "with a function using Env and Abort" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            val result = kyo2.Env.run(3) {
                kyo2.Abort.run[String] {
                    chunk.map { n =>
                        kyo2.Env.get[Int].map { factor =>
                            if n % factor == 0 then n * factor
                            else kyo2.Abort.fail(s"$n is not divisible by $factor")
                        }
                    }
                }
            }
            assert(result.eval == Left("1 is not divisible by 3"))
        }
        "with a function using Var and Abort" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            val result = kyo2.Var.run(1) {
                kyo2.Abort.run[String] {
                    chunk.map { n =>
                        kyo2.Var.get[Int].map { multiplier =>
                            if n % 2 == 0 then kyo2.Var.set(multiplier * n).andThen(n * multiplier)
                            else kyo2.Abort.fail("Odd number encountered")
                        }
                    }
                }
            }
            assert(result.eval == Left("Odd number encountered"))
        }
        "with an empty chunk" in {
            val chunk  = kyo2.Chunk.empty[Int]
            val result = chunk.map(_ * 2)
            assert(result.eval.isEmpty)
        }
    }

    "filter" - {
        "with a pure predicate" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.filter(_ % 2 == 0)
            assert(result.eval == Chunk(2, 4))
        }

        "with a predicate using Env and Var" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            val result = kyo2.Var.run(0) {
                kyo2.Env.run(2) {
                    chunk.filter { n =>
                        kyo2.Var.update[Int](_ + 1).andThen {
                            kyo2.Env.get[Int].map(_ == n)
                        }
                    }
                }
            }
            assert(result.eval == Chunk(2))
        }

        "with a predicate using Abort and Var" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            val result = kyo2.Var.run(true) {
                kyo2.Abort.run[Unit] {
                    chunk.filter { n =>
                        kyo2.Var.get[Boolean].map { b =>
                            if b then
                                if n % 2 == 0 then kyo2.Var.set(false).andThen(true)
                                else false
                            else kyo2.Abort.fail(())
                        }
                    }
                }
            }
            assert(result.eval == Left(()))
        }

        "with a predicate that filters out all elements" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.filter(_ => false)
            assert(result.eval.isEmpty)
        }
    }

    "foldLeft" - {
        "with a pure function" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.foldLeft(0)(_ + _)
            assert(result.eval == 15)
        }

        "with a function using Env and Abort" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            val result = kyo2.Env.run(10) {
                kyo2.Abort.run[String] {
                    chunk.foldLeft(0) { (acc, n) =>
                        kyo2.Env.get[Int].map { max =>
                            if acc + n <= max then acc + n
                            else kyo2.Abort.fail(s"Sum exceeded max value of $max")
                        }
                    }
                }
            }
            assert(result.eval == Left("Sum exceeded max value of 10"))
        }

        "with a function using Var and Env" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            val result = kyo2.Var.run(1) {
                kyo2.Env.run("*") {
                    chunk.foldLeft(1) { (acc, n) =>
                        kyo2.Var.get[Int].map { multiplier =>
                            kyo2.Env.get[String].map { op =>
                                if op == "*" then kyo2.Var.set(multiplier * n).andThen(acc * n)
                                else if op == "+" then kyo2.Var.set(multiplier + n).andThen(acc + n)
                                else acc
                            }
                        }
                    }
                }
            }
            assert(result.eval == 120)
        }
        "with an empty chunk" in {
            val chunk  = kyo2.Chunk.empty[Int]
            val result = chunk.foldLeft(0)(_ + _)
            assert(result.eval == 0)
        }
    }

    "toSeq" - {
        "converts a Chunk to an IndexedSeq" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            assert(chunk.toSeq == IndexedSeq(1, 2, 3, 4, 5))
        }

        "converts an empty Chunk to an empty IndexedSeq" in {
            val chunk = kyo2.Chunk.empty[Int]
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
            val chunk = kyo2.Chunk.empty[Int]
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
            val chunk = kyo2.Chunk.empty[Int].append(1).append(2).append(3)
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
            val chunk = kyo2.Chunk.empty[Int]
            val result = chunk
                .map(_ * 2).eval
                .filter(_ % 2 == 0)
            assert(result.eval.isEmpty)
        }

        "filter and map" in {
            val chunk = Chunk(1, 2, 3, 4, 5, 6)
            val result = chunk
                .filter(_ % 2 == 0).eval
                .map(_ + 1).eval
            assert(result == Chunk(3, 5, 7))
        }

        "multiple appends followed by head and tail" - {
            "returns the correct elements when calling head and tail repeatedly" in {
                val chunk = kyo2.Chunk.empty[Int]
                    .append(1)
                    .append(2)

                assert(chunk.toIndexed.head == 1)
                assert(chunk.toIndexed.tail.head == 2)
                assert(chunk.toIndexed.tail.tail.isEmpty)
            }

            "returns the correct elements when calling head and tail in a loop" in {
                val chunk = kyo2.Chunk.empty[Int]
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
                val chunk = kyo2.Chunk.empty[Int]
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
            val chunk = kyo2.Chunk.empty[Int]
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

    "flatten" - {
        "flattens a chunk of chunks in original order" in {
            val chunk: Chunk[Chunk[Int]] = Chunk(Chunk(1, 2), Chunk(3, 4, 5), Chunk(6, 7, 8, 9))
            assert(chunk.flatten == Chunk(1, 2, 3, 4, 5, 6, 7, 8, 9))
        }

        "returns an empty chunk when flattening an empty chunk of chunks" in {
            val chunk = kyo2.Chunk.empty[Chunk[Int]]
            assert(chunk.flatten.isEmpty)
        }

        "returns an empty chunk when flattening a chunk of empty chunks" in {
            val chunk = Chunk(kyo2.Chunk.empty[Int], kyo2.Chunk.empty[Int])
            assert(chunk.flatten.isEmpty)
        }

        "preserves the order of elements within each chunk" in {
            val chunk = Chunk(Chunk(1, 2, 3), Chunk(4, 5, 6))
            assert(chunk.flatten == Chunk(1, 2, 3, 4, 5, 6))
        }

        "handles a chunk containing a single chunk" in {
            val chunk = Chunk(Chunk(1, 2, 3))
            assert(chunk.flatten == Chunk(1, 2, 3))
        }

        "handles chunks of different sizes" in {
            val chunk = Chunk(Chunk(1), Chunk(2, 3), Chunk(4, 5, 6, 7))
            assert(chunk.flatten == Chunk(1, 2, 3, 4, 5, 6, 7))
        }

        "appends" in {
            val chunk: Chunk[Chunk[Int]] =
                Chunk(kyo2.Chunk.empty[Int].append(1).append(2), Chunk(3, 4).append(5), Chunk(6, 7).append(8).append(9))
            assert(chunk.flatten == Chunk(1, 2, 3, 4, 5, 6, 7, 8, 9))
        }
    }

    "toArray" - {
        "returns an array with the elements of a Chunk.Compact" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            val array = chunk.toArray
            assert(array.toSeq == Seq(1, 2, 3, 4, 5))
        }

        "returns an array with the elements of a Chunk.FromSeq" in {
            val chunk = kyo2.Chunk.from(IndexedSeq(1, 2, 3))
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
            val chunk = kyo2.Chunk.empty[Int]
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
            val chunk = kyo2.Chunk.from(IndexedSeq(1, 2, 3))
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
            val chunk = kyo2.Chunk.empty[Int]
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
            val chunk2 = kyo2.Chunk.empty[Int]
            val result = chunk1.concat(chunk2)
            assert(result == Chunk(1, 2, 3))
        }

        "concatenates an empty chunk with a non-empty chunk" in {
            val chunk1 = kyo2.Chunk.empty[Int]
            val chunk2 = Chunk(1, 2, 3)
            val result = chunk1.concat(chunk2)
            assert(result == Chunk(1, 2, 3))
        }

        "returns an empty chunk when concatenating two empty chunks" in {
            val chunk1 = kyo2.Chunk.empty[Int]
            val chunk2 = kyo2.Chunk.empty[Int]
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
            val chunk2 = kyo2.Chunk.from(IndexedSeq(4, 5, 6))
            val result = chunk1.concat(chunk2)
            assert(result == Chunk(1, 2, 3, 4, 5, 6))
        }

        "concatenates a Chunk.FromSeq with a Chunk.Compact" in {
            val chunk1 = kyo2.Chunk.from(IndexedSeq(1, 2, 3))
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
            val result = chunk.takeWhile(_ < 4).eval
            assert(result == Chunk(1, 2, 3))
        }

        "returns all elements if the predicate is always true" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.takeWhile(_ => true).eval
            assert(result == Chunk(1, 2, 3, 4, 5))
        }

        "returns an empty chunk if the predicate is always false" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.takeWhile(_ => false).eval
            assert(result.isEmpty)
        }

        "handles effectful predicates" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            val result = kyo2.Env.run(4) {
                kyo2.Var.run(0) {
                    chunk.takeWhile { n =>
                        kyo2.Var.update[Int](_ + 1).andThen(kyo2.Var.get[Int]).map { count =>
                            kyo2.Env.get[Int].map(_ > count)
                        }
                    }
                }
            }
            assert(result.eval == Chunk(1, 2, 3))
        }
    }

    "dropWhile" - {
        "drops elements while the predicate is true" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.dropWhile(_ < 3).eval
            assert(result == Chunk(3, 4, 5))
        }

        "drops all elements if the predicate is always true" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.dropWhile(_ => true).eval
            assert(result.isEmpty)
        }

        "drops no elements if the predicate is always false" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.dropWhile(_ => false).eval
            assert(result == Chunk(1, 2, 3, 4, 5))
        }

        "handles effectful predicates" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            val result = kyo2.Env.run(3) {
                kyo2.Var.run(0) {
                    chunk.dropWhile { n =>
                        kyo2.Var.update[Int](_ + 1).andThen(kyo2.Var.get[Int]).map { count =>
                            kyo2.Env.get[Int].map(_ > count)
                        }
                    }
                }
            }
            assert(result.eval == Chunk(3, 4, 5))
        }
    }

    "changes" - {
        "returns a chunk with consecutive duplicates removed" in {
            val chunk = Chunk(1, 1, 2, 3, 3, 3, 4, 4, 5)
            assert(chunk.changes == Chunk(1, 2, 3, 4, 5))
        }

        "returns the original chunk if there are no consecutive duplicates" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            assert(chunk.changes == Chunk(1, 2, 3, 4, 5))
        }

        "returns an empty chunk for an empty input chunk" in {
            val chunk = kyo2.Chunk.empty[Int]
            assert(chunk.changes.isEmpty)
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
            assert(chunk.changes(0) == Chunk(1))
            assert(chunk.changes(1) == kyo2.Chunk.empty[Int])
        }
    }

    "collect" - {
        "with a partial function" in {
            val chunk  = Chunk(1, 2, 3, 4, 5)
            val result = chunk.collect { case x if x % 2 == 0 => x * 2 }.eval
            assert(result == Chunk(4, 8))
        }

        "with an empty chunk" in {
            val chunk  = kyo2.Chunk.empty[Int]
            val result = chunk.collect { case x if x % 2 == 0 => x * 2 }.eval
            assert(result.isEmpty)
        }
    }

    "collectUnit" - {
        "with a function using Env, Abort and Var" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            var sum   = 0
            val result = kyo2.Var.run(2) {
                kyo2.Env.run(10) {
                    kyo2.Abort.run[String] {
                        chunk.collectUnit {
                            case n if n % 2 == 0 =>
                                kyo2.Var.get[Int].map { multiplier =>
                                    kyo2.Env.get[Int].map { max =>
                                        if sum + n * multiplier <= max then
                                            kyo2.Var.set(multiplier * n).andThen(sum += n * multiplier)
                                        else kyo2.Abort.fail(s"Sum exceeded max value of $max")
                                    }
                                }
                        }
                    }
                }
            }
            assert(result.eval == Left("Sum exceeded max value of 10"))
            assert(sum == 4)
        }
    }

    "foreach" - {
        "with a pure function" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            var sum   = 0
            chunk.foreach(x => sum += x).eval
            assert(sum == 15)
        }

        "with a function using Env" in {
            val chunk = Chunk(1, 2, 3, 4, 5)
            var sum   = 0
            val result = kyo2.Env.run(0) {
                chunk.foreach { x =>
                    kyo2.Env.use[Int](_ + x).map(sum += _)
                }
            }
            assert(result.eval == ())
            assert(sum == 15)
        }

        "with an empty chunk" in {
            val chunk = kyo2.Chunk.empty[Int]
            var sum   = 0
            chunk.foreach(x => sum += x).eval
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
            val chunk1 = kyo2.Chunk.empty[Int]
            val chunk2 = kyo2.Chunk.empty[Int]
            assert(chunk1.hashCode() == chunk2.hashCode())
        }

        "empty chunks are equal" in {
            val chunk1 = kyo2.Chunk.empty[Int]
            val chunk2 = kyo2.Chunk.empty[Int]
            assert(chunk1 == chunk2)
        }
    }

    "Chunk.empty" in {
        assert(kyo2.Chunk.empty[Int].toSeq.isEmpty)
    }

    "Chunks.collect" - {
        "collects elements from a chunk of effectful computations" in {
            val chunk  = Chunk[Int < Env[Int]](kyo2.Env.use[Int](_ + 1), kyo2.Env.use[Int](_ + 2))
            val result = kyo2.Env.run(1)(kyo2.Chunk.collect(chunk))
            assert(result.eval == Chunk(2, 3))
        }

        "collects elements from a chunk of effectful computations with different effect types" in {
            val chunk  = Chunk[Int < (Env[Int] & Var[Int])](kyo2.Env.use[Int](_ + 1), kyo2.Var.use[Int](_ + 2))
            val result = kyo2.Var.run(2)(kyo2.Env.run(1)(kyo2.Chunk.collect(chunk)))
            assert(result.eval == Chunk(2, 4))
        }
    }

    "toString" - {
        "prints the elements of a Chunk.Compact" in {
            val chunk = kyo2.Chunk.empty[Int].append(1).append(2).append(3).toIndexed
            assert(chunk.toString == "Chunk.Indexed(1, 2, 3)")
        }

        "prints the elements of a Chunk.FromSeq" in {
            val chunk = kyo2.Chunk.from(IndexedSeq(1, 2, 3))
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
