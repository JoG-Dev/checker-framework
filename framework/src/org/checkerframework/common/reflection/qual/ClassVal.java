package org.checkerframework.common.reflection.qual;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.checkerframework.framework.qual.SubtypeOf;
import org.checkerframework.framework.qual.TypeQualifier;

/**
 * This represents a {@link java.lang.Class Class&lt;T&gt;} object
 * where the set of possible values of T is known at compile time.  If only
 * one argument is given, then the exact value of T is known.  If more than
 * one argument is given, then the value of T is one of those classes.
 *
 * @checker_framework.manual #classval-checker ClassVal Checker
 */
@TypeQualifier
@SubtypeOf({ UnknownClass.class })
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE_USE })
public @interface ClassVal {
    /** The <a href="https://docs.oracle.com/javase/specs/jls/se8/html/jls-13.html#jls-13.1">binary name</a>
     * of the class that this Class object represents. */
    String[] value();
}

