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
            case defer @ q"defer" if defer.matches("kyo.Direct.defer") =>
                Patch.replaceTree(defer, "direct")

            // IO
            case apply @ q"IO.apply" if apply.matches("kyo.IO.apply") =>
                Patch.replaceTree(apply, "Sync.defer")

            case q"$io($_)" if io.matches("kyo.IO") =>
                Patch.replaceTree(io, "Sync.defer")

            case io @ t"IO" if io matches "kyo.IO" =>
                Patch.replaceTree(io, "Sync")

            // Async
            case asyncRun @ q"Async.run" if asyncRun.matches("kyo.Async.run") =>
                Patch.replaceTree(asyncRun, "Fiber.init")

            case apply @ q"Async.apply" if apply.matches("kyo.Async.apply") =>
                Patch.replaceTree(apply, "Async.defer")

            case q"$async($_)" if async.matches("kyo.Async") =>
                Patch.replaceTree(async, "Async.defer")

        }).asPatch

    }

}
