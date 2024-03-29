package org.checkerframework.common.reflection.qual;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.checkerframework.framework.qual.SubtypeOf;
import org.checkerframework.framework.qual.TypeQualifier;

/**
 * This represents a Class&lt;T&gt; object whose run-time value
 * is equal to or a subtype of one of the arguments.
 *
 * @checker_framework.manual #classval-checker ClassVal Checker
 */
@TypeQualifier
@SubtypeOf({ UnknownClass.class })
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE_USE })
public @interface ClassBound {
    /** The <a href="https://docs.oracle.com/javase/specs/jls/se8/html/jls-13.html#jls-13.1">binary name</a>
     * of the class or classes that upper-bound the values of this Class object. */
    String[] value();
}
