package kyo.ffi.codegen.emitters

import kyo.ffi.codegen.model.*

object EmitterFixtures:

    def mkTrait(
        simpleName: String,
        library: String,
        methods: List[MethodSpec],
        structs: List[StructSpec] = Nil,
        packageName: String = "kyo.example",
        companion: Option[ConfigSpec] = None,
        enums: List[EnumSpec] = Nil,
        nativeBundled: Boolean = false
    ): TraitSpec =
        TraitSpec(
            fqcn = s"$packageName.$simpleName",
            simpleName = simpleName,
            packageName = packageName,
            library = library,
            methods = methods,
            structs = structs,
            companion = companion,
            enums = enums,
            nativeBundled = nativeBundled
        )

    def mkMethod(
        name: String,
        cSymbol: String,
        params: List[ParamSpec],
        ret: ReturnShape,
        blocking: Boolean = false,
        kind: CallbackKind = CallbackKind.None,
        hasVarargs: Boolean = false,
        withError: Boolean = false
    ): MethodSpec =
        MethodSpec(
            scalaName = name,
            cSymbol = cSymbol,
            params = params,
            returnShape = ret,
            blocking = blocking,
            hasArrayParam = params.exists(_.tpe.isInstanceOf[TypeRef.ArrayT]),
            callbackKind = kind,
            hasVarargs = hasVarargs,
            withError = withError
        )
end EmitterFixtures
