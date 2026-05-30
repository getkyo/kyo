# Phase 11 Decisions

## INV-008 / M8: Parse 6 missing classfile attributes

### Attributes added

**BootstrapMethods (JVMS §4.7.23)**
Class-level attribute storing bootstrap method handles for `invokedynamic` (lambda capture). Parsed
in `readClassAttrList` via a new `readBootstrapMethodsData(view)` helper. Stores each entry as
`Chunk[Int]` where entry[0] is the methodRef pool index and entry[1..n] are the argument indices.
Exposed as `JavaMetadata.bootstrapMethods: Chunk[Chunk[Int]]`.

**NestHost (JVMS §4.7.28)**
Class-level attribute present on inner/nested classes, naming the outermost nest host. Parsed as
a single `u2 host_class_index`. Resolved to an unresolved Symbol via `resolveOptionalClassSymbol`.
Exposed as `JavaMetadata.nestHost: Maybe[Symbol]`.

**NestMembers (JVMS §4.7.29)**
Class-level attribute present on the nest host class, listing all member classes. Parsed as
`u2 count` + `count * u2 class_indices`. Resolved to a `Chunk[Symbol]` via `resolveClassSymbolList`.
Exposed as `JavaMetadata.nestMembers: Chunk[Symbol]`.

**PermittedSubclasses (JVMS §4.7.31)**
Class-level attribute present on sealed classes (JVM 17+). Parsed as `u2 count` + `count * u2
class_indices`. Resolved to `Chunk[Symbol]`. Wired onto `Symbol._permittedSubclasses` (a new
`SingleAssign[Maybe[Chunk[Symbol]]]` slot) when the chunk is non-empty. Exposed via the new
`Symbol.permittedSubclasses(using AllowUnsafe): Maybe[Chunk[Symbol]]` accessor.

**MethodParameters (JVMS §4.7.24)**
Method-level attribute storing formal parameter names and access flags. Parsed in
`readMemberAttributes` via a new `readMethodParameterNames(view, pool)` helper. The helper reads
`u1 parameters_count` then for each entry reads `u2 name_index, u2 access_flags` (flags discarded;
unnamed params stored as empty string). `MemberInfo.paramNames: Chunk[String]` carries the result.
In `buildOneMemberSymbol`, this is packaged as `paramNames: Chunk[(Name, Chunk[Name])]` on
`JavaMetadata` with a single-element entry keyed by the method name.

**RuntimeVisibleTypeAnnotations / RuntimeInvisibleTypeAnnotations (JVMS §4.7.20 / §4.7.21)**
Class-level type-annotation attributes. Attribute body captured as raw bytes in `readClassAttrList`.
Decoded in `buildResult` via a new `decodeTypeAnnotations` helper chain. The key challenge is the
variable-length `target_info` union preceding each annotation. `skipTypeAnnotationTargetAndPath`
handles all 16 target_type codes from JVMS Table 4.7.20-A; unknown codes fall through with no
target_info bytes consumed (graceful degradation). After skipping target_info and type_path,
`JavaAnnotationUnpickler.readOneAnnotation` decodes the standard annotation body. Results from
visible and invisible attributes are concatenated into `JavaMetadata.runtimeTypeAnnotations`.
`readOneAnnotation` was promoted from `private` to `private[classfile]` to allow access from
`ClassfileUnpickler` in the same package.

### Architecture decisions

- `ClassAttributes` gained 6 new fields instead of inline parsing, preserving the existing
  parse-first-accumulate-then-build pattern.
- `MemberInfo` gained `paramNames: Chunk[String]` without default value, updating the single
  constructor call site in `readOneMemberInfo`.
- `resolveOptionalClassSymbol` and `resolveClassSymbolList` are new private helpers in
  `ClassfileUnpickler` reusing `makeUnresolvedSymbol`.
- The `decodeTypeAnnotations(Maybe[Array[Byte]], ...)` overload dispatches on Present/Absent,
  keeping the `buildResult` chain concise.
- `buildResult` now chains 8 levels deep due to the additional `map` calls for the new attributes.
  The structure is correct but the lambda nesting is deep; this is a stylistic consequence of the
  CPS-recursive approach used throughout `ClassfileUnpickler`.

