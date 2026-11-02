package kyo.internal

import scala.annotation.StaticAnnotation

/** Marks an inline method whose typed application stands for `.now`.
  *
  * The method has the shape of `.now`: two type parameters, the exposed result `A` and the row `S`, and one value parameter, the
  * computation `A < S`. kyo-direct's lowering reads an application of such a method exactly as it reads `.now`: the computation is
  * sequenced at that position and the rest of the block becomes its continuation. A front end that plants markers through its own
  * inline method (kyo-lang's `Markers.force`) carries this annotation on it, so the marker is recognized before the inline method is
  * expanded and the expansion itself never runs inside a lowered body.
  */
final class NowMarker extends StaticAnnotation
