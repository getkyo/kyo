package kyo

@TestVariant("Coll", "List", "Chunk")
class KyoForeachCollTest extends Test:

    import KyoForeachTest.*

    @TestVariant("Seq", "List", "Chunk")
    val Coll = Seq

    // @TestVariant("Coll", "List", "Chunk")
    "Coll specialized" - {
        "collectAll" in {
            assert(Kyo.collectAll(Coll.empty).eval == Coll.empty)
            assert(TestEffect1.run(Kyo.collectAll(List(TestEffect1(1))).map(_.head)).eval == 2)
            assert(TestEffect2.run(TestEffect1.run(Kyo.collectAll(List(TestEffect1(1), TestEffect1(2))).map(c =>
                (c(0), c(1))
            ))).eval == (
                2,
                3
            ))
            assert(TestEffect1.run(Kyo.collectAll(List.fill(100)(TestEffect1(1))).map(_.size)).eval == 100)
        }
    }
    
    
    // @TestVariant("Coll", "List", "Chunk")
end KyoForeachCollTest
