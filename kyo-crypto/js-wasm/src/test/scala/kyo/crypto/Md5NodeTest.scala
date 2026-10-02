package kyo.crypto

import kyo.*

/** [[Md5]] against Node's `createHash` (OpenSSL). */
class Md5NodeTest extends kyo.test.Test[Any]:

    "matches Node at every size from 0 to 300 and on 200 random inputs, split at random points" in {
        assert(Sha256NodeTest.mismatches("md5", Md5.hashArrays) == Seq.empty)
    }

end Md5NodeTest
