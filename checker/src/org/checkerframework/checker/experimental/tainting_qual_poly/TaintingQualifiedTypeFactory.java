package org.checkerframework.checker.experimental.tainting_qual_poly;

import java.util.ArrayList;

import javax.lang.model.type.TypeMirror;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.Tree;

import org.checkerframework.qualframework.base.DefaultQualifiedTypeFactory;
import org.checkerframework.qualframework.base.QualifierHierarchy;
import org.checkerframework.qualframework.base.QualifiedTypeMirror;
import org.checkerframework.qualframework.base.QualifiedTypeMirror.QualifiedDeclaredType;
import org.checkerframework.qualframework.base.SetQualifierVisitor;
import org.checkerframework.qualframework.poly.PolyQual;
import org.checkerframework.qualframework.poly.PolyQual.GroundQual;
import org.checkerframework.qualframework.util.ExtendedTypeMirror;

import org.checkerframework.qualframework.poly.CombiningOperation;
import org.checkerframework.qualframework.poly.QualifierParameterHierarchy;
import org.checkerframework.qualframework.poly.QualifierParameterTypeFactory;
import org.checkerframework.qualframework.poly.QualifierParameterTreeAnnotator;
import org.checkerframework.qualframework.poly.QualParams;
import org.checkerframework.qualframework.poly.Wildcard;
import org.checkerframework.qualframework.util.QualifierContext;

public class TaintingQualifiedTypeFactory extends QualifierParameterTypeFactory<Tainting> {

    public TaintingQualifiedTypeFactory(QualifierContext<QualParams<Tainting>> context) {
        super(context);
    }

    @Override
    protected QualifierHierarchy<Tainting> createGroundQualifierHierarchy() {
        return new TaintingQualifierHierarchy();
    }

    @Override
    protected TaintingAnnotationConverter createAnnotationConverter() {
        return new TaintingAnnotationConverter();
    }

    @Override
    protected QualifierParameterTreeAnnotator<Tainting> createTreeAnnotator() {
        return new QualifierParameterTreeAnnotator<Tainting>(this) {
            @Override
            public QualifiedTypeMirror<QualParams<Tainting>> visitLiteral(LiteralTree tree, ExtendedTypeMirror type) {
                QualifiedTypeMirror<QualParams<Tainting>> result = super.visitLiteral(tree, type);
                if (tree.getKind() == Tree.Kind.STRING_LITERAL) {
                    // TODO: Fix, this is a defaults thing
                    result = SetQualifierVisitor.apply(result, new QualParams<>(new GroundQual<>(Tainting.UNTAINTED)));
                }
                return result;
            }
        };
    }


    private CombiningOperation<Tainting> lubOp = new CombiningOperation.Lub<>(new TaintingQualifierHierarchy());

    @Override
    protected Wildcard<Tainting> combineForSubstitution(Wildcard<Tainting> a, Wildcard<Tainting> b) {
        return a.combineWith(b, lubOp, lubOp);
    }

    @Override
    protected PolyQual<Tainting> combineForSubstitution(PolyQual<Tainting> a, PolyQual<Tainting> b) {
        return a.combineWith(b, lubOp);
    }
}
