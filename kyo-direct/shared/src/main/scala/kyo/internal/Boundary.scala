package kyo.internal

import scala.annotation.StaticAnnotation

/** Marks an inline identity method whose application wraps the computation a boundary lowered.
  *
  * A front end that lowers part of a body on its own (kyo-lang's handler arguments, effectful closures, and callbacks to effect-aware
  * APIs) wraps the lowered computation in such a method. kyo-direct's validation reads the application as it reads an already expanded
  * nested `direct` block: a computation whose markers were its own, which is a value where it stands and whose effects would be dropped
  * as a statement. The method has one type parameter, the computation's type, and one value parameter, the computation.
  */
final class Boundary extends StaticAnnotation
