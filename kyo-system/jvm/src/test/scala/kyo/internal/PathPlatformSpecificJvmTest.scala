package kyo.internal

import java.io.InterruptedIOException
import java.io.IOException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import kyo.*

class PathPlatformSpecificJvmTest extends kyo.test.Test[Any]:

    private val path = new NioPathUnsafe(java.nio.file.Path.of("classification-test"))

    private def catchRead(exception: IOException): Result[FileReadException, Any] =
        val method = classOf[NioPathUnsafe].getDeclaredMethod("catchRead", classOf[Function0[?]], classOf[String])
        method.setAccessible(true)
        val expression: Function0[Any] = () => throw exception
        method.invoke(path, expression, Frame.internal).asInstanceOf[Result[FileReadException, Any]]
    end catchRead

    private def catchWrite(exception: IOException): Result[FileWriteException, Any] =
        val method = classOf[NioPathUnsafe].getDeclaredMethod("catchWrite", classOf[Function0[?]], classOf[String])
        method.setAccessible(true)
        val expression: Function0[Any] = () => throw exception
        method.invoke(path, expression, Frame.internal).asInstanceOf[Result[FileWriteException, Any]]
    end catchWrite

    private def catchStructure(exception: IOException): Result[FileStructureException, Any] =
        val method = classOf[NioPathUnsafe].getDeclaredMethod(
            "catchFs",
            classOf[FileSystemOperation],
            classOf[Function0[?]],
            classOf[String]
        )
        method.setAccessible(true)
        val expression: Function0[Any] = () => throw exception
        method
            .invoke(path, FileSystemOperation.Create, expression, Frame.internal)
            .asInstanceOf[Result[FileStructureException, Any]]
    end catchStructure

    private def assertPanic[E](result: Result[E, Any], exception: IOException)(using kyo.test.AssertScope): Unit =
        result match
            case Result.Panic(actual) => assert(actual eq exception)
            case other                => fail(s"Expected Panic, got $other")
    end assertPanic

    "unrelated numeric diagnostics are not classified as missing files" in {
        assert(!NioExceptionBoundary.isFileNotFound(IOException("unrelated subsystem failed (2)")))
        assert(!NioExceptionBoundary.isFileNotFound(IOException("unrelated subsystem failed (3)")))
    }

    "typed path exceptions retain their classifications" in {
        assert(NioExceptionBoundary.isFileNotFound(NoSuchFileException("missing")))
        assert(NioExceptionBoundary.isNotDirectory(NotDirectoryException("file")))
        assert(!NioExceptionBoundary.isNotDirectory(IOException("unrelated subsystem failed (267)")))
    }

    "unrelated atomic move wording is not classified as unsupported" in {
        assert(!NioExceptionBoundary.isAtomicMoveUnsupported(IOException("unrelated atomic move audit failed")))
    }

    "the JVM atomic-move exception is classified without message matching" in {
        val exception = AtomicMoveNotSupportedException("source", "target", "not supported")
        assert(NioExceptionBoundary.isAtomicMoveUnsupported(exception))
    }

    "InterruptedIOException remains a panic at filesystem boundaries" in {
        val exception = InterruptedIOException("interrupted")
        assert(NioExceptionBoundary.isInterrupted(exception))
        assertPanic(catchRead(exception), exception)
        assertPanic(catchWrite(exception), exception)
        assertPanic(catchStructure(exception), exception)
    }

    "ClosedByInterruptException remains a panic at filesystem boundaries" in {
        val exception = ClosedByInterruptException()
        assert(NioExceptionBoundary.isInterrupted(exception))
        assertPanic(catchRead(exception), exception)
        assertPanic(catchWrite(exception), exception)
        assertPanic(catchStructure(exception), exception)
    }

end PathPlatformSpecificJvmTest
