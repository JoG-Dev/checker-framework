package org.checkerframework.framework.base;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.TypeMirror;

import checkers.types.AnnotatedTypeMirror;
import checkers.types.AnnotatedTypeMirror.AnnotatedExecutableType;

import org.checkerframework.framework.util.ExtendedTypeMirror;
import org.checkerframework.framework.util.WrappedAnnotatedTypeMirror;

/**
 * Adapter for {@link TypeAnnotator}, extending
 * {@link checkers.types.TypeAnnotator checkers.types.TypeAnnotator}.
 */
class TypeAnnotatorAdapter<Q> extends checkers.types.TypeAnnotator {
    private TypeAnnotator<Q> underlying;
    private TypeMirrorConverter<Q> converter;

    public TypeAnnotatorAdapter(TypeAnnotator<Q> underlying,
            TypeMirrorConverter<Q> converter,
            QualifiedTypeFactoryAdapter<Q> factoryAdapter) {
        super(factoryAdapter);
        this.underlying = underlying;
        this.converter = converter;
    }

    /**
     * Return the qualifier indicated by the <code>@Key</code> annotation on an
     * {@link ExtendedTypeMirror}, or <code>null</code> if there is no such
     * annotation.  The default {@link TypeAnnotator} implementation uses this
     * method to avoid re-processing a type more than once, which will likely
     * produce wrong results (since {@link TypeMirrorConverter} removes the
     * existing annotations when it adds the <code>@Key</code>).
     */
    public Q getExistingQualifier(ExtendedTypeMirror type) {
        if (type instanceof WrappedAnnotatedTypeMirror) {
            AnnotatedTypeMirror atm = ((WrappedAnnotatedTypeMirror)type).unwrap();
            if (atm.hasAnnotation(TypeMirrorConverter.Key.class)) {
                return converter.getQualifier(atm);
            }
        }

        return null;
    }

    @Override
    protected Void scan(AnnotatedTypeMirror atm, Void p) {
        // Produce a qualified version of the ATM.
        WrappedAnnotatedTypeMirror watm = WrappedAnnotatedTypeMirror.wrap(atm);
        QualifiedTypeMirror<Q> qtm = underlying.visit(watm, null);

        // Update the input ATM with the new qualifiers.
        converter.applyQualifiers(qtm, atm);

        return null;
    }
}
