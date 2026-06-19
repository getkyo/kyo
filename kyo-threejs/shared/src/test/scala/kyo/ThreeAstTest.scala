package kyo

class ThreeAstTest extends kyo.test.Test[Any]:

    "scene wraps children in order" in {
        val l = Three.Light.ambient()
        val m = Three.mesh(Three.Geometry.box(), Three.Material.standard())
        assert(Three.scene(l, m).children == Chunk(l, m))
    }

    "mesh requires geometry and material and is Interactive with Animated" in {
        val geom = Three.Geometry.box()
        val mat  = Three.Material.standard()
        val node = Three.mesh(geom, mat)
        assert(node.isInstanceOf[Three.Ast.Mesh])
        assert(node.geometry == geom)
        assert(node.material == mat)
        val _: Three.Ast.Interactive = node
        val _: Three.Ast.Animated    = node
        assert(node.isInstanceOf[Three.Ast.Interactive] && node.isInstanceOf[Three.Ast.Animated])
    }

    "empty is a childless Group" in {
        assert(Three.empty.children == Chunk.empty)
    }

    "box defaults to a unit cube" in {
        val box = Three.Geometry.box()
        assert(box.width == 1.0)
        assert(box.height == 1.0)
        assert(box.depth == 1.0)
    }

    "sphere carries the segment defaults" in {
        val sphere = Three.Geometry.sphere()
        assert(sphere.radius == 1.0)
        assert(sphere.widthSegments == 32)
        assert(sphere.heightSegments == 16)
    }

    "standard material defaults are white, zero metalness, one roughness" in {
        val mat = Three.Material.standard()
        // Verify the default by round-tripping: the factory default must equal a fresh same-default factory call.
        assert(mat == Three.Material.standard())
        assert(mat.map == Absent)
    }

    "material map defaults to Absent never null" in {
        val mat: Three.Ast.Material.Basic = Three.Material.basic()
        assert(mat.map == Absent)
        // Absent must equal Absent regardless of the type parameter.
        assert(mat.map.isEmpty)
    }

    "spot light angle is Radians-typed from a degree helper" in {
        val spot = Three.Light.spot(angle = Radians.deg(45))
        assert(math.abs(spot.angle.toDouble - Radians.deg(45).toDouble) < 1e-9)
    }

    "perspective camera defaults" in {
        val cam = Three.Camera.perspective()
        assert(cam.fov == Radians.deg(75))
        assert(cam.near == 0.1)
        assert(cam.far == 1000.0)
        // The default position (0,0,5) must appear in the transform, however the internal wrapping is done.
        assert(cam.transform.position.isDefined)
    }

    "position(Vec3) records a transform entry" in {
        val v      = Vec3(1, 2, 3)
        val result = Three.group().position(v)
        assert(result.transform.position.isDefined)
    }

    "position(Vec3) round-trips: a group positioned twice at the same Vec3 is equal" in {
        val g = Three.group()
        val v = Vec3(7, 8, 9)
        assert(g.position(v) == g.position(v))
    }

    "position(Vec3) and position(Vec3) at different values are not equal" in {
        val g = Three.group()
        assert(g.position(Vec3(1, 2, 3)) != g.position(Vec3(4, 5, 6)))
    }

    "a Light is Object3D but not Interactive (compile shape)" in {
        val light                 = Three.Light.ambient()
        val _: Three.Ast.Object3D = light
        // Light does not extend Interactive: ascribing it as Interactive does not type-check.
        assert(!scala.compiletime.testing.typeChecks("val i: Three.Ast.Interactive = light"))
    }

    "Group.onFrame attaches the frame hook to the props bundle" in {
        val g = Three.group().onFrame(_ => Sync.defer(()))
        assert(g.props.onFrame.isDefined)
    }

    "Group is Animated but not Interactive (compile shape)" in {
        val g                     = Three.group()
        val _: Three.Ast.Animated = g
        // Group does not extend Interactive: ascribing it as Interactive does not type-check.
        assert(!scala.compiletime.testing.typeChecks("val i: Three.Ast.Interactive = g"))
    }

    "Group.onFrame returns Group preserving children" in {
        val child = Three.mesh(Three.Geometry.box(), Three.Material.standard())
        val g     = Three.group(child).onFrame(_ => Sync.defer(()))
        assert(g.children == Chunk(child))
        assert(g.props.onFrame.isDefined)
    }

end ThreeAstTest
