package kyo

/** Regression test for user-defined containers at a nested case-class field position.
  *
  * Confirms that a `Box[A]` type with a non-inline `given Schema[Box[A]]` composes correctly when nested inside a case class derived via
  * `derives Schema`, with no modification to any macro-internal symbol table. The derivation macro must be structurally agnostic to
  * `Box`'s existence: it resolves `Schema[Box[Int]]` through the standard implicit search and routes encode/decode through the resolved
  * Schema's `serializeWrite` / `serializeRead`.
  */
class SchemaCustomContainerNestedTest extends kyo.test.Test[Any]:

    // User-defined container type. Mirrors the shape of `kyo.Schema.listSchema` (collection-typed
    // Structure node + per-element delegation to the inner Schema) without adding `Box` to any
    // macro symbol table.
    case class Box[A](item: A) derives CanEqual

    // Non-inline given. The implicit-search resolution must run at the `derives Schema` call
    // site (and at `summon[Schema[Holder]]`) without the macro reaching for any classifier table.
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
        // The recursive position uses `List` rather than `Box` because the macro's
        // `buildRecursiveResolver` resolves indirect-recursion through its built-in container
        // recognizers (List / Vector / Set / Seq / Chunk / Option / Maybe / Map). User-defined
        // containers in a recursive position remain a documented macro gap; the architectural
        // cycle-break property tested here lives on the same code path either way.
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

    "Box does not appear in any macro source symbol table".onlyJvm in {
        // Walk up from the test JVM's working directory until we find the worktree root (the
        // first ancestor containing both `build.sbt` and a `kyo-schema/shared/src/main/scala`
        // tree); resolve the macro source paths relative to that root. sbt sets the forked test
        // JVM's `user.dir` to the project's baseDirectory (`kyo-schema/jvm`), not the worktree
        // root, so a bare relative path would miss the source tree.
        val workTreeRoot: java.io.File =
            @scala.annotation.tailrec
            def loop(dir: java.io.File): java.io.File =
                val marker = new java.io.File(dir, "kyo-schema/shared/src/main/scala/kyo/internal")
                if marker.isDirectory then dir
                else
                    val parent = dir.getParentFile
                    if parent == null then fail(s"could not locate worktree root from ${java.lang.System.getProperty("user.dir")}")
                    else loop(parent)
                end if
            end loop
            loop(new java.io.File(java.lang.System.getProperty("user.dir")))
        end workTreeRoot
        val files: List[String] = List(
            "SerializationMacro.scala",
            "FocusMacro.scala",
            "MacroUtils.scala",
            "ExpandMacro.scala",
            "SchemaDerivedMacro.scala"
        )
        val pattern = "\\bBox\\b".r
        val matches: List[(String, Int)] = files.flatMap { name =>
            val path = new java.io.File(workTreeRoot, s"kyo-schema/shared/src/main/scala/kyo/internal/$name").getCanonicalPath
            val src  = scala.io.Source.fromFile(path)
            try
                src.getLines().zipWithIndex.collect {
                    case (line, idx) if pattern.findFirstIn(line).isDefined => (path, idx + 1)
                }.toList
            finally src.close()
            end try
        }
        assert(matches.isEmpty, s"expected zero `Box` matches in macro sources but found: $matches")
    }

end SchemaCustomContainerNestedTest
