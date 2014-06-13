package org.checkerframework.qualframework.base;

import java.util.*;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;

import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedTypeVariable;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedWildcardType;
import org.checkerframework.framework.util.MultiGraphQualifierHierarchy;
import org.checkerframework.framework.util.MultiGraphQualifierHierarchy.MultiGraphFactory;
import org.checkerframework.javacutil.Pair;

import org.checkerframework.qualframework.util.WrappedAnnotatedTypeMirror;
import org.checkerframework.qualframework.base.QualifiedTypeMirror.QualifiedExecutableType;
import org.checkerframework.qualframework.base.QualifiedTypeMirror.QualifiedTypeVariable;
import org.checkerframework.qualframework.base.QualifiedTypeMirror.QualifiedParameterDeclaration;

/**
 * Adapter class for {@link QualifiedTypeFactory}, extending
 * {@link BaseAnnotatedTypeFactory BaseAnnotatedTypeFactory}.
 */
class QualifiedTypeFactoryAdapter<Q> extends BaseAnnotatedTypeFactory {
    /** The underlying {@link QualifiedTypeFactory}. */
    private QualifiedTypeFactory<Q> underlying;

    public QualifiedTypeFactoryAdapter(QualifiedTypeFactory<Q> underlying,
            CheckerAdapter<Q> checker) {
        super(checker, true);
        this.underlying = underlying;

        // We can't call postInit yet.  See CheckerAdapter.getTypeFactory for
        // explanation.
    }

    /** Allow CheckerAdapter to call postInit when it's ready.  See
     * CheckerAdapter.getTypeFactory for explanation.
     */
    void doPostInit() {
        this.postInit();
    }

    /** Returns the underlying {@link QualifiedTypeFactory}. */
    public QualifiedTypeFactory<Q> getUnderlying() {
        return underlying;
    }

    /** Returns {@link checker}, downcast to a more precise type. */
    @SuppressWarnings("unchecked")
    CheckerAdapter<Q> getCheckerAdapter() {
        return (CheckerAdapter<Q>)checker;
    }

    /** Returns the same result as {@link getQualifierHierarchy}, but downcast
     * to a more precise type. */
    @SuppressWarnings("unchecked")
    private QualifierHierarchyAdapter<Q>.Implementation getQualifierHierarchyAdapter() {
        return (QualifierHierarchyAdapter<Q>.Implementation)getQualifierHierarchy();
    }

    /** Returns the same result as {@link getTypeHierarchy}, but downcast to a
     * more precise type. */
    @SuppressWarnings("unchecked")
    private TypeHierarchyAdapter<Q> getTypeHierarchyAdapter() {
        return (TypeHierarchyAdapter<Q>)getTypeHierarchy();
    }

    @Override
    protected MultiGraphQualifierHierarchy.MultiGraphFactory createQualifierHierarchyFactory() {
        return new MultiGraphQualifierHierarchy.MultiGraphFactory(this);
    }

    @Override
    public MultiGraphQualifierHierarchy createQualifierHierarchy(MultiGraphFactory factory) {
        QualifierHierarchy<Q> underlyingHierarchy = underlying.getQualifierHierarchy();

        // See QualifierHierarchyAdapter for an explanation of why we need this
        // strange pattern instead of just making a single call to the
        // QualifierHierarchyAdapter constructor.
        QualifierHierarchyAdapter<Q>.Implementation adapter =
            new QualifierHierarchyAdapter<Q>(
                underlyingHierarchy,
                getCheckerAdapter().getTypeMirrorConverter())
            .createImplementation(factory);
        return adapter;
    }

