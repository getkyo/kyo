package kyo

import kyo.EmailInvalidEnhancedStatusCodeException.Violation
import kyo.EmailLiterals.*
import kyo.EmailSend.EnhancedStatusCode.StatusClass

class EmailSendEnhancedStatusCodeTest extends kyo.test.Test[Any]:

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
        assert(refused(EmailSend.EnhancedStatusCode.init(StatusClass.Permanent, 1000, 0)).violation == Violation.SubjectOutOfRange(1000))
    }

    "init fails on a negative detail" in {
        assert(refused(EmailSend.EnhancedStatusCode.init(StatusClass.Permanent, 1, -1)).violation == Violation.DetailOutOfRange(-1))
    }

    "a rejection inside a derived schema names the decoding caller's Frame" in {
        val stored = Json.encode(CodeShape(statusCodeOf(StatusClass.Permanent, 123, 1)))
        assert(stored.contains("123"), stored)
        Json.decode[CodeShape](stored.replace("123", "1000")) match
            case Result.Failure(e: ConstructorRejectedException) =>
                e.rejection match
                    case leaf: EmailInvalidEnhancedStatusCodeException =>
                        assert(leaf.violation == Violation.SubjectOutOfRange(1000))
                        assert(leaf.frame == e.frame, s"rejected at ${leaf.frame}, decoded at ${e.frame}")
                    case other => fail(s"expected the constructor's own failure, got $other")
            case other => fail(s"expected a ConstructorRejectedException, got $other")
        end match
    }

    final private case class CodeShape(code: EmailSend.EnhancedStatusCode) derives Schema

end EmailSendEnhancedStatusCodeTest
