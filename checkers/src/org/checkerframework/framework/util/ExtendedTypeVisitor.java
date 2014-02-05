package org.checkerframework.framework.util;

public interface ExtendedTypeVisitor<R,P> {
    R visitArray(ExtendedArrayType t, P p);
    R visitDeclared(ExtendedDeclaredType t, P p);
    R visitError(ExtendedErrorType t, P p);
    R visitExecutable(ExtendedExecutableType t, P p);
    R visitIntersection(ExtendedIntersectionType t, P p);
    R visitNoType(ExtendedNoType t, P p);
    R visitNull(ExtendedNullType t, P p);
    R visitPrimitive(ExtendedPrimitiveType t, P p);
    R visitTypeVariable(ExtendedTypeVariable t, P p);
    R visitUnion(ExtendedUnionType t, P p);
    R visitWildcard(ExtendedWildcardType t, P p);
}