    /* Constructs a TypeHierarchyAdapter for the underlying factory's
     * TypeHierarchy.
     */
    @Override
    protected org.checkerframework.framework.type.TypeHierarchy createTypeHierarchy() {
        TypeHierarchy<Q> underlyingHierarchy = underlying.getTypeHierarchy();
        TypeHierarchyAdapter<Q> adapter = new TypeHierarchyAdapter<Q>(
                underlyingHierarchy,
                getCheckerAdapter().getTypeMirrorConverter(),
                getCheckerAdapter(),
                getQualifierHierarchyAdapter());

        // TODO: Move this check (and others like it) into the adapter
        // constructor.
        if (underlyingHierarchy instanceof DefaultTypeHierarchy) {
            DefaultTypeHierarchy<Q> defaultHierarchy =
                (DefaultTypeHierarchy<Q>)underlyingHierarchy;
            defaultHierarchy.setAdapter(adapter);
        }

        return adapter;
    }

    /* Constructs a TreeAnnotatorAdapter for the underlying factory's
     * TreeAnnotator.
     */
    @Override
    protected org.checkerframework.framework.type.TreeAnnotator createTreeAnnotator() {
        if (!(underlying instanceof DefaultQualifiedTypeFactory)) {
            // In theory, the result of this branch should never be used.  Only
            // DefaultQTFs have a way to access the annotation-based logic
            // which requires the TreeAnnotator produced by this method.
            return null;
        }

        DefaultQualifiedTypeFactory<Q> defaultUnderlying =
            (DefaultQualifiedTypeFactory<Q>)underlying;
        TreeAnnotator<Q> underlyingAnnotator = defaultUnderlying.getTreeAnnotator();
        TreeAnnotatorAdapter<Q> adapter = new TreeAnnotatorAdapter<Q>(
                underlyingAnnotator,
                getCheckerAdapter().getTypeMirrorConverter(),
                this);

        underlyingAnnotator.setAdapter(adapter);

        return adapter;
    }

    /* Constructs a TypeAnnotatorAdapter for the underlying factory's
     * TypeAnnotator.
     */
    @Override
    protected org.checkerframework.framework.type.TypeAnnotator createTypeAnnotator() {
        if (!(underlying instanceof DefaultQualifiedTypeFactory)) {
            // In theory, the result of this branch should never be used.  Only
            // DefaultQTFs have a way to access the annotation-based logic
            // which requires the TypeAnnotator produced by this method.
            return null;
        }

        DefaultQualifiedTypeFactory<Q> defaultUnderlying =
            (DefaultQualifiedTypeFactory<Q>)underlying;
        TypeAnnotator<Q> underlyingAnnotator = defaultUnderlying.getTypeAnnotator();
        TypeAnnotatorAdapter<Q> adapter = new TypeAnnotatorAdapter<Q>(
                underlyingAnnotator,
                getCheckerAdapter().getTypeMirrorConverter(),
                this);

        underlyingAnnotator.setAdapter(adapter);

        return adapter;
    }


    @Override
    public boolean isSupportedQualifier(AnnotationMirror anno) {
        if (anno == null) {
            return false;
        }

        // If 'underlying' is not a DefaultQTF, there is no AnnotationConverter
        // for us to use for this check.
        if (!(underlying instanceof DefaultQualifiedTypeFactory)) {
            return true;
        }

        DefaultQualifiedTypeFactory<Q> defaultUnderlying = (DefaultQualifiedTypeFactory<Q>)underlying;
        AnnotationConverter<Q> annoConverter = defaultUnderlying.getAnnotationConverter();

        return annoConverter.isAnnotationSupported(anno)
            || getCheckerAdapter().getTypeMirrorConverter().isKey(anno);
    }


    @Override
    public AnnotatedTypeMirror getAnnotatedType(Element elt) {
        return getCheckerAdapter().getTypeMirrorConverter().getAnnotatedType(
                underlying.getQualifiedType(elt));
    }

    QualifiedTypeMirror<Q> superGetAnnotatedType(Element elt) {
        AnnotatedTypeMirror atm = super.getAnnotatedType(elt);
        typeAnnotator.visit(atm, null);
        return getCheckerAdapter().getTypeMirrorConverter().getQualifiedType(atm);
    }

