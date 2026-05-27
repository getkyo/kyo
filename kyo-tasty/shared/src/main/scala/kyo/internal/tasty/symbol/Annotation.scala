package kyo.internal.tasty.symbol

import kyo.Chunk
import kyo.Tasty

/** Internal annotation representation.
  *
  * `annotationType` is the annotation class type (resolved in Phase 4). `argsPickle` is a raw byte slice from the TASTy AST section
  * corresponding to the ANNOTATION payload. The payload is decoded on demand in Phase 4+.
  *
  * This is a concrete internal-package-visible definition of the same structure declared at the public level in `Tasty.Annotation`. The
  * internal version is used during pass 1 to record raw annotation slices before the type is resolved.
  */
final case class Annotation(annotationType: Tasty.Type, argsPickle: Chunk[Byte])
