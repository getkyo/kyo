package kyo

/** User-defined containers at a nested case-class field position.
  *
  * Confirms that a `Box[A]` type with a non-inline `given Schema[Box[A]]` composes correctly when nested inside a case class derived via
  * `derives Schema`. The derivation resolves `Schema[Box[Int]]` through standard implicit search and routes encode/decode through the
  * resolved Schema's `serializeWrite` / `serializeRead`.
  */
class SchemaCustomContainerNestedTest extends kyo.test.Test[Any]:

    // User-defined container type. Mirrors the shape of `kyo.Schema.listSchema`: a collection-typed
    // Structure node with per-element delegation to the inner Schema.
    case class Box[A](item: A) derives CanEqual

    // Non-inline given. Implicit-search resolves it at the `derives Schema` call site and at
    // `summon[Schema[Holder]]`.
    given boxSchema[A](using inner: Schema[A], frame: Frame): Schema[Box[A]] =
        Schema.init[Box[A]](
            writeFn = (b, w) =>
                w.objectStart("Box", 1)
                w.field("item", 0)
                inner.serializeWrite(b.item, w)
                w.objectEnd()
            ,
            readFn = r =>
                discard(r.objectStart())
                r.fieldParse()
                val item = inner.serializeRead(r)
                r.objectEnd()
                Box(item)
            ,
            structure = Structure.Type.Collection("Box", Tag[Any], inner.structure)
        )

    case class Holder(payload: Box[Int]) derives CanEqual, Schema

    case class HolderRec(children: List[HolderRec]) derives CanEqual, Schema

    case class Foo(payload: Box[Int]) derives Schema

    case class Bar(payload: List[Int]) derives Schema

    "User-defined Box[Int] at Holder nested position roundtrips via Json" in {
        val v       = Holder(Box(42))
        val encoded = Json.encode(v)
        // JsonWriter's `objectStart(name, size)` discards `name` (the JSON shape has no
        // class-name wrapper); the wire shape for `Holder(Box(42))` is therefore the nested
        // `{"payload":{"item":42}}` rather than the doubly-wrapped `{"payload":{"Box":{"item":42}}}`.
        assert(encoded.contains("\"payload\":{\"item\":42}"), s"unexpected encoded shape: $encoded")
        val decoded = Json.decode[Holder](encoded)
        assert(decoded == Result.succeed(v))
    }

    "Holder structure carries Box at the nested fieldType matching boxSchema[Int].structure shape" in {
        val holder    = summon[Schema[Holder]]
        val boxInt    = summon[Schema[Box[Int]]]
        val product   = holder.structure.asInstanceOf[Structure.Type.Product]
        val fieldType = product.fields.find(_.name == "payload").get.fieldType
        // `boxSchema[A]` is a polymorphic given def: each summon constructs a fresh
        // `Schema[Box[Int]]`, so reference equality on the materialized `Structure.Type` does not
        // hold across two distinct summons. Structural compatibility per `Structure.Type.compatible`
        // is the load-bearing check: the resolved `Schema` guarantees structural equivalence
        // through the field summon path.
        assert(
            Structure.Type.compatible(fieldType, boxInt.structure),
            s"expected structural compat with boxSchema[Int].structure but got $fieldType"
        )
        fieldType match
            case Structure.Type.Collection("Box", _, inner) =>
                inner match
                    case Structure.Type.Primitive(Structure.PrimitiveKind.Int, _) => succeed
                    case other => fail(s"expected inner Primitive(Int, _) but got $other")
            case other => fail(s"expected Collection(\"Box\", _, _) but got $other")
        end match
    }

    "Indirect-recursive container field reuses the cached Schema without StackOverflow" in {
        // The recursive position uses `List` rather than `Box` because indirect-recursion is resolved
        // through the built-in container recognizers (List / Vector / Set / Seq / Chunk / Option /
        // Maybe / Map); user-defined containers in a recursive position are not supported. The
        // architectural cycle-break property tested here lives on the same code path either way.
        val s           = summon[Schema[HolderRec]]
        val product     = s.structure.asInstanceOf[Structure.Type.Product]
        val payloadType = product.fields(0).fieldType
        val collection  = payloadType.asInstanceOf[Structure.Type.Collection]
        val elementType = collection.elementType
        elementType match
            case _: Structure.Type.Product => succeed
            case other                     => fail(s"expected recursive cycle break to a Product but got $other")
        end match
    }

    "Holder structures for Box and List variants are wire-shape similar at the resolved Schema level" in {
        val fooS    = summon[Schema[Foo]]
        val barS    = summon[Schema[Bar]]
        val fooProd = fooS.structure.asInstanceOf[Structure.Type.Product]
        val barProd = barS.structure.asInstanceOf[Structure.Type.Product]
        assert(fooProd.fields.size == 1)
        assert(barProd.fields.size == 1)
        assert(fooProd.fields(0).name == "payload")
        assert(barProd.fields(0).name == "payload")
        fooProd.fields(0).fieldType match
            case Structure.Type.Collection("Box", _, _) => succeed
            case other                                  => fail(s"expected Collection(\"Box\", _, _) but got $other")
        end match
        barProd.fields(0).fieldType match
            case Structure.Type.Collection("List", _, _) => succeed
            case other                                   => fail(s"expected Collection(\"List\", _, _) but got $other")
        end match
    }

    case class NestedItem(label: String, count: Int) derives CanEqual, Schema

    case class NestedHolder(
        data: Maybe[Map[String, Chunk[NestedItem]]]
    ) derives CanEqual, Schema

    "Maybe[Map[String, Chunk[NestedItem]]] derives via the macro container path and roundtrips" in {
        // Exercises three nested container layers in the derived Schema: the outer Optional
        // (`Maybe`) wraps a Map whose values are Chunks of a user-defined case class. The
        // derivation descends Optional -> Mapping -> Collection -> Product without losing the
        // element-type binding.
        val instance = NestedHolder(
            Present(
                Map(
                    "first"  -> Chunk(NestedItem("a", 1), NestedItem("b", 2)),
                    "second" -> Chunk(NestedItem("c", 3))
                )
            )
        )
        val encoded = Json.encode(instance)
        val decoded = Json.decode[NestedHolder](encoded)
        assert(decoded == Result.succeed(instance))
    }

end SchemaCustomContainerNestedTest
