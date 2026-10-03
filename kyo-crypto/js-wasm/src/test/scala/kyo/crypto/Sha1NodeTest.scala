package kyo.crypto

import kyo.*

/** [[Sha1]] against Node's `createHash` (OpenSSL). */
class Sha1NodeTest extends kyo.test.Test[Any]:

    "matches Node at every size from 0 to 300 and on 200 random inputs, split at random points" in {
        assert(Sha256NodeTest.mismatches("sha1", Sha1.hashArrays) == Seq.empty)
    }

end Sha1NodeTest
