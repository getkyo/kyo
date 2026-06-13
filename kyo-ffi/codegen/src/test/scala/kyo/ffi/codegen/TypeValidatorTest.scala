package kyo.ffi.codegen

import kyo.ffi.codegen.model.*

class TypeValidatorTest extends kyo.test.Test[Any]:

    private def mkTrait(methods: List[MethodSpec], structs: List[StructSpec] = Nil): TraitSpec =
        TraitSpec(
            fqcn = "test.Bindings",
            simpleName = "Bindings",
            packageName = "test",
            library = "bindings",
            methods = methods,
            structs = structs,
            companion = None
        )

    private def mkMethod(
        name: String,
        params: List[ParamSpec],
        ret: ReturnShape,
        kind: CallbackKind = CallbackKind.None
    ): MethodSpec =
        MethodSpec(
            scalaName = name,
            cSymbol = name,
            params = params,
            returnShape = ret,
            blocking = false,
            hasArrayParam = params.exists(_.tpe.isInstanceOf[TypeRef.ArrayT]),
            callbackKind = kind
        )

    "a plain primitive method is valid" in {
        val t = mkTrait(List(mkMethod("foo", List(ParamSpec("x", TypeRef.IntT)), ReturnShape.Primitive(TypeRef.IntT))))
        assert(TypeValidator.validate(t).isEmpty)
    }

    "Retained callback method must have exactly one Ffi.Guard param and a function param" in {
        val t = mkTrait(List(mkMethod(
            "bad",
            List(ParamSpec("x", TypeRef.IntT)),
            ReturnShape.Primitive(TypeRef.IntT),
            CallbackKind.Retained
        )))
        val errs = TypeValidator.validate(t)
        assert(errs.exists(_.contains("exactly one Ffi.Guard")) == true)
        assert(errs.exists(_.contains("no function parameter")) == true)
    }

    "Transient callback method rejects an Ffi.Guard param" in {
        val t = mkTrait(List(mkMethod(
            "bad",
            List(ParamSpec("g", TypeRef.GuardT), ParamSpec("cb", TypeRef.FnPtrT(Nil, TypeRef.UnitT))),
            ReturnShape.Primitive(TypeRef.IntT),
            CallbackKind.Transient
        )))
        val errs = TypeValidator.validate(t)
        assert(errs.exists(_.contains("must not have an Ffi.Guard")) == true)
    }

    "Unknown struct reference in params is rejected" in {
        val t = mkTrait(List(mkMethod(
            "bad",
            List(ParamSpec("s", TypeRef.StructT("unknown.X"))),
            ReturnShape.Primitive(TypeRef.IntT)
        )))
        val errs = TypeValidator.validate(t)
        assert(errs.exists(_.contains("unknown struct 'unknown.X'")) == true)
    }

    "MultiValue return with <2 fields is rejected" in {
        val struct = StructSpec("test.R", "R", List(StructField("only", TypeRef.IntT)), packed = false)
        val t = mkTrait(
            List(mkMethod("bad", Nil, ReturnShape.MultiValue(struct))),
            List(struct)
        )
        val errs = TypeValidator.validate(t)
        assert(errs.exists(_.contains("multi-value return")) == true)
    }

    "Ffi.Guard as return type is rejected" in {
        val t    = mkTrait(List(mkMethod("bad", Nil, ReturnShape.Primitive(TypeRef.GuardT))))
        val errs = TypeValidator.validate(t)
        assert(errs.exists(_.contains("Ffi.Guard is not a valid return type")) == true)
    }

    "Self-referential struct is rejected" in {
        val self = StructSpec("test.S", "S", List(StructField("next", TypeRef.StructT("test.S"))), packed = false)
        val t    = mkTrait(Nil, List(self))
        val errs = TypeValidator.validate(t)
        assert(errs.exists(_.contains("transitively contains itself")) == true)
    }

    "Method with Ffi.Guard but no callback should have CallbackKind=Retained" in {
        val t = mkTrait(List(mkMethod(
            "bad",
            List(ParamSpec("g", TypeRef.GuardT)),
            ReturnShape.Primitive(TypeRef.IntT),
            CallbackKind.None
        )))
        val errs = TypeValidator.validate(t)
        assert(errs.exists(_.contains("no callback")) == true)
    }

    "Well-formed retained callback passes" in {
        val t = mkTrait(List(mkMethod(
            "ok",
            List(ParamSpec("g", TypeRef.GuardT), ParamSpec("cb", TypeRef.FnPtrT(Nil, TypeRef.UnitT))),
            ReturnShape.Primitive(TypeRef.IntT),
            CallbackKind.Retained
        )))
        assert(TypeValidator.validate(t).isEmpty)
    }

    "Well-formed transient callback passes" in {
        val t = mkTrait(List(mkMethod(
            "ok",
            List(ParamSpec("cb", TypeRef.FnPtrT(List(TypeRef.IntT), TypeRef.UnitT))),
            ReturnShape.Void,
            CallbackKind.Transient
        )))
        assert(TypeValidator.validate(t).isEmpty)
    }

    "MultiValue return whose first field is non-primitive is rejected" in {
        // First field is a String, not a primitive, so it cannot be the C return.
        val struct = StructSpec(
            "test.Bad",
            "Bad",
            List(StructField("name", TypeRef.StringT), StructField("count", TypeRef.IntT)),
            packed = false
        )
        val t = mkTrait(
            List(mkMethod("bad", Nil, ReturnShape.MultiValue(struct))),
            List(struct)
        )
        val errs = TypeValidator.validate(t)
        assert(errs.exists(e => e.contains("non-primitive first field") && e.contains("Bad")) == true)
    }

    "Well-formed multi-value return with >=2 fields passes" in {
        val struct = StructSpec(
            "test.R",
            "R",
            List(StructField("a", TypeRef.IntT), StructField("b", TypeRef.LongT)),
            packed = false
        )
        val t = mkTrait(List(mkMethod("ok", Nil, ReturnShape.MultiValue(struct))), List(struct))
        assert(TypeValidator.validate(t).isEmpty)
    }

    // -------------------------------------------------------------------------
    // Multi-hop circular-reference detection
    // -------------------------------------------------------------------------

    "Two-hop struct cycle (A -> B -> A) is rejected" in {
        val a    = StructSpec("test.A", "A", List(StructField("b", TypeRef.StructT("test.B"))), packed = false)
        val b    = StructSpec("test.B", "B", List(StructField("a", TypeRef.StructT("test.A"))), packed = false)
        val t    = mkTrait(Nil, List(a, b))
        val errs = TypeValidator.validate(t)
        assert(errs.exists(_.contains("test.A")) == true)
        assert(errs.exists(_.contains("test.B")) == true)
    }

    "Three-hop struct cycle (A -> B -> C -> A) is rejected" in {
        val a    = StructSpec("test.A", "A", List(StructField("b", TypeRef.StructT("test.B"))), packed = false)
        val b    = StructSpec("test.B", "B", List(StructField("c", TypeRef.StructT("test.C"))), packed = false)
        val c    = StructSpec("test.C", "C", List(StructField("a", TypeRef.StructT("test.A"))), packed = false)
        val t    = mkTrait(Nil, List(a, b, c))
        val errs = TypeValidator.validate(t)
        assert(errs.count(_.contains("transitively contains itself")) == 3)
    }

    "DAG with multiple paths to the same struct is NOT rejected (no cycle)" in {
        // Root R refers to L and M, both of which refer to Leaf, two distinct paths R -> L -> Leaf and R -> M -> Leaf.
        val leaf = StructSpec("test.Leaf", "Leaf", List(StructField("x", TypeRef.IntT)), packed = false)
        val l    = StructSpec("test.L", "L", List(StructField("leaf", TypeRef.StructT("test.Leaf"))), packed = false)
        val m    = StructSpec("test.M", "M", List(StructField("leaf", TypeRef.StructT("test.Leaf"))), packed = false)
        val r = StructSpec(
            "test.R",
            "R",
            List(StructField("l", TypeRef.StructT("test.L")), StructField("m", TypeRef.StructT("test.M"))),
            packed = false
        )
        val t = mkTrait(Nil, List(r, l, m, leaf))
        assert(TypeValidator.validate(t).isEmpty)
    }

    "Disjoint cycle among unrelated structs does not falsely reject a third struct" in {
        // X and Y cycle; Z is independent and well-formed.
        val x    = StructSpec("test.X", "X", List(StructField("y", TypeRef.StructT("test.Y"))), packed = false)
        val y    = StructSpec("test.Y", "Y", List(StructField("x", TypeRef.StructT("test.X"))), packed = false)
        val z    = StructSpec("test.Z", "Z", List(StructField("v", TypeRef.IntT)), packed = false)
        val t    = mkTrait(Nil, List(x, y, z))
        val errs = TypeValidator.validate(t)
        assert(errs.exists(_.contains("test.X")) == true)
        assert(errs.exists(_.contains("test.Y")) == true)
        assert(errs.exists(_.contains("test.Z")) == false)
    }

    // -------------------------------------------------------------------------
    // Array[Boolean] rejection
    // -------------------------------------------------------------------------

    "Array[Boolean] parameter is rejected with a direction-to-use-Array[Byte] message" in {
        val t = mkTrait(List(mkMethod(
            "bad",
            List(ParamSpec("flags", TypeRef.ArrayT(TypeRef.BooleanT))),
            ReturnShape.Primitive(TypeRef.IntT)
        )))
        val errs = TypeValidator.validate(t)
        assert(errs.exists(e => e.contains("Array[Boolean]") && e.contains("not supported")) == true)
        assert(errs.exists(e => e.contains("Array[Byte]") && e.contains("0/1 convention")) == true)
    }

    "Array[Byte] parameter remains valid (opt-out via Array[Byte] is the documented workaround)" in {
        val t = mkTrait(List(mkMethod(
            "ok",
            List(ParamSpec("bytes", TypeRef.ArrayT(TypeRef.ByteT))),
            ReturnShape.Primitive(TypeRef.IntT)
        )))
        assert(TypeValidator.validate(t).isEmpty)
    }

    // -------------------------------------------------------------------------
    // Validator preemption, more-specific error messages
    // -------------------------------------------------------------------------

    "MultiValue with a Buffer first field is rejected and identifies the case class + type" in {
        val struct = StructSpec(
            "test.BadBuf",
            "BadBuf",
            List(
                StructField("data", TypeRef.BufferT(TypeRef.ByteT)),
                StructField("len", TypeRef.IntT)
            ),
            packed = false
        )
        val t = mkTrait(
            List(mkMethod("bad", Nil, ReturnShape.MultiValue(struct))),
            List(struct)
        )
        val errs = TypeValidator.validate(t)
        assert(errs.exists(e =>
            e.contains("non-primitive first field") &&
                e.contains("BadBuf") &&
                e.contains("BufferT")
        ) == true)
    }

    "MultiValue with a nested-struct first field is rejected" in {
        val inner = StructSpec(
            "test.Inner",
            "Inner",
            List(StructField("x", TypeRef.IntT)),
            packed = false
        )
        val struct = StructSpec(
            "test.BadNested",
            "BadNested",
            List(
                StructField("head", TypeRef.StructT("test.Inner")),
                StructField("count", TypeRef.IntT)
            ),
            packed = false
        )
        val t = mkTrait(
            List(mkMethod("bad", Nil, ReturnShape.MultiValue(struct))),
            List(struct, inner)
        )
        val errs = TypeValidator.validate(t)
        assert(errs.exists(e => e.contains("non-primitive first field") && e.contains("BadNested")) == true)
    }

    // -------------------------------------------------------------------------
    // Error ordering
    // -------------------------------------------------------------------------

    "errors are returned in deterministic order regardless of method declaration order" in {
        // Build the same trait twice with methods declared in different order; the validator output must be identical.
        val struct = StructSpec(
            "test.MV",
            "MV",
            List(StructField("name", TypeRef.StringT), StructField("count", TypeRef.IntT)),
            packed = false
        )
        val mBadGuard = mkMethod(
            "alpha",
            List(ParamSpec("g", TypeRef.GuardT)),
            ReturnShape.Primitive(TypeRef.IntT),
            CallbackKind.None
        )
        val mBadArray = mkMethod(
            "bravo",
            List(ParamSpec("flags", TypeRef.ArrayT(TypeRef.BooleanT))),
            ReturnShape.Primitive(TypeRef.IntT)
        )
        val mBadMv = mkMethod(
            "charlie",
            Nil,
            ReturnShape.MultiValue(struct)
        )
        val mGuardReturn = mkMethod(
            "delta",
            Nil,
            ReturnShape.Primitive(TypeRef.GuardT)
        )

        val t1 = mkTrait(List(mBadGuard, mBadArray, mBadMv, mGuardReturn), List(struct))
        val t2 = mkTrait(List(mGuardReturn, mBadMv, mBadArray, mBadGuard), List(struct))
        val e1 = TypeValidator.validate(t1)
        val e2 = TypeValidator.validate(t2)
        assert(e1 == e2)
        assert(e1.size >= 4)
    }

    "errors with critical severity (Guard misuse) come before syntactic errors" in {
        // Guard-as-return-type is a structural/critical error; Array[Boolean] is a syntactic/use-site error.
        // Critical severity must precede syntactic in the ordered output.
        val mGuardReturn = mkMethod(
            "alpha",
            Nil,
            ReturnShape.Primitive(TypeRef.GuardT)
        )
        val mBadArray = mkMethod(
            "bravo",
            List(ParamSpec("flags", TypeRef.ArrayT(TypeRef.BooleanT))),
            ReturnShape.Primitive(TypeRef.IntT)
        )
        val t        = mkTrait(List(mBadArray, mGuardReturn))
        val errs     = TypeValidator.validate(t)
        val guardIdx = errs.indexWhere(_.contains("Ffi.Guard is not a valid return type"))
        val arrayIdx = errs.indexWhere(_.contains("Array[Boolean]"))
        assert(guardIdx >= 0)
        assert(arrayIdx >= 0)
        assert(guardIdx < arrayIdx)
    }

    // -------------------------------------------------------------------------
    // Borrowed top-level String / Buffer returns
    // -------------------------------------------------------------------------

    "Borrowed[String] return passes validation" in {
        val t = mkTrait(List(mkMethod(
            "getenv",
            List(ParamSpec("name", TypeRef.StringT)),
            ReturnShape.BorrowedString(65536)
        )))
        assert(TypeValidator.validate(t).isEmpty)
    }

    "Borrowed[Buffer[A]] return passes validation when the size param exists" in {
        val t = mkTrait(List(mkMethod(
            "mallocChunk",
            List(ParamSpec("n", TypeRef.LongT)),
            ReturnShape.BorrowedBuffer(TypeRef.ByteT, "n")
        )))
        assert(TypeValidator.validate(t).isEmpty)
    }

    "Borrowed[Buffer[A]] return is rejected when the inferred size param does not exist" in {
        val t = mkTrait(List(mkMethod(
            "bad",
            List(ParamSpec("x", TypeRef.LongT)),
            ReturnShape.BorrowedBuffer(TypeRef.ByteT, "missing")
        )))
        val errs = TypeValidator.validate(t)
        assert(errs.exists(e => e.contains("inferred size parameter") && e.contains("missing")) == true)
    }

    "MultiValue with a Guard first field is rejected" in {
        // Guard is non-primitive (it's an opaque handle), should trip the same rule even though
        // Guard would also be caught by other rules elsewhere.
        val struct = StructSpec(
            "test.BadGuard",
            "BadGuard",
            List(StructField("g", TypeRef.GuardT), StructField("count", TypeRef.IntT)),
            packed = false
        )
        val t = mkTrait(
            List(mkMethod("bad", Nil, ReturnShape.MultiValue(struct))),
            List(struct)
        )
        val errs = TypeValidator.validate(t)
        assert(errs.exists(e => e.contains("non-primitive first field") && e.contains("BadGuard")) == true)
    }
    "accept HandleT in param position" in {
        val t = mkTrait(
            List(mkMethod("use", List(ParamSpec("h", TypeRef.HandleT("test.Handle"))), ReturnShape.Primitive(TypeRef.IntT)))
        )
        assert(TypeValidator.validate(t).isEmpty)
    }

    "accept HandleReturn" in {
        val t = mkTrait(
            List(mkMethod("create", Nil, ReturnShape.HandleReturn("test.Handle")))
        )
        assert(TypeValidator.validate(t).isEmpty)
    }
    // -------------------------------------------------------------------------
    // Enum validation
    // -------------------------------------------------------------------------

    "accept EnumT in param position when FQCN is known" in {
        val enumSpec = EnumSpec("test.Color", "Color")
        val t = mkTrait(
            List(mkMethod("setColor", List(ParamSpec("c", TypeRef.EnumT("test.Color"))), ReturnShape.Primitive(TypeRef.IntT)))
        ).copy(enums = List(enumSpec))
        assert(TypeValidator.validate(t).isEmpty)
    }

    "accept EnumReturn when FQCN is known" in {
        val enumSpec = EnumSpec("test.Color", "Color")
        val t = mkTrait(
            List(mkMethod("getColor", List(ParamSpec("index", TypeRef.IntT)), ReturnShape.EnumReturn("test.Color")))
        ).copy(enums = List(enumSpec))
        assert(TypeValidator.validate(t).isEmpty)
    }

    "reject unknown enum FQCN in param" in {
        val t = mkTrait(
            List(mkMethod("setColor", List(ParamSpec("c", TypeRef.EnumT("unknown.Color"))), ReturnShape.Primitive(TypeRef.IntT)))
        )
        val errs = TypeValidator.validate(t)
        assert(errs.exists(_.contains("unknown enum type 'unknown.Color'")) == true)
    }

end TypeValidatorTest
