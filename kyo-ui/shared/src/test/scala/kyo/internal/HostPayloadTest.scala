package kyo.internal

import kyo.*

/** Schema round-trip codec test for every HostPayload/HostValue/StructuralOp/SceneDescriptor/PointerData leaf.
  *
  * All leaves are purely synchronous (encode then decode with no async), run on JVM, JS, and Wasm, and carry no
  * browser dependency. Every wire type must survive a Json.encode then Json.decode round-trip with field-exact
  * equality (CanEqual ==). The absence of js.Dynamic in HostPayload.scala is a verify-time static check, not a
  * Scala test body.
  */
class HostPayloadTest extends kyo.test.Test[Any]:

    "Prop(V3) Schema round-trip" in {
        val original: HostPayload = HostPayload.Prop("node-3", "position", HostValue.V3(1.0, 2.0, 3.0))
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
    }

    "Prop(Col) Schema round-trip" in {
        // 0x00ff00 == 65280 decimal; the round-trip must preserve the Int bit-pattern exactly.
        val original: HostPayload = HostPayload.Prop("node-0", "color", HostValue.Col(0x00ff00))
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
        decoded match
            case Result.Success(HostPayload.Prop(_, _, HostValue.Col(rgb))) => assert(rgb == 65280)
            case other                                                      => fail(s"unexpected: $other")
        end match
    }

    "Prop(Num) Schema round-trip" in {
        val original: HostPayload = HostPayload.Prop("node-1", "opacity", HostValue.Num(0.5))
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
    }

    "Structural(Insert) round-trip with descriptor" in {
        val descriptor            = SceneDescriptor("mesh", Seq("color" -> HostValue.Col(255)), Seq.empty)
        val original: HostPayload = HostPayload.Structural(StructuralOp.Insert("k7", 2, descriptor))
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
        decoded match
            case Result.Success(HostPayload.Structural(StructuralOp.Insert(k, idx, d), regionId)) =>
                assert(k == "k7")
                assert(idx == 2)
                assert(d.kind == "mesh")
                assert(d.children == Seq.empty)
                // The default region id (the host root) survives the round-trip.
                assert(regionId == "r")
            case other => fail(s"unexpected: $other")
        end match
    }

    "Structural(Insert) round-trip carrying a directional light with a transform" in {
        // A light descriptor with a color, an intensity, and position/rotation transform slots: the
        // wire must carry geometry/material/transform AND light fields, so every slot must survive the
        // codec field-exact (V3 transforms and a Col/Num light are all serializable HostValues).
        val descriptor = SceneDescriptor(
            "light.directional",
            Seq(
                "color"     -> HostValue.Col(0xffeeaa),
                "intensity" -> HostValue.Num(0.75),
                "position"  -> HostValue.V3(1.0, 10.0, -4.0),
                "rotation"  -> HostValue.V3(0.0, 1.5707963, 0.0)
            ),
            Seq.empty
        )
        val original: HostPayload = HostPayload.Structural(StructuralOp.Insert("light0", 0, descriptor))
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
        decoded match
            case Result.Success(HostPayload.Structural(StructuralOp.Insert(k, idx, d), _)) =>
                assert(k == "light0")
                assert(idx == 0)
                assert(d.kind == "light.directional")
                // The color survives as the exact RGB bit-pattern.
                assert(d.props.contains("color" -> HostValue.Col(0xffeeaa)))
                // The scalar intensity survives exactly.
                assert(d.props.contains("intensity" -> HostValue.Num(0.75)))
                // The position transform survives as a field-exact V3.
                assert(d.props.contains("position" -> HostValue.V3(1.0, 10.0, -4.0)))
                // The rotation transform survives as a field-exact V3.
                assert(d.props.contains("rotation" -> HostValue.V3(0.0, 1.5707963, 0.0)))
            case other => fail(s"unexpected: $other")
        end match
    }

    "Structural(Insert) round-trip carrying a mesh with geometry and material" in {
        // The mesh descriptor now carries the typed geometry shape + params and the material class tag,
        // so the server's actual sphere/torus/basic-material reconstitutes faithfully. Both must survive
        // the codec field-exact, alongside the existing color/transform props.
        val descriptor = SceneDescriptor(
            "mesh",
            Seq(
                "color"   -> HostValue.Col(0x2244ff),
                "opacity" -> HostValue.Num(1.0)
            ),
            Seq.empty,
            geometry = Present(GeometryDescriptor.Sphere(2.5, 24, 12)),
            material = Present(MaterialKind.Basic)
        )
        val original: HostPayload = HostPayload.Structural(StructuralOp.Insert("mesh0", 0, descriptor))
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
        decoded match
            case Result.Success(HostPayload.Structural(StructuralOp.Insert(_, _, d), _)) =>
                assert(d.kind == "mesh")
                // The geometry shape + params survive exactly.
                assert(d.geometry == Present(GeometryDescriptor.Sphere(2.5, 24, 12)), s"geometry must survive, got ${d.geometry}")
                // The material class tag survives.
                assert(d.material == Present(MaterialKind.Basic), s"material must survive, got ${d.material}")
                // The whole-number opacity survives as a Num (not a Col): the D-9 slot-typed wire value.
                assert(d.props.contains("opacity" -> HostValue.Num(1.0)))
            case other => fail(s"unexpected: $other")
        end match
    }

    "Boot envelope round-trip carrying the scene insert and a non-default camera" in {
        // The page-load boot envelope carries the scene's root insert AND the embed's camera, so the
        // client reconstitutes the server's actual viewpoint. Every camera field must survive field-exact.
        val sceneDesc = SceneDescriptor("scene", Seq.empty, Seq.empty)
        val camera = CameraDescriptor.Perspective(
            fovRadians = 0.7853981633974483,
            near = 0.25,
            far = 250.0,
            position = HostValue.V3(3.0, 7.0, -2.0),
            lookAt = HostValue.V3(1.0, 0.0, 1.0)
        )
        val original: HostPayload = HostPayload.Boot(StructuralOp.Insert("r", 0, sceneDesc), camera)
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
        decoded match
            case Result.Success(HostPayload.Boot(StructuralOp.Insert(k, idx, d), cam)) =>
                assert(k == "r")
                assert(idx == 0)
                assert(d.kind == "scene")
                cam match
                    case CameraDescriptor.Perspective(fov, near, far, pos, lookAt) =>
                        assert(fov == 0.7853981633974483, s"fov must survive, got $fov")
                        assert(near == 0.25)
                        assert(far == 250.0)
                        assert(pos == HostValue.V3(3.0, 7.0, -2.0), s"camera position must survive, got $pos")
                        assert(lookAt == HostValue.V3(1.0, 0.0, 1.0), s"camera lookAt must survive, got $lookAt")
                    case other => fail(s"unexpected camera: $other")
                end match
            case other => fail(s"unexpected: $other")
        end match
    }

    "Structural(Remove) round-trip" in {
        val original: HostPayload = HostPayload.Structural(StructuralOp.Remove("k3"))
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
    }

    "Structural(Move) round-trip" in {
        val original: HostPayload = HostPayload.Structural(StructuralOp.Move("k2", 0))
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
    }

    "PointerData round-trip" in {
        val original = PointerData(1.5, -2.0, 0.25, 8.0, 0.3, -0.4)
        val encoded  = Json.encode[PointerData](original)
        val decoded  = Json.decode[PointerData](encoded)
        assert(decoded == Result.Success(original))
        decoded match
            case Result.Success(pd) =>
                assert(pd.pointX == 1.5)
                assert(pd.pointY == -2.0)
                assert(pd.pointZ == 0.25)
                assert(pd.distance == 8.0)
                assert(pd.ndcX == 0.3)
                assert(pd.ndcY == -0.4)
            case other => fail(s"unexpected: $other")
        end match
    }

end HostPayloadTest
