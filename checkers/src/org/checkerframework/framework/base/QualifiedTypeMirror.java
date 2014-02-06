package org.checkerframework.framework.base;

import java.util.ArrayList;
import java.util.List;

import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

import org.checkerframework.framework.util.ExtendedArrayType;
import org.checkerframework.framework.util.ExtendedDeclaredType;
import org.checkerframework.framework.util.ExtendedExecutableType;
import org.checkerframework.framework.util.ExtendedIntersectionType;
import org.checkerframework.framework.util.ExtendedNoType;
import org.checkerframework.framework.util.ExtendedNullType;
import org.checkerframework.framework.util.ExtendedPrimitiveType;
import org.checkerframework.framework.util.ExtendedTypeVariable;
import org.checkerframework.framework.util.ExtendedUnionType;
import org.checkerframework.framework.util.ExtendedWildcardType;
import org.checkerframework.framework.util.ExtendedTypeMirror;

public abstract class QualifiedTypeMirror<Q> {
    private final ExtendedTypeMirror underlying;
    private final Q qualifier;
    Exception where;

    private QualifiedTypeMirror(ExtendedTypeMirror underlying, Q qualifier) {
        if (qualifier == null) {
            throw new IllegalArgumentException(
                    "cannot construct QualifiedTypeMirror with null qualifier");
        }

        this.underlying = underlying;
        this.qualifier = qualifier;
        try {
            throw new RuntimeException("where");
        } catch (Exception e) {
            this.where = e;
        }
    }

    public abstract <R,P> R accept(QualifiedTypeVisitor<Q,R,P> visitor, P p);

    public ExtendedTypeMirror getUnderlyingType() {
        return underlying;
    }

    public final TypeKind getKind() {
        return underlying.getKind();
    }

    public final /*@NonNull*/ Q getQualifier() {
        return qualifier;
    }

    public Q getEffectiveQualifier() {
        return qualifier;
    }

