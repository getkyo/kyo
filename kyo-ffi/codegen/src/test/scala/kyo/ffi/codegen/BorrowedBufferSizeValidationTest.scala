package kyo.ffi.codegen

import kyo.ffi.codegen.model.*

/** Validation for struct-field `Buffer[A]` size inference. Mirrors the top-level `Borrowed[Buffer[A]]` rule in `FfiInspector`:
  * exactly one `Int`/`Long` sibling field in the enclosing struct is required to infer the buffer extent. Zero or multiple candidates must
  * be rejected by `TypeValidator`.
  */
class BorrowedBufferSizeValidationTest extends kyo.test.Test[Any]:

    private def mkTrait(structs: List[StructSpec]): TraitSpec =
        TraitSpec(
            fqcn = "test.Bindings",
            simpleName = "Bindings",
            packageName = "test",
            library = "bindings",
            methods = Nil,
            structs = structs,
            companion = None
        )

    "valid: struct with one Buffer[Byte] and one Int sibling, validator accepts, inferred size is the Int field" in {
        val s = StructSpec(
            "test.Foo",
            "Foo",
            List(
                StructField("buf", TypeRef.BufferT(TypeRef.ByteT)),
                StructField("len", TypeRef.IntT)
            ),
            packed = false
        )
        assert(TypeValidator.validate(mkTrait(List(s))).isEmpty)
        assert(s.inferredBufferSizeField.map(_.name) == Some("len"))
        assert(s.inferredBufferSizeField.map(_.tpe) == Some(TypeRef.IntT))
    }

    "valid: struct with one Buffer[Byte] and one Long sibling, validator accepts, inferred size is the Long field" in {
        val s = StructSpec(
            "test.Foo",
            "Foo",
            List(
                StructField("buf", TypeRef.BufferT(TypeRef.ByteT)),
                StructField("len", TypeRef.LongT)
            ),
            packed = false
        )
        assert(TypeValidator.validate(mkTrait(List(s))).isEmpty)
        assert(s.inferredBufferSizeField.map(_.name) == Some("len"))
        assert(s.inferredBufferSizeField.map(_.tpe) == Some(TypeRef.LongT))
    }

    "invalid: struct with Buffer but no Int/Long sibling, validator emits missingBorrowedBufferSize" in {
        val s = StructSpec(
            "test.Foo",
            "Foo",
            List(StructField("buf", TypeRef.BufferT(TypeRef.ByteT))),
            packed = false
        )
        val errs = TypeValidator.validate(mkTrait(List(s)))
        assert(errs.exists(e =>
            e.contains("test.Foo") &&
                e.contains("'buf'") &&
                e.contains("no Int or Long sibling field")
        ) == true)
        assert(s.inferredBufferSizeField == None)
    }

    "invalid: struct with Buffer and two Int/Long siblings, validator emits ambiguousBorrowedBufferSize listing candidates" in {
        val s = StructSpec(
            "test.Foo",
            "Foo",
            List(
                StructField("buf", TypeRef.BufferT(TypeRef.ByteT)),
                StructField("len", TypeRef.IntT),
                StructField("other", TypeRef.LongT)
            ),
            packed = false
        )
        val errs = TypeValidator.validate(mkTrait(List(s)))
        assert(errs.exists(e =>
            e.contains("test.Foo") &&
                e.contains("'buf'") &&
                e.contains("size inference is ambiguous") &&
                e.contains("len") &&
                e.contains("other")
        ) == true)
        assert(s.inferredBufferSizeField == None)
    }

end BorrowedBufferSizeValidationTest
