package org.checkerframework.checker.qualparam;

import java.util.*;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeKind;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;

import org.checkerframework.javacutil.Pair;

import org.checkerframework.qualframework.base.QualifierHierarchy;
import org.checkerframework.qualframework.base.DefaultQualifiedTypeFactory;
import org.checkerframework.qualframework.base.QualifiedTypeMirror;
import org.checkerframework.qualframework.base.QualifierMapVisitor;
import org.checkerframework.qualframework.base.SetQualifierVisitor;
import org.checkerframework.qualframework.base.QualifiedTypeMirror.QualifiedExecutableType;
import org.checkerframework.qualframework.base.QualifiedTypeMirror.QualifiedParameterDeclaration;
import org.checkerframework.qualframework.base.QualifiedTypeMirror.QualifiedTypeVariable;
import org.checkerframework.qualframework.base.QualifiedTypeMirror.QualifiedWildcardType;

public abstract class QualifierParameterTypeFactory<Q> extends DefaultQualifiedTypeFactory<QualParams<Q>> {
    QualifierHierarchy<Q> groundHierarchy;

    @Override
    protected abstract QualifierParameterAnnotationConverter<Q> createAnnotationConverter();

    public QualifierParameterAnnotationConverter<Q> getAnnotationConverter() {
        return (QualifierParameterAnnotationConverter<Q>)super.getAnnotationConverter();
    }

    protected abstract QualifierHierarchy<Q> createGroundQualifierHierarchy();

    public QualifierHierarchy<Q> getGroundQualifierHierarchy() {
        if (groundHierarchy == null) {
            groundHierarchy = createGroundQualifierHierarchy();
        }
        return groundHierarchy;
    }

    @Override
    protected QualifierHierarchy<QualParams<Q>> createQualifierHierarchy() {
        return QualifierParameterHierarchy.fromGround(getGroundQualifierHierarchy());
    }

    @Override
    protected QualifierParameterTypeAnnotator<Q> createTypeAnnotator() {
        return new QualifierParameterTypeAnnotator<Q>(getAnnotationConverter(),
                new ContainmentHierarchy<>(new PolyQualHierarchy<>(getGroundQualifierHierarchy())));
    }

    /*
    public final QualParams<Q> applyCaptureConversion(QualParams<Q> objectQual) {
        if (objectQual == null || objectQual == QualParams.<Q>getBottom()
                || objectQual == QualParams.<Q>getTop())
            return objectQual;
        return objectQual.capture();
    }
    */
    public final QualParams<Q> qualifierAsMemberOf(QualParams<Q> memberQual, QualParams<Q> objectQual) {
        if (memberQual == null || memberQual == QualParams.<Q>getBottom()
                || memberQual == QualParams.<Q>getTop())
            return memberQual;
        if (objectQual == null || objectQual == QualParams.<Q>getBottom()
                || objectQual == QualParams.<Q>getTop())
            return memberQual;
        return memberQual.substituteAll(objectQual);
    }


    private QualifierMapVisitor<QualParams<Q>, QualParams<Q>, QualParams<Q>> AS_MEMBER_OF_VISITOR =
        new QualifierMapVisitor<QualParams<Q>, QualParams<Q>, QualParams<Q>>() {
            @Override
            public QualParams<Q> process(QualParams<Q> memberQual, QualParams<Q> objectQual) {
                return qualifierAsMemberOf(memberQual, objectQual);
            }
        };

    @Override
    public QualifiedTypeMirror<QualParams<Q>> postAsMemberOf(
            QualifiedTypeMirror<QualParams<Q>> memberType,
            QualifiedTypeMirror<QualParams<Q>> receiverType,
            Element memberElement) {
        return AS_MEMBER_OF_VISITOR.visit(memberType, receiverType.getQualifier());
    }

    private QualifierMapVisitor<QualParams<Q>, QualParams<Q>, Map<String, Wildcard<Q>>> SUBSTITUTE_VISITOR =
        new QualifierMapVisitor<QualParams<Q>, QualParams<Q>, Map<String, Wildcard<Q>>>() {
            @Override
            public QualParams<Q> process(QualParams<Q> params, Map<String, Wildcard<Q>> substs) {
                return params.substituteAll(substs);
            }
        };

