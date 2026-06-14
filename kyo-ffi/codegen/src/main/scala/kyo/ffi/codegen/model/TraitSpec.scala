package kyo.ffi.codegen.model

/** Normalized description of one `Ffi`-extending trait, produced by the extractor, consumed by emitters.
  *
  * @param fqcn
  *   fully-qualified binding trait name, e.g. `"kyo.example.TcpBindings"`.
  * @param simpleName
  *   unqualified trait name, e.g. `"TcpBindings"`.
  * @param packageName
  *   enclosing package for emitted impl output, e.g. `"kyo.example"`.
  * @param library
  *   library identifier used by `NativeLoader`.
  * @param methods
  *   one [[MethodSpec]] per abstract method.
  * @param structs
  *   every [[StructSpec]] referenced by this trait (params, fields, returns).
  * @param companion
  *   the companion's [[ConfigSpec]] if present.
  * @param nativeBundled
  *   when `true`, the library's C is compiled into the Scala Native binary (under `resources/scala-native`), so the Native emitter must
  *   NOT emit `@link(library)` for this binding. Mirrors `Ffi.Config.nativeBundled`. Has no effect on JVM/JS emission.
  */
final case class TraitSpec(
    fqcn: String,
    simpleName: String,
    packageName: String,
    library: String,
    methods: List[MethodSpec],
    structs: List[StructSpec],
    companion: Option[ConfigSpec],
    headers: Seq[String] = Seq.empty,
    enums: List[EnumSpec] = Nil,
    nativeBundled: Boolean = false
)

/** Companion configuration extracted from an `object` extending `Ffi.Config`. Field semantics mirror `Ffi.Config`.
  *
  * `scratchSize` carries the optional per-binding scratch-block override from `Ffi.Config.scratchSize`. `None` means "use the global
  * default", the JVM emitter emits `Scratch.currentFor(traitFqn, Scratch.configuredSize)`. `Some(n)` means the emitter emits
  * `Scratch.currentFor(traitFqn, n)` so the per-thread block is sized specifically for this binding.
  */
final case class ConfigSpec(
    library: String,
    symbolPrefix: String,
    symbols: Map[String, String],
    packedStructs: Set[String],
    scratchSize: Option[Int] = None,
    checkedBorrows: Boolean = false,
    headers: Seq[String] = Seq.empty,
    nativeBundled: Boolean = false
)
