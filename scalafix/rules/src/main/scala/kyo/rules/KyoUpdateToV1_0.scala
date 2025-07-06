package kyo.rules

import scala.meta._
import scalafix.v1._

class KyoUpdateToV1_0 extends SemanticRule("KyoUpdateToV1_0") {

    override def fix(implicit doc: SemanticDocument): Patch = {
        implicit class TreeOps(tree: Tree) {
            def matches(symbol: String): Boolean = {
                tree.symbol.normalized.value.matches("^" + symbol.flatMap(c =>
                    if (c.isLetterOrDigit) c.toString else ".*"
                ) + ".?$")
            }
        }

        doc.tree.collect({
            case defer @ Term.Name("defer") if defer matches "kyo.Direct.defer" =>
                Patch.replaceTree(defer, "direct")

            case apply @ Term.Select(Term.Name("IO"), Term.Name("apply")) if apply matches "kyo.IO.apply" =>
                Patch.replaceTree(apply, "Sync.defer")

            case Term.Apply.After_4_6_0(io @ Term.Name("IO"), _) if io matches "kyo.IO" =>
                Patch.replaceTree(io, "Sync.defer")

            case io @ Type.Name("IO") if io matches "kyo.IO" =>
                Patch.replaceTree(io, "Sync")

            case asyncRun @ Term.Select(Term.Name("Async"), Term.Name("run")) if asyncRun matches "kyo.Async.run" =>
                Patch.replaceTree(asyncRun, "Fiber.init")

            case Term.Apply.After_4_6_0(async @ Term.Name("Async"), _) if async matches "kyo.Async" =>
                Patch.replaceTree(async, "Async.defer")

            case apply @ Term.Select(Term.Name("Async"), Term.Name("apply")) if apply matches "kyo.Async.apply" =>
                Patch.replaceTree(apply, "Async.defer")
        }).asPatch

    }

}
