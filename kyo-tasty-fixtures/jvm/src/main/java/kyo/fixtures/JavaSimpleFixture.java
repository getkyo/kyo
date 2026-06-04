package kyo.fixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Minimal Java fixture for cross-platform Java classfile decode testing.
 *
 * Provides: RUNTIME-retained annotation, CLASS-retained annotation, SOURCE-retained
 * annotation (excluded from .class), a public field, a static method, and an inner class.
 * Used to exercise the Java decoder and annotation retention paths on JS/Native.
 *
 * Pins: F-A1-OPEN (Java decoder coverage), F-A3-OPEN-AP (annotation retention).
 */
public class JavaSimpleFixture {

    /** RUNTIME-retained annotation: visible via reflection and classfile decoder. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE, ElementType.FIELD})
    public @interface SimpleRuntimeAnnotation {
        String value() default "runtime";
    }

    /** CLASS-retained annotation: present in .class but not at runtime reflection. */
    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.FIELD, ElementType.TYPE})
    public @interface SimpleClassAnnotation {
        int id() default 0;
    }

    /** SOURCE-retained annotation: stripped from .class output entirely. */
    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.METHOD)
    public @interface SimpleSourceAnnotation {}

    /** Public field annotated with the CLASS-retained annotation. */
    @SimpleClassAnnotation(id = 42)
    public int value;

    /** Default constructor. */
    public JavaSimpleFixture() {
        this.value = 0;
    }

    /** Method annotated with the RUNTIME-retained annotation. */
    @SimpleRuntimeAnnotation("hello")
    public int getValue() {
        return value;
    }

    /** Static method for static-modifier decode coverage. */
    public static String staticHelper(int x) {
        return "value=" + x;
    }

    /** Inner class for inner-class hierarchy decode coverage. */
    public class Inner {
        public final String label;

        public Inner(String label) {
            this.label = label;
        }

        public String describe() {
            return label + ":" + value;
        }
    }
}
