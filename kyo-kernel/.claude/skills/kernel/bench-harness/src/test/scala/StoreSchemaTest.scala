import Model.*
import kyo.*
import kyo.test.*

/** Holds the stored-run schema to the runs already on disk.
  *
  * The store is the durable record: a run measured weeks ago is the only copy of that measurement, and a schema change must leave it
  * readable. Adding `allocByMethod` without a default once made all 34 stored runs undecodable at once; `coverage`, `Row.iterations` and the
  * renamed `JitMetrics` counters are the same class of change, an additive field with no default. This suite decodes the real runs recorded
  * before those fields existed, so the next such field fails here rather than silently orphaning the history.
  *
  * One change it does NOT bridge is the `jit` field's representation, which went from a flat per-site list
  * (`{method, bytes, inlined: Boolean, reason: String}`, 944 entries in these runs) to the aggregated `InlineSites`
  * (`inlined: Int, refused: Int, reasons: Chunk`). That is a shape change, not an added field, and no default reconciles a Boolean with an
  * Int, so the old `jit` blob is isolated out below and its recovery is a separate decision (a custom lenient decoder, or accept it lossy).
  */
class StoreSchemaTest extends Test[Any]:

    val runs = Roots.repo / "qa-artifacts" / "store" / "runs"

    private val failed = scala.collection.mutable.ListBuffer.empty[String]
    private def check(name: String, cond: Boolean, detail: => String = ""): Unit =
        if !cond then failed += (if detail.nonEmpty then s"$name  <- $detail" else name)

    // strip the retired flat-`jit` array so its shape change does not mask the additive-field compatibility this suite is about; with the
    // field absent it takes its default, exactly as a genuinely older run lacking it would
    private def withoutFlatJit(raw: String): String = raw.replaceAll(""""jit":\[[^\]]*\]""", """"jit":[]""")

    def decode(name: String)(using Frame): Run < (Async & Abort[Any]) =
        (runs / name).read.map { raw =>
            Json.decode[Run](withoutFlatJit(raw)) match
                case Result.Success(r) => r
                case other             => Abort.fail(new AssertionError(s"$name did not decode: $other"))
        }

    "a Full run recorded before coverage, Row.iterations and the JitMetrics rename still decodes" in {
        for
            control <- decode("qa-control-36b41336fb-1786935756136.json")
            variant <- decode("qa-variant-0f9b4b69f7-1786935842708.json")
        yield
            check("the control run decodes with its rows", control.rows.nonEmpty, s"${control.rows.size} rows")
            check("coverage absent in the record reads as empty, not a failure", control.coverage.isEmpty)
            check("a row recorded before the iteration series reads as having none", control.rows.forall(_.iterations.isEmpty))
            check("its jit_metrics decodes though it predates the rename", control.jit_metrics.isDefined, s"${control.jit_metrics}")
            check(
                "and the counters the old shape lacked default to zero rather than blocking the decode",
                control.jit_metrics.exists(m => m.osrTasks == 0 && m.madeNotEntrant == 0 && m.plantedTraps == 0 && m.runtimeDeopts == 0),
                s"${control.jit_metrics}"
            )
            check("the counters the old shape did carry survive", control.jit_metrics.exists(m => m.tasks >= 0 && m.c2Tasks >= 0))
            check("the variant run decodes too", variant.rows.nonEmpty, s"${variant.rows.size} rows")
            check("both are Full runs, so their evidence chunks are present", control.cpu.nonEmpty || control.alloc.nonEmpty, "neither cpu nor alloc")
            assert(failed.isEmpty, "claims that did not hold:\n" + failed.mkString("\n"))
    }

    "a re-encoded run round-trips and survives the next defaulted field being dropped" in {
        for
            control <- decode("qa-control-36b41336fb-1786935756136.json")
        yield
            val encoded = Json.encode(control)
            Json.decode[Run](encoded) match
                case Result.Success(r) => check("a re-encoded run decodes", r.rows.size == control.rows.size)
                case other             => check("a re-encoded run decodes", false, other.toString)
            // the next field added to Run must not orphan this record either: strip a defaulted evidence chunk and it still reads
            val stripped = encoded.replaceAll(""","cpu":\[[^\]]*\]""", "")
            check("the field really left the fixture", !stripped.contains("\"cpu\":"), stripped.take(80))
            Json.decode[Run](stripped) match
                case Result.Success(r) => check("a run missing a defaulted evidence chunk still decodes", r.cpu.isEmpty)
                case other             => check("a run missing a defaulted evidence chunk still decodes", false, other.toString)
            assert(failed.isEmpty, "claims that did not hold:\n" + failed.mkString("\n"))
    }

end StoreSchemaTest