    @Override
    public String toString() {
        return qualifier + " " + underlying;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != this.getClass()) {
            return false;
        }
        @SuppressWarnings("unchecked")
        QualifiedTypeMirror<Q> other = (QualifiedTypeMirror<Q>)obj;
        return this.qualifier.equals(other.qualifier)
            && this.underlying.equals(other.underlying);
    }

    @Override
    public int hashCode() {
        return this.qualifier.hashCode() * 17
            + this.underlying.hashCode() * 37;
    }


    /** Check that the underlying ExtendedTypeMirror has a specific TypeKind, and
     * throw an exception if it does not.  This is a helper method for
     * QualifiedTypeMirror subclass constructors.
     */
    private static void checkUnderlyingKind(ExtendedTypeMirror underlying, TypeKind expectedKind) {
        TypeKind actualKind = underlying.getKind();
        if (actualKind != expectedKind) {
            throw new IllegalArgumentException(
                    "underlying ExtendedTypeMirror must have kind " + expectedKind +
                    ", not " + actualKind);
        }
    }

    /** Check that the underlying ExtendedTypeMirror has one of the indicated
     * TypeKinds, and throw an exception if it does not.  This is a helper
     * method for QualifiedTypeMirror subclass constructors.
     */
    private static void checkUnderlyingKindIsOneOf(ExtendedTypeMirror underlying, TypeKind... validKinds) {
        TypeKind actualKind = underlying.getKind();
        for (TypeKind kind : validKinds) {
            if (actualKind == kind) {
                // The ExtendedTypeMirror is valid.
                return;
            }
        }
        throw new IllegalArgumentException(
                "underlying ExtendedTypeMirror must have one of the kinds " +
                java.util.Arrays.toString(validKinds) + ", not " + actualKind);
    }

    /** Check that the underlying ExtendedTypeMirror has a primitive TypeKind, and
     * throw an exception if it does not.
     */
    // This method is here instead of in QualifiedPrimitiveType so that its
    // exception message can be kept consistent with the message from
    // checkUnderlyingKind.
    private static void checkUnderlyingKindIsPrimitive(ExtendedTypeMirror underlying) {
        TypeKind actualKind = underlying.getKind();
        if (!actualKind.isPrimitive()) {
            throw new IllegalArgumentException(
                    "underlying ExtendedTypeMirror must have primitive kind, not " + actualKind);
        }
    }

    /** Helper function to raise an appropriate exception in case of a mismatch
     * between qualified and unqualified versions of the same ExtendedTypeMirror.
     */
    private static <Q> void checkTypeMirrorsMatch(String description,
            QualifiedTypeMirror<Q> qualified, ExtendedTypeMirror unqualified) {
        if (!typeMirrorsMatch(qualified, unqualified)) {
            throw new IllegalArgumentException(
                    "qualified and unqualified " + description +
                    " TypeMirrors must be identical");
        }
    }

    /** Check if the underlying types of a list of QualifiedTypeMirrors match
     * the actual TypeMirrors from a second list.
     */
    private static <Q> void checkTypeMirrorListsMatch(String description,
            List<? extends QualifiedTypeMirror<Q>> qualified,
            List<? extends ExtendedTypeMirror> unqualified) {
        if (!typeMirrorListsMatch(qualified, unqualified)) {
            System.err.printf("MISMATCH!! %s <-> %s\n", qualified, unqualified);
            qualified.get(0).where.printStackTrace();
            throw new IllegalArgumentException(
                    "qualified and unqualified " + description +
                    " TypeMirrors must be identical");
        }
    }

    private static <Q> boolean typeMirrorsMatch(
            QualifiedTypeMirror<Q> qualified, ExtendedTypeMirror unqualified) {
        if (qualified == null && unqualified == null) {
            return true;
        }

        if (qualified == null || unqualified == null ||
                qualified.getUnderlyingType() != unqualified) {
            return false;
        }

        return true; 
    }

    /** Helper function for checkTypeMirrorListsMatch.  Returns a boolean
     * indicating whether the qualified and unqualified lists have matching
     * TypeMirrors.
     */
    private static <Q> boolean typeMirrorListsMatch(
            List<? extends QualifiedTypeMirror<Q>> qualified,
            List<? extends ExtendedTypeMirror> unqualified) {
        if (qualified == null && unqualified == null) {
            return true;
        }
        if (qualified == null || unqualified == null) {
            return false;
        }
        if (unqualified.size() != qualified.size())
            return false;

        for (int i = 0; i < qualified.size(); ++i) {
            if (!typeMirrorsMatch(qualified.get(i), unqualified.get(i))) {
                return false;
            }
        }

        return true;
    }

    private static String commaSeparatedList(List<? extends Object> objs) {
        return punctuatedList(", ", objs);
    }

    private static String punctuatedList(String punct, List<? extends Object> objs) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object obj : objs) {
            if (!first) {
                sb.append(punct);
            } else {
                first = false;
            }
            sb.append(obj);
        }
        return sb.toString();
    }


    public static final class QualifiedArrayType<Q> extends QualifiedTypeMirror<Q> {
        private final QualifiedTypeMirror<Q> componentType;

        public QualifiedArrayType(ExtendedTypeMirror underlying, Q qualifier,
                QualifiedTypeMirror<Q> componentType) {
            super(underlying, qualifier);
            checkUnderlyingKind(underlying, TypeKind.ARRAY);
            checkTypeMirrorsMatch("component",
                    componentType, getUnderlyingType().getComponentType());

            this.componentType = componentType;
        }

        @Override
        public <R,P> R accept(QualifiedTypeVisitor<Q,R,P> visitor, P p) {
            return visitor.visitArray(this, p);
        }

        public ExtendedArrayType getUnderlyingType() {
            return (ExtendedArrayType)super.getUnderlyingType();
        }

        public QualifiedTypeMirror<Q> getComponentType() {
            return componentType;
        }

        @Override
        public String toString() {
            return getComponentType() + " " + getQualifier() + " []";
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj))
                return false;
            // super.equals ensures that 'obj.getClass() == this.getClass()'.
            @SuppressWarnings("unchecked")
            QualifiedArrayType<Q> other = (QualifiedArrayType<Q>)obj;
            return this.componentType.equals(other.componentType);
        }

        @Override
        public int hashCode() {
            return super.hashCode()
                + componentType.hashCode() * 43;
        }
    }

    
    public static final class QualifiedDeclaredType<Q> extends QualifiedTypeMirror<Q> {
        private final List<? extends QualifiedTypeMirror<Q>> typeArguments;

        public QualifiedDeclaredType(ExtendedTypeMirror underlying, Q qualifier,
                List<? extends QualifiedTypeMirror<Q>> typeArguments) {
            super(underlying, qualifier);
            checkUnderlyingKind(underlying, TypeKind.DECLARED);
            checkTypeMirrorListsMatch("argument",
                    typeArguments, getUnderlyingType().getTypeArguments());

            this.typeArguments = new ArrayList<>(typeArguments);
        }

        @Override
        public <R,P> R accept(QualifiedTypeVisitor<Q,R,P> visitor, P p) {
            return visitor.visitDeclared(this, p);
        }

        public ExtendedDeclaredType getUnderlyingType() {
            return (ExtendedDeclaredType)super.getUnderlyingType();
        }

        public List<? extends QualifiedTypeMirror<Q>> getTypeArguments() {
            return typeArguments;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(getQualifier());
            sb.append(" ");
            sb.append(getUnderlyingType());

            if (typeArguments.size() > 0) {
                sb.append("<").append(commaSeparatedList(typeArguments)).append(">");
            }
            return sb.toString();
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj))
                return false;
            // super.equals ensures that 'obj.getClass() == this.getClass()'.
            @SuppressWarnings("unchecked")
            QualifiedDeclaredType<Q> other = (QualifiedDeclaredType<Q>)obj;
            return this.typeArguments.equals(other.typeArguments);
        }

        @Override
        public int hashCode() {
            return super.hashCode()
                + typeArguments.hashCode() * 43;
        }
    }


    public static final class QualifiedExecutableType<Q> extends QualifiedTypeMirror<Q> {
        private final List<? extends QualifiedTypeMirror<Q>> parameterTypes;
        private final QualifiedTypeMirror<Q> receiverType;
        private final QualifiedTypeMirror<Q> returnType;
        private final List<? extends QualifiedTypeMirror<Q>> thrownTypes;
        private final List<? extends QualifiedTypeVariable<Q>> typeVariables;

        public QualifiedExecutableType(ExtendedTypeMirror underlying, Q qualifier,
                List<? extends QualifiedTypeMirror<Q>> parameterTypes,
                QualifiedTypeMirror<Q> receiverType,
                QualifiedTypeMirror<Q> returnType,
                List<? extends QualifiedTypeMirror<Q>> thrownTypes,
                List<? extends QualifiedTypeVariable<Q>> typeVariables) {
            super(underlying, qualifier);
            checkUnderlyingKind(underlying, TypeKind.EXECUTABLE);
            checkTypeMirrorListsMatch("parameter",
                    parameterTypes, getUnderlyingType().getParameterTypes());
            // TODO: This is a hack to make constructor types work.  According
            // to javac, constructors have type 'void ()', while according to
            // the checker framework, they are 'TheClass (TheClass this)'.  We
            // use the checker framework version for compatibility, which means
            // the receiver and return types may not actually match.
            /*
            checkTypeMirrorsMatch("receiver",
                    receiverType, getUnderlyingType().getReceiverType());
            checkTypeMirrorsMatch("return",
                    returnType, getUnderlyingType().getReturnType());
            */
            checkTypeMirrorListsMatch("thrown",
                    thrownTypes, getUnderlyingType().getThrownTypes());
            checkTypeMirrorListsMatch("type variable",
                    typeVariables, getUnderlyingType().getTypeVariables());

            this.parameterTypes = new ArrayList<>(parameterTypes);
            this.receiverType = receiverType;
            this.returnType = returnType;
            this.thrownTypes = new ArrayList<>(thrownTypes);
            this.typeVariables = new ArrayList<>(typeVariables);
        }

        @Override
        public <R,P> R accept(QualifiedTypeVisitor<Q,R,P> visitor, P p) {
            return visitor.visitExecutable(this, p);
        }

        public ExtendedExecutableType getUnderlyingType() {
            return (ExtendedExecutableType)super.getUnderlyingType();
        }

        public List<? extends QualifiedTypeMirror<Q>> getParameterTypes() {
            return parameterTypes;
        }

        public QualifiedTypeMirror<Q> getReceiverType() {
            return receiverType;
        }

        public QualifiedTypeMirror<Q> getReturnType() {
            return returnType;
        }

        public List<? extends QualifiedTypeMirror<Q>> getThrownTypes() {
            return thrownTypes;
        }

        public List<? extends QualifiedTypeVariable<Q>> getTypeVariables() {
            return typeVariables;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(getQualifier()).append(" : ");

            if (typeVariables.size() > 0) {
                sb.append("<").append(commaSeparatedList(typeVariables)).append(">");
            }

            sb.append(returnType);

            sb.append("((");
            if (receiverType != null) {
                sb.append(receiverType).append(" this, ");
            }
            sb.append(commaSeparatedList(parameterTypes));
            sb.append("))");

            if (thrownTypes.size() > 0) {
                sb.append(" throws ").append(commaSeparatedList(thrownTypes));
            }

            return sb.toString();
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj))
                return false;
            // super.equals ensures that 'obj.getClass() == this.getClass()'.
            @SuppressWarnings("unchecked")
            QualifiedExecutableType<Q> other = (QualifiedExecutableType<Q>)obj;
            return this.parameterTypes.equals(other.parameterTypes)
                && (this.receiverType == null ?
                        other.receiverType == null :
                        this.receiverType.equals(other.receiverType))
                && (this.returnType == null ?
                        other.returnType == null :
                        this.returnType.equals(other.returnType))
                && this.thrownTypes.equals(other.thrownTypes)
                && this.typeVariables.equals(other.typeVariables);
        }

        @Override
        public int hashCode() {
            return super.hashCode()
                + parameterTypes.hashCode() * 43
                + (this.receiverType == null ? 0 : this.receiverType.hashCode() * 67)
                + (this.returnType == null ? 0 : this.returnType.hashCode() * 83)
                + thrownTypes.hashCode() * 109
                + typeVariables.hashCode() * 127;
        }
    }

    public static final class QualifiedIntersectionType<Q> extends QualifiedTypeMirror<Q> {
        private final List<? extends QualifiedTypeMirror<Q>> bounds;

        public QualifiedIntersectionType(ExtendedTypeMirror underlying, Q qualifier,
                List<? extends QualifiedTypeMirror<Q>> bounds) {
            super(underlying, qualifier);
            checkUnderlyingKind(underlying, TypeKind.INTERSECTION);
            checkTypeMirrorListsMatch("bounds",
                    bounds, getUnderlyingType().getBounds());

            this.bounds = new ArrayList<>(bounds);
        }

        @Override
        public <R,P> R accept(QualifiedTypeVisitor<Q,R,P> visitor, P p) {
            return visitor.visitIntersection(this, p);
        }

        public ExtendedIntersectionType getUnderlyingType() {
            return (ExtendedIntersectionType)super.getUnderlyingType();
        }

        public List<? extends QualifiedTypeMirror<Q>> getBounds() {
            return bounds;
        }

        @Override
        public String toString() {
            return punctuatedList(" & ", bounds);
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj))
                return false;
            // super.equals ensures that 'obj.getClass() == this.getClass()'.
            @SuppressWarnings("unchecked")
            QualifiedIntersectionType<Q> other = (QualifiedIntersectionType<Q>)obj;
            return this.bounds.equals(other.bounds);
        }

        @Override
        public int hashCode() {
            return super.hashCode()
                + bounds.hashCode() * 43;
        }
    }

    public static final class QualifiedNoType<Q> extends QualifiedTypeMirror<Q> {
        public QualifiedNoType(ExtendedTypeMirror underlying, Q qualifier) {
            super(underlying, qualifier);
            // According to the ExtendedNoType javadocs, valid kinds are NONE, PACKAGE,
            // and VOID.
            checkUnderlyingKindIsOneOf(underlying,
                    TypeKind.NONE, TypeKind.PACKAGE, TypeKind.VOID);
        }

        @Override
        public <R,P> R accept(QualifiedTypeVisitor<Q,R,P> visitor, P p) {
            return visitor.visitNoType(this, p);
        }

        public ExtendedNoType getUnderlyingType() {
            return (ExtendedNoType)super.getUnderlyingType();
        }

        // Use superclass implementation of 'toString', 'equals', and
        // 'hashCode'.
    }

    public static final class QualifiedNullType<Q> extends QualifiedTypeMirror<Q> {
        public QualifiedNullType(ExtendedTypeMirror underlying, Q qualifier) {
            super(underlying, qualifier);
            checkUnderlyingKind(underlying, TypeKind.NULL);
        }

        @Override
        public <R,P> R accept(QualifiedTypeVisitor<Q,R,P> visitor, P p) {
            return visitor.visitNull(this, p);
        }

        public ExtendedNullType getUnderlyingType() {
            return (ExtendedNullType)super.getUnderlyingType();
        }

        // Use superclass implementation of 'toString', 'equals', and
        // 'hashCode'.
    }

    public static final class QualifiedPrimitiveType<Q> extends QualifiedTypeMirror<Q> {
        public QualifiedPrimitiveType(ExtendedTypeMirror underlying, Q qualifier) {
            super(underlying, qualifier);
            checkUnderlyingKindIsPrimitive(underlying);
        }

        @Override
        public <R,P> R accept(QualifiedTypeVisitor<Q,R,P> visitor, P p) {
            return visitor.visitPrimitive(this, p);
        }

        public ExtendedPrimitiveType getUnderlyingType() {
            return (ExtendedPrimitiveType)super.getUnderlyingType();
        }

        // Use superclass implementation of 'toString', 'equals', and
        // 'hashCode'.
    }

    // There is no QualifiedReferenceType.  If we really need one, we can add
    // it to the hierarchy as an empty abstract class between
    // QualifiedTypeMirror and the qualified reference types (ExtendedArrayType,
    // ExtendedDeclaredType, ExtendedNullType, ExtendedTypeVariable).

    public static final class QualifiedTypeVariable<Q> extends QualifiedTypeMirror<Q> {
        private final QualifiedTypeMirror<Q> upperBound;
        private final QualifiedTypeMirror<Q> lowerBound;

        public QualifiedTypeVariable(ExtendedTypeMirror underlying, Q qualifier,
                QualifiedTypeMirror<Q> upperBound,
                QualifiedTypeMirror<Q> lowerBound) {
            super(underlying, qualifier);
            checkUnderlyingKind(underlying, TypeKind.TYPEVAR);
            checkTypeMirrorsMatch("upper bound",
                    upperBound, getUnderlyingType().getUpperBound());
            checkTypeMirrorsMatch("lower bound",
                    lowerBound, getUnderlyingType().getLowerBound());

            this.upperBound = upperBound;
            this.lowerBound = lowerBound;
        }

        @Override
        public <R,P> R accept(QualifiedTypeVisitor<Q,R,P> visitor, P p) {
            return visitor.visitTypeVariable(this, p);
        }

        public ExtendedTypeVariable getUnderlyingType() {
            return (ExtendedTypeVariable)super.getUnderlyingType();
        }

        public QualifiedTypeMirror<Q> getUpperBound() {
            return upperBound;
        }

        public QualifiedTypeMirror<Q> getLowerBound() {
            return lowerBound;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(getQualifier()).append(" ")
                    .append(getUnderlyingType().asElement().getSimpleName())
                    .append(" extends ").append(upperBound)
                    .append(" super ").append(lowerBound);
            return sb.toString();
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj))
                return false;
            // super.equals ensures that 'obj.getClass() == this.getClass()'.
            @SuppressWarnings("unchecked")
            QualifiedTypeVariable<Q> other = (QualifiedTypeVariable<Q>)obj;
            return this.upperBound.equals(other.upperBound)
                && this.lowerBound.equals(other.lowerBound);
        }

        @Override
        public int hashCode() {
            return super.hashCode()
                + upperBound.hashCode() * 43
                + lowerBound.hashCode() * 67;
        }
    }

    public static final class QualifiedUnionType<Q> extends QualifiedTypeMirror<Q> {
        private final List<? extends QualifiedTypeMirror<Q>> alternatives;

        public QualifiedUnionType(ExtendedTypeMirror underlying, Q qualifier,
                List<? extends QualifiedTypeMirror<Q>> alternatives) {
            super(underlying, qualifier);
            checkUnderlyingKind(underlying, TypeKind.UNION);
            checkTypeMirrorListsMatch("alternative",
                    alternatives, getUnderlyingType().getAlternatives());

            this.alternatives = new ArrayList<>(alternatives);
        }

        @Override
        public <R,P> R accept(QualifiedTypeVisitor<Q,R,P> visitor, P p) {
            return visitor.visitUnion(this, p);
        }

        public ExtendedUnionType getUnderlyingType() {
            return (ExtendedUnionType)super.getUnderlyingType();
        }

        public List<? extends QualifiedTypeMirror<Q>> getAlternatives() {
            return alternatives;
        }

        @Override
        public String toString() {
            return punctuatedList(" | ", alternatives);
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj))
                return false;
            // super.equals ensures that 'obj.getClass() == this.getClass()'.
            @SuppressWarnings("unchecked")
            QualifiedUnionType<Q> other = (QualifiedUnionType<Q>)obj;
            return this.alternatives.equals(other.alternatives);
        }

        @Override
        public int hashCode() {
            return super.hashCode()
                + alternatives.hashCode() * 43;
        }
    }

    public static final class QualifiedWildcardType<Q> extends QualifiedTypeMirror<Q> {
        private final QualifiedTypeMirror<Q> extendsBound;
        private final QualifiedTypeMirror<Q> superBound;

        public QualifiedWildcardType(ExtendedTypeMirror underlying, Q qualifier,
                QualifiedTypeMirror<Q> extendsBound,
                QualifiedTypeMirror<Q> superBound) {
            super(underlying, qualifier);
            checkUnderlyingKind(underlying, TypeKind.WILDCARD);
            checkTypeMirrorsMatch("extends bound",
                        extendsBound, getUnderlyingType().getExtendsBound());
            checkTypeMirrorsMatch("super bound",
                    superBound, getUnderlyingType().getSuperBound());

            this.extendsBound = extendsBound;
            this.superBound = superBound;
        }

        @Override
        public <R,P> R accept(QualifiedTypeVisitor<Q,R,P> visitor, P p) {
            return visitor.visitWildcard(this, p);
        }

        public ExtendedWildcardType getUnderlyingType() {
            return (ExtendedWildcardType)super.getUnderlyingType();
        }

        public QualifiedTypeMirror<Q> getExtendsBound() {
            return extendsBound;
        }

        public QualifiedTypeMirror<Q> getSuperBound() {
            return superBound;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(getQualifier()).append(" ?")
                    .append(" extends ").append(extendsBound)
                    .append(" super ").append(superBound);
            return sb.toString();
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj))
                return false;
            // super.equals ensures that 'obj.getClass() == this.getClass()'.
            @SuppressWarnings("unchecked")
            QualifiedWildcardType<Q> other = (QualifiedWildcardType<Q>)obj;
            return this.extendsBound.equals(other.extendsBound)
                && this.superBound.equals(other.superBound);
        }

        @Override
        public int hashCode() {
            return super.hashCode()
                + extendsBound.hashCode() * 43
                + superBound.hashCode() * 67;
        }
    }
}


