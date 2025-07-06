package kyo.rules

import scala.meta._
import scalafix.v1._

class KyoUpdateToV1_0 extends SemanticRule("KyoUpdateToV1_0") {

    override def fix(implicit doc: SemanticDocument): Patch = {
        println("Tree.syntax: " + doc.tree.syntax)
        println("Tree.structure: " + doc.tree.structure)
        println("Tree.structureLabeled: " + doc.tree.structureLabeled)

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
        }).asPatch

    }

}
