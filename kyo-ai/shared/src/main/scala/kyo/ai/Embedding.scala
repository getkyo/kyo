package kyo.ai

import kyo.*

/** A single embedding vector tagged with the space that produced it.
  *
  * `Embedding` is the element type `Completion.embed` returns and the input to the
  * compactor's semantic-edge derivation. `modelName` and `dim` tag the embedding space:
  * two embeddings are comparable (a cosine similarity is meaningful) only when both
  * fields match, because a vector produced by one model lives in a different space
  * from another model's. The compactor treats a `(modelName, dim)` mismatch as no edge
  * rather than comparing across spaces, so the space is a runtime-negotiated fact,
  * not a static type parameter.
  *
  * @param vector
  *   the dense float vector; `Span[Float]` keeps the cosine hot path allocation-free
  *   and cross-platform (JVM, JS, Native, Wasm).
  * @param modelName
  *   the embedding model that produced the vector, tagging its space.
  * @param dim
  *   the vector length, a redundant guard checked alongside `modelName`.
  */
final case class Embedding(vector: Span[Float], modelName: String, dim: Int) derives CanEqual, Schema:

    /** The cosine similarity with `that`, guarded by the embedding space. Returns
      * `Absent` when the two embeddings do not share a `(modelName, dim)` space, so a
      * caller never compares vectors across spaces (a no-edge outcome, not a throw).
      */
    def cosine(that: Embedding): Maybe[Double] =
        if modelName != that.modelName || dim != that.dim || dim == 0 then Absent
        else
            var dot = 0.0
            var na  = 0.0
            var nb  = 0.0
            var i   = 0
            while i < dim do
                val a = vector(i).toDouble
                val b = that.vector(i).toDouble
                dot += a * b
                na += a * a
                nb += b * b
                i += 1
            end while
            if na == 0.0 || nb == 0.0 then Present(0.0)
            else Present(dot / (math.sqrt(na) * math.sqrt(nb)))
    end cosine
end Embedding
