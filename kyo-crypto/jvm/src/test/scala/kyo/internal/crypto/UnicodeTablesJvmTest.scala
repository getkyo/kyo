package kyo.internal.crypto

import java.nio.charset.StandardCharsets

/** The artifact's `META-INF/kyo-crypto/NOTICE`, the Unicode and RFC 3454 notices that travel with the generated [[UnicodeTables]]:
  * read as a classpath resource, which is how it reaches a jar.
  */
class UnicodeTablesJvmTest extends kyo.test.Test[Any]:

    private def notice(using kyo.test.AssertScope): String =
        val stream = getClass.getResourceAsStream("/META-INF/kyo-crypto/NOTICE")
        assert(stream != null, "META-INF/kyo-crypto/NOTICE is not on the classpath")
        try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
        finally stream.close()
    end notice

    "the notice carries RFC 3454's copyright notice and its derivative-works paragraph" in {
        val text = notice
        assert(text.contains("Copyright (C) The Internet Society (2002).  All Rights Reserved."))
        assert(text.contains("derivative works that comment on or otherwise explain it"))
        assert(text.contains("provided that the above copyright notice and this paragraph are"))
        assert(text.contains("https://www.rfc-editor.org/rfc/rfc3454.txt"))
    }

    "the notice carries the Unicode licence, its copyright line and the data's source" in {
        val text    = notice
        val license = UnicodeInputs.text("unicode-15.1.0", "LICENSE")
        assert(text.endsWith(license))
        assert(text.contains("kyo.internal.crypto.UnicodeTables"))
        assert(text.contains("version 15.1.0 (https://www.unicode.org/Public/15.1.0/ucd/UnicodeData.txt and CompositionExclusions.txt)"))
        assert(text.contains(license.linesIterator.map(_.trim).find(_.startsWith("Copyright")).get))
        assert(text.contains("Unicode License v3 (https://www.unicode.org/license.txt)"))
    }

    "no bare META-INF/NOTICE is shipped, so a shaded jar has nothing of this module's to merge" in {
        assert(getClass.getResource("/META-INF/NOTICE") == null)
    }

end UnicodeTablesJvmTest
