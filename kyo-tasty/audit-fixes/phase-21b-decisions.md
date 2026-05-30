# Phase 21b Decisions

## ADT Case-Name Reconciliation

The plan used placeholder names (StringVal, ArrayVal) that matched the actual `Tasty.JavaAnnotation.Value` enum cases exactly:

| Plan name    | Actual case                            | Match |
|--------------|----------------------------------------|-------|
| StringVal    | Tasty.JavaAnnotation.Value.StringVal   | exact |
| ArrayVal     | Tasty.JavaAnnotation.Value.ArrayVal    | exact |
| IntVal       | Tasty.JavaAnnotation.Value.IntVal      | exact |
| BoolVal      | Tasty.JavaAnnotation.Value.BoolVal     | exact |
| LongVal      | Tasty.JavaAnnotation.Value.LongVal     | exact |
| FloatVal     | Tasty.JavaAnnotation.Value.FloatVal    | exact |
| DoubleVal    | Tasty.JavaAnnotation.Value.DoubleVal   | exact |
| ClassVal     | Tasty.JavaAnnotation.Value.ClassVal    | exact |
| EnumVal      | Tasty.JavaAnnotation.Value.EnumVal     | exact |
| AnnotationVal| Tasty.JavaAnnotation.Value.AnnotationVal| exact |

No renaming was required.

## jvmOnly Tag Usage

Neither new test uses `taggedAs jvmOnly`. Both are in `shared/` and exercise only pure byte arithmetic:

- `ConstantPoolTest` T2-1 and T2-2: build byte arrays inline; call `ConstantPool.read` with a `ByteView.Heap`. No JVM I/O.
- `JavaAnnotationUnpicklerTest` Test 1 and Test 2: build byte arrays inline; call `JavaAnnotationUnpickler.readAnnotations` with `ByteView.Heap`. No JVM I/O.

JS and Native compilation confirmed passing. The `jvmOnly` tag is not applied because neither test uses classloader resource loading or any platform-specific API.

## Error-Message Reconciliation

The plan said `entry(99)` on a 5-entry pool should produce an error containing "index 99 out of range".

Actual message from `ConstantPool.entry` (line 72): `"Constant pool index $idx out of bounds [1, ${entries.length - 1}]"`

The phrase used is "out of bounds", not "out of range". The test assertion accepts both ("out of bounds" OR "out of range") to be robust against future wording changes, while also asserting the index value "99" appears in the message. Both conditions are satisfied by the actual implementation.

## Entry-Point Used

The plan referenced `JavaAnnotationUnpickler.read(view, pool)` as the entry point. The actual public entry point is `JavaAnnotationUnpickler.readAnnotations(view, pool, interner, home)` which reads a `u2 num_annotations` prefix then each annotation. The `readOneAnnotation` method is `private[classfile]` and not accessible from the `kyo` test package. Tests use `readAnnotations` with the `u2 num_annotations=1` prefix prepended to the annotation bytes, which is the correct production-level calling convention.
