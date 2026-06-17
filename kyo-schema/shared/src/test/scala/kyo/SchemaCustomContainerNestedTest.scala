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

    "Indirect-recursive user container at recursive position resolves through the user's given" in {
        // After the generic-macro rewrite, ZERO specialization for container types means a
        // user-defined `Box[A]` and the built-in `List[A]` follow the same resolution path. The
        // macro emits `summonInline[Schema[Box[Holder]]]`; the user's `boxSchema[A]` given is
        // picked up; the cycle break is the by-name `Structure.Field._fieldType` thunk plus the
        // outer Schema's `lazy val structure` -- exactly the same mechanism that broke `List`'s
        // cycle in the prior design.
        case class BoxedHolder(payload: Box[BoxedHolder]) derives CanEqual, Schema

        val s           = summon[Schema[BoxedHolder]]
        val product     = s.structure.asInstanceOf[Structure.Type.Product]
        val payloadType = product.fields(0).fieldType
        val collection  = payloadType.asInstanceOf[Structure.Type.Collection]
        assert(collection.name == "Box", s"expected Box wrapper but got ${collection.name}")
        val elementType = collection.elementType
        elementType match
            case _: Structure.Type.Product => succeed
            case other                     => fail(s"expected recursive cycle break to a Product but got $other")
        end match
    }

    "Box[Holder] recursive Schema construction breaks the cycle (binding regression guard)" in {
        // Reproduces the regression guard from the binding design.
        //
        // `BoxedHolderRT` has a required recursive field, so a concrete instance has no natural
        // "leaf" value. The cycle-break property the binding design guarantees is structural: the
        // outer `Schema`'s `lazy val structure` builds the Product literal without forcing the
        // field-type thunk, the `Structure.Field._fieldType` is by-name, and the recursive
        // `summonInline[Schema[Box[BoxedHolderRT]]]` resolves through the synthesised
        // `derived$Schema` for `BoxedHolderRT` (Magnolia contract). Touching the structure tree
        // end-to-end exercises the cycle-break path: any failure would surface as
        // StackOverflowError. (Encode/decode is exercised by the round-trip tests on `Holder`
        // above, which use the non-recursive `Box[Int]` shape.)
        case class BoxedHolderRT(payload: Box[BoxedHolderRT]) derives CanEqual, Schema

        val s = summon[Schema[BoxedHolderRT]]
        val p = s.structure.asInstanceOf[Structure.Type.Product]
        assert(p.name == "BoxedHolderRT")
        val payload = p.fields(0).fieldType.asInstanceOf[Structure.Type.Collection]
        assert(payload.name == "Box")
        val inner = payload.elementType.asInstanceOf[Structure.Type.Product]
        assert(inner.name == "BoxedHolderRT")
        succeed
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
