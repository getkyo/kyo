package kyo.crypto

import kyo.*

/** [[Sha512]] against Node's `createHash` (OpenSSL). */
class Sha512NodeTest extends kyo.test.Test[Any]:

    "matches Node at every size from 0 to 300 and on 200 random inputs, split at random points" in {
        assert(Sha256NodeTest.mismatches("sha512", Sha512.hashArrays) == Seq.empty)
    }

end Sha512NodeTest
