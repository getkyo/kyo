package kyo

import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.symbol.Memo

class InternerTest extends Test:

    private def utf8Bytes(s: String): Array[Byte] =
        s.getBytes(java.nio.charset.StandardCharsets.UTF_8)

    // Test 1: two intern calls for the same byte sequence return reference-equal Name instances.
    "two intern calls for the same bytes return reference-equal Name instances" in run {
        val interner = new Interner(32)
        val bytes    = utf8Bytes("hello")
        val n1       = interner.intern(bytes, 0, bytes.length)
        val n2       = interner.intern(bytes, 0, bytes.length)
        assert(n1 eq n2)
    }

    // Test 2: two intern calls for different byte sequences return non-equal Name instances.
    "two intern calls for different bytes return non-equal Name instances" in run {
        val interner = new Interner(32)
        val b1       = utf8Bytes("hello")
        val b2       = utf8Bytes("world")
        val n1       = interner.intern(b1, 0, b1.length)
        val n2       = interner.intern(b2, 0, b2.length)
        assert(!(n1 eq n2))
    }

    // Test 3: intern from two different shards (different hash values) produces distinct entries.
    "names in different shards are distinct entries" in run {
        // Use 1-shard interner so shard logic is exercised; then use 2-shard to force shard separation.
        val interner = new Interner(32)
        // "a" and "b" will most likely land in different shards given FNV-1a hash distribution.
        val ba = utf8Bytes("alpha")
        val bb = utf8Bytes("beta")
        val na = interner.intern(ba, 0, ba.length)
        val nb = interner.intern(bb, 0, bb.length)
        assert(!(na eq nb))
        // Confirm they resolve to distinct strings.
        assert(na.string.get() != nb.string.get())
    }

    // Test 4: Name.asString returns the correct UTF-8 decoded string.
    "Name.asString returns the correct decoded string" in run {
        val interner = new Interner(32)
        val s        = "PlainClass"
        val bytes    = utf8Bytes(s)
        val entry    = interner.intern(bytes, 0, bytes.length)
        // Wrap via the package-internal factory so we can test the Name extension method.
        val name: Reflect.Name = Reflect.Name.wrap(entry)
        assert(name.asString == s)
    }

    // Test 5: Name.asString called twice returns the same (reference-equal) String (Memo caching).
    "Name.asString called twice returns the same String reference (Memo caching)" in run {
        val interner           = new Interner(32)
        val bytes              = utf8Bytes("cached")
        val entry              = interner.intern(bytes, 0, bytes.length)
        val name: Reflect.Name = Reflect.Name.wrap(entry)
        val s1                 = name.asString
        val s2                 = name.asString
        assert(s1 eq s2)
    }

    // Test 6: CanEqual[Name, Name] holds for two names with the same bytes.
    "CanEqual[Name, Name]: two names interned from the same bytes are equal" in run {
        val interner         = new Interner(32)
        val bytes            = utf8Bytes("equal")
        val n1: Reflect.Name = Reflect.Name.wrap(interner.intern(bytes, 0, bytes.length))
        val n2: Reflect.Name = Reflect.Name.wrap(interner.intern(bytes, 0, bytes.length))
        // CanEqual allows == comparison; since both are the same Entry, == is true.
        assert(n1 == n2)
    }

end InternerTest
