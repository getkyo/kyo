package kyo

import kyo.EmailInvalidEnhancedStatusCodeException.Violation
import kyo.EmailLiterals.*
import kyo.EmailSmtp.EnhancedStatusCode.StatusClass

class EmailSmtpEnhancedStatusCodeTest extends kyo.test.Test[Any]:

    "shows the dotted form, the class as its digit" in {
        assert(statusCodeOf(StatusClass.Permanent, 1, 1).show == "5.1.1")
        assert(statusCodeOf(StatusClass.PersistentTransient, 999, 0).show == "4.999.0")
        assert(statusCodeOf(StatusClass.Success, 0, 0).show == "2.0.0")
    }

    "the permanent class is permanent, the success and persistent transient classes are not" in {
        assert(StatusClass.Permanent.permanent)
        assert(!StatusClass.PersistentTransient.permanent)
        assert(!StatusClass.Success.permanent)
        assert(statusCodeOf(StatusClass.Permanent, 7, 8).statusClass.permanent)
    }

    "init fails on a subject above 999" in {
        assert(refused(EmailSmtp.EnhancedStatusCode.init(StatusClass.Permanent, 1000, 0)).violation == Violation.SubjectOutOfRange(1000))
    }

    "init fails on a negative detail" in {
        assert(refused(EmailSmtp.EnhancedStatusCode.init(StatusClass.Permanent, 1, -1)).violation == Violation.DetailOutOfRange(-1))
    }

end EmailSmtpEnhancedStatusCodeTest
