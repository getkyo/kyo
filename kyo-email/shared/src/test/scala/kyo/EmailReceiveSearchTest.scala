package kyo

import kyo.EmailLiterals.*

class EmailReceiveSearchTest extends kyo.test.Test[Any]:

    "a header key's init refuses a name that is not a field name" in {
        Seq("", "Bad Name", "a:b", "café", "a\u0001").foreach { name =>
            val ex = refused(EmailReceive.Search.Header.init(name, "x"))
            assert(ex.name == name && ex.problem == EmailInvalidHeaderException.Problem.InvalidName)
        }
        assert(headerKeyOf("X-Mailer", "kyo").name == "X-Mailer")
    }

end EmailReceiveSearchTest
