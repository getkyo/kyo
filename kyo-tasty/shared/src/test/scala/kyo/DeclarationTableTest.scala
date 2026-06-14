package kyo

import kyo.internal.tasty.symbol.DeclarationTable
import kyo.internal.tasty.symbol.SymbolKind

/** Tests for DeclarationTable: flat-array vs HashMap cutover, and AtomicRef CAS-swap visibility.
  */
class DeclarationTableTest extends kyo.test.Test[Any]:

    private def makeSymbol(nameStr: String): Tasty.Symbol =
        import AllowUnsafe.embrace.danger
        Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name(nameStr), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty)
    end makeSymbol

    // Small tables (size <= 8) use the flat-array internal representation.
    "class with 4 members uses flat-array Dict path: all members retrievable" in {
        import AllowUnsafe.embrace.danger
        val members = (1 to 4).map(i => Tasty.Name(s"member$i") -> makeSymbol(s"member$i"))
        val table   = DeclarationTable.build(members)
        assert(
            table.storageKind == "flat-array",
            s"Expected flat-array storage for 4 members but got ${table.storageKind}"
        )
        members.foreach { case (name, symbol) =>
            val result = table.get(name)
            result match
                case Present(found) => assert(found eq symbol, s"Wrong symbol for $name")
                case Absent         => fail(s"Symbol not found for $name")
        }
        succeed
    }

    // Larger tables (size > 8) use the HashMap internal representation.
    "class with 9 members (above threshold) uses HashMap path: all members retrievable" in {
        import AllowUnsafe.embrace.danger
        val members = (1 to 9).map(i => Tasty.Name(s"field$i") -> makeSymbol(s"field$i"))
        val table   = DeclarationTable.build(members)
        assert(
            table.storageKind == "hash-map",
            s"Expected hash-map storage for 9 members but got ${table.storageKind}"
        )
        members.foreach { case (name, symbol) =>
            val result = table.get(name)
            result match
                case Present(found) => assert(found eq symbol, s"Wrong symbol for $name")
                case Absent         => fail(s"Symbol not found for $name")
        }
        succeed
    }

    // Writer populates FIRST, then releases latch; reader awaits latch, then reads.
    // Verifies AtomicRef CAS-swap visibility across fibers.
    "AtomicRef CAS-swap visibility: reader sees either empty or fully-populated dict" in {
        import AllowUnsafe.embrace.danger
        Async.timeout(1.second) {
            val table   = DeclarationTable.init()
            val members = (1 to 4).map(i => Tasty.Name(s"m$i") -> makeSymbol(s"m$i"))
            for
                latch <- Latch.init(1)
                readerFiber <- Fiber.initUnscoped(
                    latch.await.andThen {
                        Sync.defer {
                            val dict = table.all
                            val sz   = dict.size
                            // Reader unblocks only after writer has populated: must see 4.
                            assert(sz == 4, s"Expected fully-populated table (size=4) but saw size=$sz")
                        }
                    }
                )
                _ <- Sync.defer {
                    val b = DictBuilder.init[Tasty.Name, Tasty.Symbol]
                    members.foreach { case (k, v) => discard(b.add(k, v)) }
                    table.populate(b.result())
                }
                _ <- latch.release
                _ <- readerFiber.get
            yield succeed
            end for
        }
    }

end DeclarationTableTest