    @Override
    public AnnotatedTypeMirror getAnnotatedType(Tree tree) {
        return getCheckerAdapter().getTypeMirrorConverter().getAnnotatedType(
                underlying.getQualifiedType(tree));
    }

    QualifiedTypeMirror<Q> superGetAnnotatedType(Tree tree) {
        AnnotatedTypeMirror atm = super.getAnnotatedType(tree);
        typeAnnotator.visit(atm, null);
        return getCheckerAdapter().getTypeMirrorConverter().getQualifiedType(atm);
    }

    @Override
    public AnnotatedTypeMirror getAnnotatedTypeFromTypeTree(Tree tree) {
        return getCheckerAdapter().getTypeMirrorConverter().getAnnotatedType(
                underlying.getQualifiedTypeFromTypeTree(tree));
    }

    QualifiedTypeMirror<Q> superGetAnnotatedTypeFromTypeTree(Tree tree) {
        AnnotatedTypeMirror atm = super.getAnnotatedTypeFromTypeTree(tree);
        typeAnnotator.visit(atm, null);
        return getCheckerAdapter().getTypeMirrorConverter().getQualifiedType(atm);
    }


    @Override
    public AnnotatedWildcardType getWildcardBoundedBy(AnnotatedTypeMirror upper) {
        // The superclass implementation of this method doesn't run the
        // TypeAnnotator, which means annotations won't get converted to
        // qualifier @Keys.  This causes problems later on, so we run the
        // TypeAnnotator manually here.
        AnnotatedWildcardType result = super.getWildcardBoundedBy(upper);
        typeAnnotator.scanAndReduce(result, null, null);
        return result;
    }

    @Override
    public AnnotatedWildcardType getUninferredWildcardType(AnnotatedTypeVariable var) {
        // Same logic as getWildcardBoundedBy.
        AnnotatedWildcardType result = super.getUninferredWildcardType(var);
        typeAnnotator.scanAndReduce(result, null, null);
        return result;
    }


    @Override
    public void postDirectSuperTypes(AnnotatedTypeMirror subtype, List<? extends AnnotatedTypeMirror> supertypes) {
        TypeMirrorConverter<Q> conv = getCheckerAdapter().getTypeMirrorConverter();

        QualifiedTypeMirror<Q> qualSubtype = conv.getQualifiedType(subtype);
        List<QualifiedTypeMirror<Q>> qualSupertypes = conv.getQualifiedTypeList(supertypes);

        List<QualifiedTypeMirror<Q>> qualResult = underlying.postDirectSuperTypes(qualSubtype, qualSupertypes);

        for (int i = 0; i < supertypes.size(); ++i) {
            conv.applyQualifiers(qualResult.get(i), supertypes.get(i));
        }
    }

    List<QualifiedTypeMirror<Q>> superPostDirectSuperTypes(QualifiedTypeMirror<Q> subtype, List<? extends QualifiedTypeMirror<Q>> supertypes) {
        TypeMirrorConverter<Q> conv = getCheckerAdapter().getTypeMirrorConverter();

        AnnotatedTypeMirror annoSubtype = conv.getAnnotatedType(subtype);
        List<AnnotatedTypeMirror> annoSupertypes = conv.getAnnotatedTypeList(supertypes);

        super.postDirectSuperTypes(annoSubtype, annoSupertypes);

        return conv.getQualifiedTypeList(annoSupertypes);
    }


    @Override
    public void postAsMemberOf(AnnotatedTypeMirror memberType, AnnotatedTypeMirror receiverType, Element memberElement) {
        TypeMirrorConverter<Q> conv = getCheckerAdapter().getTypeMirrorConverter();

        QualifiedTypeMirror<Q> qualMemberType = conv.getQualifiedType(memberType);
        QualifiedTypeMirror<Q> qualReceiverType = conv.getQualifiedType(receiverType);

        QualifiedTypeMirror<Q> qualResult = underlying.postAsMemberOf(
                qualMemberType, qualReceiverType, memberElement);

        conv.applyQualifiers(qualResult, memberType);
    }

