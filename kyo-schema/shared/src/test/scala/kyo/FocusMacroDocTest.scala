package kyo

class FocusMacroDocTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    case class City(@doc("ISO country code") country: String, name: String) derives Schema

    "a @doc field's Structure.Field.doc is Present" in {
        val schema = Schema[City]
        schema.structure match
            case Structure.Type.Product(_, _, _, fields) =>
                val countryField = fields.toList.find(_.name == "country").get
                assert(countryField.doc == Maybe.Present("ISO country code"))
            case other =>
                fail(s"expected Product structure, got $other")
        end match
    }

    "a field with no @doc has doc == Maybe.empty" in {
        val schema = Schema[City]
        schema.structure match
            case Structure.Type.Product(_, _, _, fields) =>
                val nameField = fields.toList.find(_.name == "name").get
                assert(nameField.doc == Maybe.empty)
            case other =>
                fail(s"expected Product structure, got $other")
        end match
    }

    case class MultiDoc(
        @doc("first") a: String,
        @doc("second") b: Int,
        @doc("third") c: Boolean,
        d: Double,
        e: Long
    ) derives Schema

    "derivation emits one buildProductSchema, no per-field branching" in {
        val original = MultiDoc("x", 1, true, 3.14, 99L)
        val encoded  = Json.encode(original)
        val decoded  = Json.decode[MultiDoc](encoded).getOrThrow
        assert(decoded == original)
        val schema = Schema[MultiDoc]
        schema.structure match
            case Structure.Type.Product(_, _, _, fields) =>
                assert(fields.toList.find(_.name == "a").get.doc == Maybe.Present("first"))
                assert(fields.toList.find(_.name == "b").get.doc == Maybe.Present("second"))
                assert(fields.toList.find(_.name == "c").get.doc == Maybe.Present("third"))
                assert(fields.toList.find(_.name == "d").get.doc == Maybe.empty)
                assert(fields.toList.find(_.name == "e").get.doc == Maybe.empty)
            case other =>
                fail(s"expected Product structure, got $other")
        end match
    }

    case class Node(@doc("payload") value: Int, child: Maybe[Node]) derives Schema

    "recursive type with a @doc field still derives" in {
        val tree    = Node(1, Maybe(Node(2, Maybe.empty)))
        val encoded = Json.encode(tree)
        val decoded = Json.decode[Node](encoded).getOrThrow
        assert(decoded == tree)
        Schema[Node].structure match
            case Structure.Type.Product(_, _, _, fields) =>
                val valueField = fields.toList.find(_.name == "value").get
                assert(valueField.doc == Maybe.Present("payload"))
            case other =>
                fail(s"expected Product structure, got $other")
        end match
    }

end FocusMacroDocTest