    @Override
    public Pair<QualifiedExecutableType<QualParams<Q>>, List<QualifiedTypeMirror<QualParams<Q>>>> methodFromUse(MethodInvocationTree tree) {
        Pair<QualifiedExecutableType<QualParams<Q>>, List<QualifiedTypeMirror<QualParams<Q>>>> result = super.methodFromUse(tree);

        Set<String> qualParams = getAnnotationConverter().getDeclaredParameters(
                result.first.getUnderlyingType().asElement());
        if (qualParams.isEmpty()) {
            // This check is not just a performance optimization - it saves us
            // from crashing in one obscure corner case.  An `enum`
            // declarations gets an auto-generated constructor with an
            // auto-generated `super()` call.  But the actual java.lang.Enum
            // constructor takes two arguments.  So trying to do inference on
            // that super call will cause a crash.  (This problem shows up as
            // an IndexOutOfBoundsException in tests/all-systems/Enums.java.)
            // The constructor has no qualifier parameters, though, so we can
            // skip processing it using this check.
            return result;
        }

        List<? extends QualifiedTypeMirror<QualParams<Q>>> formals =
            getQualifiedTypes().expandVarArgs(result.first, tree.getArguments());
        List<QualifiedTypeMirror<QualParams<Q>>> actuals = new ArrayList<>();
        for (ExpressionTree actualExpr : tree.getArguments()) {
            actuals.add(getQualifiedType(actualExpr));
        }

        QualifierParameterHierarchy<Q> hierarchy = (QualifierParameterHierarchy<Q>)getQualifierHierarchy();
        InferenceContext<Q> inference = new InferenceContext<>(
                new ArrayList<>(qualParams), formals, actuals,
                groundHierarchy, new PolyQualHierarchy<>(groundHierarchy));
        inference.run(getTypeHierarchy(), hierarchy);

        Map<String, PolyQual<Q>> subst = inference.getAssignment();

        if (subst != null) {
            Map<String, Wildcard<Q>> wildSubst = new HashMap<>();
            for (String name : subst.keySet()) {
                wildSubst.put(name, new Wildcard<>(subst.get(name)));
            }

            QualifiedExecutableType<QualParams<Q>> newMethodType =
                (QualifiedExecutableType<QualParams<Q>>)SUBSTITUTE_VISITOR.visit(result.first, wildSubst);
            List<QualifiedTypeMirror<QualParams<Q>>> newTypeArgs = new ArrayList<>();
            for (QualifiedTypeMirror<QualParams<Q>> qtm : result.second) {
                newTypeArgs.add(SUBSTITUTE_VISITOR.visit(qtm, wildSubst));
            }
            result = Pair.of(newMethodType, newTypeArgs);
        } else {
            // TODO: report error
        }

        return result;
    }


    protected abstract Wildcard<Q> combineForSubstitution(Wildcard<Q> a, Wildcard<Q> b);

    @Override
    public QualifiedTypeMirror<QualParams<Q>> postTypeVarSubstitution(QualifiedParameterDeclaration<QualParams<Q>> varDecl,
            QualifiedTypeVariable<QualParams<Q>> varUse, QualifiedTypeMirror<QualParams<Q>> value) {
        if (value.getKind() == TypeKind.WILDCARD) {
            // Ideally we would never get a wildcard type as `value`, but
            // sometimes it happens due to checker framework misbehavior.
            // There are no top-level qualifiers on a wildcard type, so instead
            // we apply the combining to both the upper and lower bounds of the
            // wildcard.
            QualifiedWildcardType<QualParams<Q>> wild = (QualifiedWildcardType<QualParams<Q>>)value;
            QualifiedTypeMirror<QualParams<Q>> extendsBound = wild.getExtendsBound();
            QualifiedTypeMirror<QualParams<Q>> superBound = wild.getSuperBound();

            if (extendsBound != null) {
                extendsBound = postTypeVarSubstitution(varDecl, varUse, extendsBound);
            }

            if (superBound != null) {
                superBound = postTypeVarSubstitution(varDecl, varUse, superBound);
            }

            return new QualifiedWildcardType<QualParams<Q>>(
                    wild.getUnderlyingType(), extendsBound, superBound);
        }

        QualParams<Q> useParams = varUse.getQualifier();
        QualParams<Q> valueParams = value.getQualifier();

        HashMap<String, Wildcard<Q>> newParams = new HashMap<>(useParams);
        for (String name : valueParams.keySet()) {
            Wildcard<Q> newValue = valueParams.get(name);

            Wildcard<Q> oldValue = newParams.get(name);
            if (oldValue != null) {
                newValue = combineForSubstitution(oldValue, newValue);
            }

            newParams.put(name, newValue);
        }

        return value.accept(new SetQualifierVisitor<>(), new QualParams<>(newParams));
    }


    @Override
    public List<QualifiedTypeMirror<QualParams<Q>>> postDirectSuperTypes(
            QualifiedTypeMirror<QualParams<Q>> subtype,
            List<? extends QualifiedTypeMirror<QualParams<Q>>> supertypes) {
        QualParams<Q> subQuals = subtype.getQualifier();
        if (subQuals == null) {
            return new ArrayList<>(supertypes);
        }

        List<QualifiedTypeMirror<QualParams<Q>>> result = new ArrayList<>();
        for (QualifiedTypeMirror<QualParams<Q>> supertype : supertypes) {
            QualParams<Q> superQuals = supertype.getQualifier().substituteAll(subQuals);
            result.add(SetQualifierVisitor.apply(supertype, superQuals));
        }

        return result;
    }
}
