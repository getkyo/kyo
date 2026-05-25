package kyo

import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.symbol.DeclarationTable
import kyo.internal.reflect.symbol.Symbol as InternalSymbol

/** Tests for DeclarationTable: flat-array vs HashMap cutover, and AtomicRef CAS-swap visibility.
  *
  * Plan tests 21-23.
  */
class DeclarationTableTest extends Test:

    private def makeSymbol(nameStr: String): Reflect.Symbol =
        val home   = new ClasspathRef
        val origin = Reflect.Symbol.TastyOrigin.empty
        Reflect.Symbol.make(
            Reflect.SymbolKind.Val,
            Reflect.Flags.empty,
            Reflect.Name(nameStr),
            null,
            home,
            origin,
            Absent
        )
    end makeSymbol

    // Test 21: 4 members use flat-array Dict path; all retrievable by name.
    // Verifies that small tables (size <= 8) use the flat-array internal representation.
    "class with 4 members uses flat-array Dict path: all members retrievable" in run {
        val members = (1 to 4).map(i => Reflect.Name(s"member$i") -> makeSymbol(s"member$i"))
        val table   = DeclarationTable.build(members)
        assert(
            table.storageKind == "flat-array",
            s"Expected flat-array storage for 4 members but got ${table.storageKind}"
        )
        members.foreach { case (name, sym) =>
            val result = table.get(name)
            result match
                case Present(found) => assert(found eq sym, s"Wrong symbol for $name")
                case Absent         => fail(s"Symbol not found for $name")
        }
        succeed
    }

    // Test 22: 9 members (above threshold 8) use HashMap path; all retrievable.
    // Verifies that larger tables (size > 8) use the HashMap internal representation.
    "class with 9 members (above threshold) uses HashMap path: all members retrievable" in run {
        val members = (1 to 9).map(i => Reflect.Name(s"field$i") -> makeSymbol(s"field$i"))
        val table   = DeclarationTable.build(members)
        assert(
            table.storageKind == "hash-map",
            s"Expected hash-map storage for 9 members but got ${table.storageKind}"
        )
        members.foreach { case (name, sym) =>
            val result = table.get(name)
            result match
                case Present(found) => assert(found eq sym, s"Wrong symbol for $name")
                case Absent         => fail(s"Symbol not found for $name")
        }
        succeed
    }

    // Test 23: AtomicRef CAS-swap visibility: reader sees fully-populated dict after latch release.
    // Writer: populate FIRST, then release latch. Reader: await latch, then read.
    // Verifies that the AtomicRef CAS-swap in populate() is visible to a fiber that starts reading
    // after the latch is released.
    "AtomicRef CAS-swap visibility: reader sees either empty or fully-populated dict" in run {
        Async.timeout(1.second) {
            val table   = new DeclarationTable
            val members = (1 to 4).map(i => Reflect.Name(s"m$i") -> makeSymbol(s"m$i"))
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
                    val b = DictBuilder.init[Reflect.Name, Reflect.Symbol]
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
