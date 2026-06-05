package kyo.internal.tasty.classfile

import kyo.*
import kyo.internal.tasty.symbol.LoadingSymbol
import kyo.internal.tasty.type_.TypeArena

/** Result produced by ClassfileUnpickler for a single .class file.
  *
  * @param classSymbol
  *   The loading-phase symbol for the class or interface defined by this file.
  * @param parents
  *   Unresolved parent types: super class (if any) followed by implemented interfaces. The classpath orchestrator resolves these to real
  *   symbols during the merge pass.
  * @param innerClassTable
  *   Map from inner binary name to (outer binary name, simple inner name). Outer is "" for anonymous/local classes.
  * @param symbols
  *   All field and method symbols declared by the class.
  * @param arena
  *   The TypeArena used during decoding; retained so the orchestrator can merge per-file arenas into the canonical arena.
  */
final case class ClassfileResult(
    classSymbol: LoadingSymbol.Materialising,
    parents: Chunk[Tasty.Type],
    innerClassTable: Map[String, (String, String)],
    symbols: Chunk[LoadingSymbol.Materialising],
    typeParams: Chunk[LoadingSymbol.Materialising],
    arena: TypeArena,
    memberTypes: Map[LoadingSymbol.Materialising, Tasty.Type],
    /** F-A3-001..004 fix: raw binary class names of parent types (e.g. "java/lang/Object", "java/io/Serializable").
      *
      * Parallel to `parents`: `parentBinaryNames(i)` is the binary name for `parents(i)`. Empty string entries indicate
      * non-class parent slots (should not occur for JDK classes but preserved for safety). Populated by buildResult for
      * use by ClasspathOrchestrator.finalizeMerge to resolve parent FQNs without re-parsing the classfile.
      */
    parentBinaryNames: Chunk[String] = Chunk.empty,
    /** Dotted FQNs of permitted subclasses (from PermittedSubclasses attribute), e.g. "java.lang.Double".
      *
      * Non-empty only when the class has a PermittedSubclasses attribute (Java 17+ sealed classes).
      * Used by ClasspathOrchestrator.finalizeMerge to resolve permittedSubclassIds for classfile symbols.
      */
    permittedSubclassFqns: Chunk[String] = Chunk.empty,
    /** Dotted FQN of the NestHost class, e.g. "java.lang.invoke.MethodHandles".
      *
      * Present when the classfile carries a NestHost attribute (JVMS 4.7.28). The orchestrator resolves
      * this FQN to a final Symbol after finalizeMerge and injects it into javaMetadata.nestHost.
      * Stored as a string here so no sentinel Symbol is created during decode.
      */
    nestHostFqn: Maybe[String] = Maybe.Absent,
    /** Dotted FQNs of NestMembers, e.g. "java.lang.invoke.MethodHandles$Lookup".
      *
      * Non-empty when the classfile carries a NestMembers attribute (JVMS 4.7.29). The orchestrator
      * resolves these FQNs to final Symbols after finalizeMerge and injects them into
      * javaMetadata.nestMembers.
      */
    nestMemberFqns: Chunk[String] = Chunk.empty,
    /** Enclosing-method data from the EnclosingMethod attribute (JVMS 4.7.7).
      *
      * The pair is (enclosingClassFqn, methodName). The orchestrator resolves the FQN to a final Symbol
      * after finalizeMerge and injects it into javaMetadata.enclosingMethod.
      * Stored as a string pair here so no sentinel Symbol is created during decode.
      */
    enclosingMethodData: Maybe[(String, String)] = Maybe.Absent
)
