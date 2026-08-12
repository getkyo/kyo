package kyo

import java.nio.charset.StandardCharsets

class GlobMacroTest extends kyo.test.Test[Any]:

    "literal bytecode reconstructs the automaton without calling parse" in {
        val stream = getClass.getClassLoader.getResourceAsStream("globclient/GlobLiteralFixture$.class")
        assert(stream != null)
        val bytecode =
            try new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1)
            finally stream.close()
        assert(bytecode.contains("fromEncodedV1"))
        assert(!bytecode.contains("parse"))
    }
end GlobMacroTest
