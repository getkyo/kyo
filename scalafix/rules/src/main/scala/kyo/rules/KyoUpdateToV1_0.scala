package kyo.rules

import scala.meta._
import scalafix.v1._

class KyoUpdateToV1_0 extends SemanticRule("KyoUpdateToV1_0") {

    override def fix(implicit doc: SemanticDocument): Patch = {
        println("Tree.syntax: " + doc.tree.syntax)
        println("Tree.structure: " + doc.tree.structure)
        println("Tree.structureLabeled: " + doc.tree.structureLabeled)

        doc.tree.collect({
            case defer @ Term.Name("defer") if (SymbolMatcher.normalized("kyo.Direct$package.defer.").matches(defer.symbol)) =>
                Patch.replaceTree(defer, "direct")

            //TODO: IO => Sync
        }).asPatch

    }

}
