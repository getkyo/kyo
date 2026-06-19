package kyo

import kyo.Three.foreach
import kyo.Three.foreachKeyed
import kyo.Three.render

class ThreeReactiveAstTest extends kyo.test.Test[Any]:

    "reactive records the signal" in {
        val sig  = Signal.initConst[Three](Three.empty)
        val node = Three.reactive(sig)
        assert(node.signal eq sig)
    }

    "render projects a value signal into a Reactive" in {
        val sig  = Signal.initConst(0.0)
        val node = sig.render(_ => Three.empty)
        assert(node.isInstanceOf[Three.Ast.Reactive])
    }

    "foreach builds a positional Foreach with Absent key" in {
        val sig  = Signal.initConst(Chunk.empty[Int])
        val node = sig.foreach(i => Three.empty)
        assert(node.key == Absent)
    }

    "foreachKeyed records the key projection" in {
        val sig  = Signal.initConst(Chunk.empty[Int])
        val node = sig.foreachKeyed(_.toString)(i => Three.empty)
        assert(node.key.isDefined)
    }

    "when wraps a conditional in a Reactive" in {
        val cond = Signal.initConst(true)
        val node = Three.when(cond)(Three.mesh(Three.Geometry.box(), Three.Material.basic()))
        assert(node.isInstanceOf[Three.Ast.Reactive])
    }

end ThreeReactiveAstTest
