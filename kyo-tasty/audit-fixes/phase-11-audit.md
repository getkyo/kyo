# Phase 11 Audit — M8/INV-008 missing classfile attributes

**HEAD:** `5c6f2b065`
**Path:** `/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/kyo-tasty/audit-fixes/phase-11-audit.md`

## Verdict per category

### 1. JVMS byte-level spec correctness — PASS

Cross-checked all 6 parsers against JVMS §4.7:
- **BootstrapMethods §4.7.23**: `u2 n` + per-entry `u2 ref, u2 argc, u2[argc] args` — matches lines 767-783.
- **NestHost §4.7.28**: `u2 host_class_index` — single readU2 at line 1009.
- **NestMembers §4.7.29** / **PermittedSubclasses §4.7.31**: `u2 n, u2[n] classes` — both arms loop readU2 n times.
- **MethodParameters §4.7.24**: `u1 parameters_count` (correct; not u2), per-entry `u2 name_index, u2 access_flags`, `name_index==0` → empty string per spec.
- **RuntimeVisibleTypeAnnotations §4.7.20** / **Invisible §4.7.21**: `u2 num_annotations`, target_info switch covers Table 4.7.20-A codes 0x00-0x17 and 0x40-0x4B; unknown codes fall through with zero target_info bytes (graceful), then `u1 path_length` + `2*path_len` skip. Annotation body delegated to existing `JavaAnnotationUnpickler.readOneAnnotation`.

### 2. JavaAnnotationUnpickler visibility change — PASS (necessary)

`private → private[classfile]` is the minimal scope widening. An "internal proxy" inside JavaAnnotationUnpickler would either add a no-op forwarder (pure ceremony) or duplicate the byte-level parser (worse). `private[classfile]` keeps the symbol invisible to `kyo.*` and `kyo.internal.tasty.*` outside the classfile package. Trade-off accepted.

### 3. SingleAssign pattern consistency — PASS

`_permittedSubclasses: SingleAssign[Maybe[Chunk[Symbol]]]` (Tasty.scala:542) mirrors `_parents`, `_typeParams`, `_declarations`, `_position`, `_scaladoc` slot pattern. Naming, `private[kyo]` visibility, and lazy-init via `new SingleAssign` are uniform.

### 4. Writer-side `import danger` — PASS (orchestration §839 case 3)

ClassfileUnpickler.scala:1113-1114 uses `import AllowUnsafe.embrace.danger` immediately before the single `_permittedSubclasses.set(...)` call. Matches the established sibling pattern at line 74 in `mergeResults` (sets `_parents`, `_typeParams`, `_declarations`, `_declaredType`). Pickler-side orchestration; no refactor warranted.

### 5. Default-param remediation — PASS

`grep JavaMetadata(` finds exactly 2 constructor callsites: ClassfileUnpickler.scala:1090 (class metadata) and :1596 (member metadata). Both pass all 10 fields explicitly with named args. JavaMetadata case class definition (Tasty.scala:246-256) carries zero `=` defaults.

## NOTE for Phase 12 prep

- `Chunk[Chunk[Int]]` for `bootstrapMethods` exposes raw constant-pool indices. Phase 12 (or later) may want to resolve these to `MethodHandle`/`MethodType` symbols when the broader invokedynamic surface is implemented.
- `paramNames: Chunk[(Name, Chunk[Name])]` carries a single-entry chunk per method symbol; redundant outer wrapper, but matches the class-metadata-aggregating-method-data shape used elsewhere. Acceptable as-is.

## Overall: READY
