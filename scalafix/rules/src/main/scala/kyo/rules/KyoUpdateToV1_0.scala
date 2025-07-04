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

            case ioApply @ Term.Select(Term.Name("IO"), Term.Name("apply")) if SymbolMatcher.normalized("kyo.IO$package.IO.apply.").matches(ioApply.symbol) =>
                Patch.replaceTree(ioApply, "Sync.defer")

            case Term.Apply.After_4_6_0(io @ Term.Name("IO"), _) if SymbolMatcher.normalized("kyo.IO$package.IO.").matches(io) =>
                Patch.replaceTree(io, "Sync.defer")

            case io @ Type.Name("IO") if SymbolMatcher.normalized("kyo/IO$package.IO#").matches(io) =>
                Patch.replaceTree(io, "Sync")
        }).asPatch

    }

}
