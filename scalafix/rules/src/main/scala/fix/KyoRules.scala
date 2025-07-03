package fix

import scala.meta._
import scalafix.v1._

class KyoRules extends SemanticRule("KyoRules") {

    override def fix(implicit doc: SemanticDocument): Patch = {
        println("Tree.syntax: " + doc.tree.syntax)
        println("Tree.structure: " + doc.tree.structure)
        println("Tree.structureLabeled: " + doc.tree.structureLabeled)

        doc.tree.collect({
            case defer @ Term.Name("defer") if (SymbolMatcher.normalized("kyo.Direct$package.defer.").matches(defer.symbol)) =>
                Patch.replaceTree(defer, "direct")
        }).asPatch

    }

}
