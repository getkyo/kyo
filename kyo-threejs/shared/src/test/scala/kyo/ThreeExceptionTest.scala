package kyo

class ThreeExceptionTest extends kyo.test.Test[Any]:

    "CanvasNotFound carries the selector in its message" in {
        val ex = ThreeException.CanvasNotFound("#missing")
        assert(ex.getMessage.contains("No canvas matched: #missing"))
    }

    "AssetLoadFailed carries the url and cause" in {
        val cause = new RuntimeException("boom")
        val ex    = ThreeException.AssetLoadFailed("/x.glb", cause)
        assert(ex.getMessage.contains("Failed to load: /x.glb"))
        assert(ex.getCause() eq cause)
    }

    "every leaf is a ThreeException and a KyoException" in {
        val cause = new RuntimeException("test cause")
        val leaves: List[ThreeException] = List(
            ThreeException.CanvasNotFound("#id"),
            ThreeException.WebGLUnavailable("no gl"),
            ThreeException.RenderFailure("render error", cause),
            ThreeException.AssetLoadFailed("/asset.glb", cause)
        )
        leaves.foreach { leaf =>
            assert(leaf.isInstanceOf[ThreeException])
            assert(leaf.isInstanceOf[KyoException])
        }
        ()
    }

end ThreeExceptionTest
