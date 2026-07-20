package kyo.ai

import kyo.*

class EmbeddingTest extends kyo.test.Test[Any]:

    "embedding Schema round-trips through Json (vector, modelName, dim preserved)" in {
        val original = Embedding(Span(0.1f, 0.2f, 0.3f), "text-embedding-3-small", 3)
        val json     = Json.encode(original)
        val decoded  = Json.decode[Embedding](json).getOrThrow
        assert(decoded.vector(0) == 0.1f && decoded.vector(1) == 0.2f && decoded.vector(2) == 0.3f)
        assert(decoded.modelName == "text-embedding-3-small")
        assert(decoded.dim == 3)
    }

    "cosine of two same-space vectors returns the analytic value" in {
        val a = Embedding(Span(1f, 0f, 0f), "m", 3)
        val b = Embedding(Span(1f, 0f, 0f), "m", 3)
        assert(a.cosine(b) == Present(1.0), s"identical unit vectors should have cosine 1.0, got: ${a.cosine(b)}")
        val orthoA = Embedding(Span(1f, 0f), "m", 2)
        val orthoB = Embedding(Span(0f, 1f), "m", 2)
        assert(orthoA.cosine(orthoB) == Present(0.0), s"orthogonal unit vectors should have cosine 0.0, got: ${orthoA.cosine(orthoB)}")
    }

    "cross-space cosine is no-edge (modelName mismatch)" in {
        val a = Embedding(Span(1f, 0f), "modelA", 2)
        val b = Embedding(Span(1f, 0f), "modelB", 2)
        assert(a.cosine(b) == Absent, s"a modelName mismatch must never produce a numeric similarity, got: ${a.cosine(b)}")
    }

    "absence: dim mismatch is also no-edge" in {
        val a = Embedding(Span(1f, 0f, 0f), "m", 3)
        val b = Embedding(Span(1f, 0f), "m", 2)
        assert(a.cosine(b) == Absent, s"a (modelName,dim)-mismatched pair must never produce a similarity, got: ${a.cosine(b)}")
    }

    "a dim-0 pair in the same space is also no-edge, never a division by zero" in {
        val a = Embedding(Span[Float](), "m", 0)
        val b = Embedding(Span[Float](), "m", 0)
        assert(a.cosine(b) == Absent, s"a dim-0 pair must guard the division by zero, got: ${a.cosine(b)}")
    }

    "a same-space all-zeros vector yields cosine 0.0, never a division by zero" in {
        val a = Embedding(Span(0f, 0f, 0f), "m", 3)
        val b = Embedding(Span(1f, 0f, 0f), "m", 3)
        assert(a.cosine(b) == Present(0.0), s"an all-zeros vector must guard the zero-norm division, got: ${a.cosine(b)}")
    }

end EmbeddingTest
