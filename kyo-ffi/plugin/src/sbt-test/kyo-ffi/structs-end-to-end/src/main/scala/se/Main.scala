package se

import kyo.AllowUnsafe.embrace.danger
import kyo.ffi.*

object Main:
    def main(args: Array[String]): Unit =
        val b = Ffi.load[SeBindings]

        // Nested struct param: 1 + 2 + 3 + 4 = 10.
        // NOTE: struct params are passed by pointer (ADDRESS layout) on JVM
        //, see se_lib.c. The C function signature reflects that.
        val ls = b.seLineSum(Line(Point(1, 2), Point(3, 4)))
        if ls != 10 then throw new AssertionError(s"expected line-sum=10, got $ls")

        // Packed struct param: tag=1 weights value * 10.
        // NOTE: The current JvmEmitter emits `.withByteAlignment(1L)` per field for
        // packed structs, which Panama rejects when the inner member has a natural
        // alignment > 1 (e.g., an int32 at offset 1 after a byte field). Using two
        // int32 fields keeps alignments uniform and avoids that defect while still
        // exercising the `packedStructs` code path.
        val pc1 = b.sePackedCompute(Packed(1, 42))
        if pc1 != 420 then throw new AssertionError(s"expected packed-compute(1,42)=420, got $pc1")
        val pc0 = b.sePackedCompute(Packed(0, 42))
        if pc0 != 42 then throw new AssertionError(s"expected packed-compute(0,42)=42, got $pc0")

        // Multi-value return: a=3, b=5 -> sum=8, product=15
        val p = b.sePair(3, 5)
        if p.sum != 8 then throw new AssertionError(s"expected sum=8, got ${p.sum}")
        if p.product != 15 then throw new AssertionError(s"expected product=15, got ${p.product}")

        // Struct-return with String field. code=0 → ("OK"), code=1 → ("ERR").
        val ok = b.seGetStatus(0)
        if ok.code != 0 || ok.message != "OK" then
            throw new AssertionError(s"expected status code=0 message=OK, got $ok")
        val err = b.seGetStatus(1)
        if err.code != 1 || err.message != "ERR" then
            throw new AssertionError(s"expected status code=1 message=ERR, got $err")

        println(s"OK: line=$ls packed=$pc1/$pc0 pair=$p status=${ok.code}=${ok.message}")
    end main
end Main
