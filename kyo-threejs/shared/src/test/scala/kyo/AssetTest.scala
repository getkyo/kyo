package kyo

import scala.scalajs.js.Dynamic as JsDynamic

class AssetTest extends kyo.test.Test[Any]:

    private def custom =
        Three.custom((s: JsDynamic) => s)(JsDynamic.literal().asInstanceOf[JsDynamic])

    "Gltf carries a Custom root, named Custom nodes, and clip names" in {
        val root  = custom
        val nodes = Map("Door_01" -> custom)
        val anims = Chunk("idle")
        val gltf  = Asset.Gltf(root, nodes, anims)
        assert(gltf.root == root)
        assert(gltf.nodes("Door_01") == nodes("Door_01"))
        assert(gltf.animations == Chunk("idle"))
    }

    "a handler attaches to the loaded root and the node carries it" in {
        val root        = custom
        val withHandler = root.onPointerOver(_ => Sync.defer(()))
        assert(withHandler.props.onPointerOver.isDefined)
    }

    "a handler attaches to a named node" in {
        val node = custom.onClick(_ => Sync.defer(()))
        val gltf = Asset.Gltf(custom, Map("Wheel" -> node), Chunk.empty)
        assert(gltf.nodes("Wheel").props.onClick.isDefined)
    }

end AssetTest