    QualifiedTypeMirror<Q> superPostAsMemberOf(
            QualifiedTypeMirror<Q> memberType, QualifiedTypeMirror<Q> receiverType, Element memberElement) {
        TypeMirrorConverter<Q> conv = getCheckerAdapter().getTypeMirrorConverter();

        AnnotatedTypeMirror annoMemberType = conv.getAnnotatedType(memberType);
        AnnotatedTypeMirror annoReceiverType = conv.getAnnotatedType(receiverType);

        super.postAsMemberOf(annoMemberType, annoReceiverType, memberElement);

        QualifiedTypeMirror<Q> qualResult = conv.getQualifiedType(annoMemberType);
        return qualResult;
    }


    @Override
    public Pair<AnnotatedExecutableType, List<AnnotatedTypeMirror>> methodFromUse(MethodInvocationTree tree) {
        Pair<QualifiedExecutableType<Q>, List<QualifiedTypeMirror<Q>>> qualResult =
            underlying.methodFromUse(tree);

        TypeMirrorConverter<Q> conv = getCheckerAdapter().getTypeMirrorConverter();
        Pair<AnnotatedExecutableType, List<AnnotatedTypeMirror>> annoResult =
            Pair.of((AnnotatedExecutableType)conv.getAnnotatedType(qualResult.first),
                    conv.getAnnotatedTypeList(qualResult.second));

        return annoResult;
    }

    Pair<QualifiedExecutableType<Q>, List<QualifiedTypeMirror<Q>>> superMethodFromUse(MethodInvocationTree tree) {
        Pair<AnnotatedExecutableType, List<AnnotatedTypeMirror>> annoResult =
            super.methodFromUse(tree);

        TypeMirrorConverter<Q> conv = getCheckerAdapter().getTypeMirrorConverter();
        Pair<QualifiedExecutableType<Q>, List<QualifiedTypeMirror<Q>>> qualResult =
            Pair.of((QualifiedExecutableType<Q>)conv.getQualifiedType(annoResult.first),
                    conv.getQualifiedTypeList(annoResult.second));
        return qualResult;
    }


    public void postTypeVarSubstitution(AnnotatedTypeVariable varDecl,
            AnnotatedTypeVariable varUse, AnnotatedTypeMirror value) {
        TypeMirrorConverter<Q> conv = getCheckerAdapter().getTypeMirrorConverter();

        QualifiedParameterDeclaration<Q> qualVarDecl = (QualifiedParameterDeclaration<Q>)conv.getQualifiedType(varDecl);
        QualifiedTypeVariable<Q> qualVarUse = (QualifiedTypeVariable<Q>)conv.getQualifiedType(varUse);
        QualifiedTypeMirror<Q> qualValue = conv.getQualifiedType(value);

        QualifiedTypeMirror<Q> qualResult = underlying.postTypeVarSubstitution(
                qualVarDecl, qualVarUse, qualValue);

        conv.applyQualifiers(qualResult, value);
    }

    QualifiedTypeMirror<Q> superPostTypeVarSubstitution(QualifiedParameterDeclaration<Q> varDecl,
            QualifiedTypeVariable<Q> varUse, QualifiedTypeMirror<Q> value) {
        TypeMirrorConverter<Q> conv = getCheckerAdapter().getTypeMirrorConverter();

        AnnotatedTypeVariable annoVarDecl = (AnnotatedTypeVariable)conv.getAnnotatedType(varDecl);
        AnnotatedTypeVariable annoVarUse = (AnnotatedTypeVariable)conv.getAnnotatedType(varUse);
        AnnotatedTypeMirror annoValue = conv.getAnnotatedType(value);

        super.postTypeVarSubstitution(annoVarDecl, annoVarUse, annoValue);

        QualifiedTypeMirror<Q> qualResult = conv.getQualifiedType(annoValue);
        return qualResult;
    }
}
