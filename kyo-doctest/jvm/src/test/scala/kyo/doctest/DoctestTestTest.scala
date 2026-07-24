package kyo.doctest

import kyo.*

class DoctestTestTest extends DoctestTest:

    private def generateConcurrently(generator: UUIDGenerator, count: Int): Chunk[UUID] < Async =
        Scope.run {
            for
                release <- Latch.init(1)
                fibers <- Kyo.fill(count) {
                    Fiber.init(release.await.andThen(generator.v4))
                }
                _      <- release.release
                values <- Kyo.foreach(fibers)(_.get)
            yield values
        }

    "deterministic UUID generator" - {
        "concurrent calls produce a strictly increasing set without duplicates" in {
            val count     = 64
            val generator = DoctestTest.generator(0x0123456789abcdefL)

            generateConcurrently(generator, count).map { values =>
                val sorted = values.toSeq.sorted
                assert(sorted.size == count)
                assert(sorted.distinct.size == count)
                assert(sorted.sliding(2).forall {
                    case Seq(previous, next) => previous.compare(next) < 0
                    case _                   => true
                })
                assert(sorted.head.show == "01234567-89ab-4cde-bc00-000000000001")
                assert(sorted.last.show == "01234567-89ab-4cde-bc00-000000000040")
            }
        }

        "equal namespaces reproduce the same concurrent sequence" in {
            val namespace = 0x0123456789abcdefL

            for
                first  <- generateConcurrently(DoctestTest.generator(namespace), 64)
                second <- generateConcurrently(DoctestTest.generator(namespace), 64)
            yield assert(first.toSeq.sorted == second.toSeq.sorted)
            end for
        }

        "distinct namespaces do not overlap at equal counter positions" in {
            val count                          = 64
            val firstGenerator: UUIDGenerator  = DoctestTest.generator(0x0123456789abcdefL)
            val secondGenerator: UUIDGenerator = DoctestTest.generator(0xfedcba987654cdefL)
            for
                first  <- Kyo.fill(count)(firstGenerator.v4)
                second <- Kyo.fill(count)(secondGenerator.v4)
            yield
                assert(first.zip(second).forall { case (left, right) => left != right })
                assert(first.toSet.intersect(second.toSet).isEmpty)
            end for
        }

        "emits version 4 with the RFC variant" in {
            DoctestTest.generator(1L).v4.map { generated =>
                assert(generated.version == 4)
                assert(generated.variant == UUID.Variant.RFC)
            }
        }

        "emits version 7 with the RFC variant" in {
            DoctestTest.generator(1L).v7.map { generated =>
                assert(generated.version == 7)
                assert(generated.variant == UUID.Variant.RFC)
            }
        }
    }

    "aroundLeaf installs the process-wide UUID generator inside an enclosing UUID scope" in {
        val sentinel = UUID.parse("ffffffff-ffff-4fff-bfff-ffffffffffff").getOrThrow
        val enclosing =
            new UUIDGenerator:
                def v4(using Frame): UUID < Sync = sentinel
                def v7(using Frame): UUID < Sync = sentinel

        UUID.let(enclosing)(aroundLeaf(UUID.v4)).map { generated =>
            assert(generated != sentinel)
            assert(generated.version == 4)
            assert(generated.variant == UUID.Variant.RFC)
        }
    }
end DoctestTestTest
