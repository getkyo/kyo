package kyo.test.snapshot

import kyo.Chunk
import kyo.Schema

/** Internal storage envelope for a golden: the normalized sample spread as a `samples`-keyed document, so a golden file round-trips as one
  * document through both text and binary codecs.
  */
final private[snapshot] case class GoldenSamples[A](samples: Chunk[A])

private[snapshot] object GoldenSamples:
    given goldenSamplesSchema[A](using Schema[A]): Schema[GoldenSamples[A]] = Schema.derived
