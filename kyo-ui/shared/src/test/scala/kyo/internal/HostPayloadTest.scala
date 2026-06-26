package kyo.internal

import kyo.*

/** Schema round-trip codec test for every HostPayload feed leaf and the app-event UIEvent leaf.
  *
  * All leaves are purely synchronous (encode then decode with no async), run on JVM, JS, and Wasm, and carry no
  * browser dependency. Every wire type must survive a Json.encode then Json.decode round-trip with field-exact
  * equality (CanEqual ==). The absence of js.Dynamic in HostPayload.scala is a verify-time static check, not a
  * Scala test body.
  */
class HostPayloadTest extends kyo.test.Test[Any]:

    "SignalUpdate(encoded Int) Schema round-trip (feed leaf round-trip)" in {
        // The feed-by-signal-id leaf carries the fed value as an opaque Json.encode'd string of
        // its Schema, decoded client-side with the same Schema. Probe the double round-trip: the leaf
        // survives the HostPayload codec, and the inner encoded payload decodes back to the exact Int the
        // server fed. 0xff00ff == 16711935 decimal; the bit-pattern must survive exactly.
        val fedColor: Int         = 0xff00ff
        val encoded               = Json.encode[Int](fedColor)
        val original: HostPayload = HostPayload.SignalUpdate("feed-color", encoded)
        val outer                 = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](outer)
        assert(decoded == Result.Success(original))
        decoded match
            case Result.Success(HostPayload.SignalUpdate(sid, enc)) =>
                assert(sid == "feed-color")
                // The inner encoded payload decodes back to the exact fed value.
                assert(Json.decode[Int](enc) == Result.Success(16711935))
            case other => fail(s"unexpected: $other")
        end match
    }

    "SignalChunk(encoded Chunk[Int]) Schema round-trip (structural feed leaf round-trip)" in {
        // The structural feed leaf carries the whole Chunk[A] snapshot as an opaque Json.encode'd
        // string of its Schema, decoded client-side with the same Schema. Probe the double round-trip: the
        // leaf survives the HostPayload codec, and the inner encoded payload decodes back to the exact list
        // the server fed, in order (the ordering is load-bearing for the keyed reconciler).
        val fedList: Chunk[Int]   = Chunk(4, 3, 2, 0)
        val encoded               = Json.encode[Chunk[Int]](fedList)
        val original: HostPayload = HostPayload.SignalChunk("feed-list", encoded)
        val outer                 = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](outer)
        assert(decoded == Result.Success(original))
        decoded match
            case Result.Success(HostPayload.SignalChunk(sid, enc)) =>
                assert(sid == "feed-list")
                assert(Json.decode[Chunk[Int]](enc) == Result.Success(Chunk(4, 3, 2, 0)))
            case other => fail(s"unexpected: $other")
        end match
    }

    "AppEvent(encoded payload) Schema round-trip (app-event back-channel leaf round-trip)" in {
        // The app-event back-channel carries the typed event as an opaque Json.encode'd string of
        // its Schema under an eventId. Probe the double round-trip: the UIEvent.AppEvent survives the
        // UIEvent codec, and the inner encoded payload decodes back to the exact value the client emitted.
        val encoded           = Json.encode[Int](7)
        val original: UIEvent = UIEvent.AppEvent(Seq("h"), "bump", encoded)
        val outer             = Json.encode[UIEvent](original)
        val decoded           = Json.decode[UIEvent](outer)
        assert(decoded == Result.Success(original))
        decoded match
            case Result.Success(UIEvent.AppEvent(path, eventId, enc)) =>
                assert(path == Seq("h"))
                assert(eventId == "bump")
                assert(Json.decode[Int](enc) == Result.Success(7))
            case other => fail(s"unexpected: $other")
        end match
    }

end HostPayloadTest
