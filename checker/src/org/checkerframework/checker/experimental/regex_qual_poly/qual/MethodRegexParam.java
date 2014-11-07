package org.checkerframework.checker.experimental.regex_qual_poly.qual;

import org.checkerframework.qualframework.poly.SimpleQualifierParameterAnnotationConverter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
@Repeatable(MultiMethodRegexParam.class)
public @interface MethodRegexParam {
    String value() default SimpleQualifierParameterAnnotationConverter.PRIMARY_TARGET;
}