### Convention sweep
- No em-dashes introduced.
- No `Option`/`Some`; uses `Maybe`/`Present`/`Absent` throughout.
- No semicolons in chains.
- No `asInstanceOf`.
- No default params in new `MemberInfo` or `ClassAttributes` fields.
- `AllowUnsafe` embraced only at the `_permittedSubclasses.set` call with a comment.
- No `Frame.internal`.
- No `java.util.concurrent` imports.

### Tests added (ClassfileReaderTest.scala)

| Test | Attribute | Class used | Pins |
|------|-----------|------------|------|
| 13 | BootstrapMethods | `java/util/function/Function.class` | INV-008 |
| 14 | NestHost | `java/util/HashMap$Node.class` | INV-008 |
| 15 | NestMembers | `java/util/HashMap.class` | INV-008 |
| 16 | PermittedSubclasses | `java/lang/constant/ClassDesc.class` | INV-008 |
| 17 | MethodParameters | `java/lang/module/ModuleDescriptor$Requires$Modifier.class` | INV-008 |
| 18 | RuntimeTypeAnnotations | synthetic classfile (byte array) | INV-008 |

Tests 13-17 use `taggedAs jvmOnly` (JVM classloader access required).
Test 18 builds a minimal synthetic classfile in-memory with a single
`RuntimeVisibleTypeAnnotations` entry containing a `Ljava/lang/Deprecated;` type annotation;
also tagged `jvmOnly` because `ClassfileReaderTest` uses `TestResourceLoader` infrastructure.

### Cross-platform status
All three platforms (JVM, JS, Native) compile cleanly. JS and Native do not run the `jvmOnly`
tests so the `java.io.ByteArrayOutputStream` / `java.io.DataOutputStream` usage in Test 18 is
JVM-gated.

### HEAD at completion
`7cd758d64` - kyo-tasty Phase 10: log unknown TASTy type tags (unchanged, no commit per HARD RULE).

---

## Phase 11 verify-FAIL remediation (2026-05-30)

### Dev-tag comments (class-A gate violation)

Three `// Phase 11: ...` comments in `ClassfileUnpickler.scala` fired the dev-tag gate.
Rewrote to describe what the code does:

- Line 276 (was): `// Phase 11: MethodParameters attribute (method-level only; empty for fields)`
  (now): `// MethodParameters attribute (method-level only; empty for fields)`
- Line 465 (was): `// Phase 11: additional attribute payloads`
  (now): `// Additional attribute payloads: BootstrapMethods, NestHost, NestMembers, PermittedSubclasses, type annotations`
- Line 1735 (was): `// Phase 11 attribute helpers` section banner
  (now): `// Attribute helpers: BootstrapMethods, NestHost, NestMembers, type annotations`

### Default-param violation (feedback_no_default_params_internal)

`JavaMetadata` in `Tasty.scala` had 5 fields with `= default` literals:
`bootstrapMethods`, `nestHost`, `nestMembers`, `paramNames`, `runtimeTypeAnnotations`.

Fix A applied: removed all 5 defaults. Two callsites updated:
- `ClassfileUnpickler.scala` line 1090 (`buildResult`): already had all 10 fields explicit, no change needed.
- `ClassfileUnpickler.scala` line 1596 (`buildOneMemberSymbol`): added 4 missing fields explicitly
  (`bootstrapMethods = Chunk.empty`, `nestHost = Absent`, `nestMembers = Chunk.empty`,
  `runtimeTypeAnnotations = Chunk.empty`).

### Verification result
- `grep -nP '// (DEV: )?Phase \d' ClassfileUnpickler.scala` -> 0 hits
- `grep -nE '...(default fields)...' Tasty.scala` -> 0 hits
- `kyo-tasty/Test/compile` -> SUCCESS
- `testOnly kyo.ClassfileReaderTest` -> 18/18 passed
- JS `Test/compile` -> SUCCESS
- Native `Test/compile` -> SUCCESS
- HEAD: `7cd758d64` (unchanged, no commit per HARD RULE)
