package org.checkerframework.framework.type;

import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.framework.type.AnnotatedTypeMirror.*;
import org.checkerframework.framework.type.visitor.AnnotatedTypeVisitor;
import org.checkerframework.framework.util.DefaultAnnotationFormatter;
import org.checkerframework.framework.util.AnnotationFormatter;

import javax.lang.model.element.Element;
import javax.lang.model.type.TypeKind;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * An AnnotatedTypeFormatter used by default by all AnnotatedTypeFactory (and therefore all
 * annotated types).
 * @see org.checkerframework.framework.type.AnnotatedTypeFormatter
 * @see org.checkerframework.framework.type.AnnotatedTypeMirror#toString
 */
public class DefaultAnnotatedTypeFormatter implements AnnotatedTypeFormatter {
    protected final FormattingVisitor formattingVisitor;

    /**
     * Constructs a DefaultAnnotatedTypeFormatter that does not print invisible annotations by default
     */
    public DefaultAnnotatedTypeFormatter() {
        this(new DefaultAnnotationFormatter(), false);
    }

    /**
     * @param defaultPrintInvisibleAnnos Whether or not this AnnotatedTypeFormatter should print invisible annotations
     */
    public DefaultAnnotatedTypeFormatter(boolean defaultPrintInvisibleAnnos) {
        this(new DefaultAnnotationFormatter(), defaultPrintInvisibleAnnos);
    }

    /**
     * @param formatter An object that converts annotation mirrors to strings
     * @param defaultPrintInvisibleAnnos Whether or not this AnnotatedTypeFormatter should print invisible annotations
     */
    public DefaultAnnotatedTypeFormatter(AnnotationFormatter formatter, boolean defaultPrintInvisibleAnnos) {
        this(new FormattingVisitor(formatter, defaultPrintInvisibleAnnos));
    }

    /**
     * Used by subclasses and other constructors to specify the underlying implementation of
     * this DefaultAnnotatedTypeFormatter
     */
    protected DefaultAnnotatedTypeFormatter(FormattingVisitor visitor) {
        this.formattingVisitor = visitor;
    }

    public String format(final AnnotatedTypeMirror type) {
        formattingVisitor.resetPrintInvisibles();
        return formattingVisitor.visit(type);
    }

    public String format(final AnnotatedTypeMirror type, final boolean printInvisibles) {
        formattingVisitor.setCurrentPrintInvisibleSetting(printInvisibles);
        return formattingVisitor.visit(type);
    }

    /**
     * A scanning visitor that prints the the entire AnnotatedTypeMirror passed to visit.
     */
    protected static class FormattingVisitor implements AnnotatedTypeVisitor<String, Set<AnnotatedTypeMirror>> {

        /**
         * The object responsible for converting annotations to strings
         */
        protected final AnnotationFormatter annoFormatter;

        /**
         * Represents whether or not invisible annotations should be printed if the
         * client of this class does not use the printInvisibleAnnos parameter
         */
        protected final boolean defaultInvisiblesSetting;

        /**
         * For a given call to format, this setting specifies whether or not to printInvisibles.
         * If a user did not specify a printInvisible parameter in the call to format then this
         * value will equal DefaultAnnotatedTypeFormatter.defaultInvisibleSettings for this object
         */
        protected boolean currentPrintInvisibleSetting;

        public FormattingVisitor(AnnotationFormatter annoFormatter, boolean defaultInvisiblesSetting) {
            this.annoFormatter = annoFormatter;
            this.defaultInvisiblesSetting = defaultInvisiblesSetting;
            this.currentPrintInvisibleSetting = false;
        }

        /**
         * Set the current print invisible setting to use while printing
         */
        protected void setCurrentPrintInvisibleSetting(boolean printInvisibleSetting) {
            this.currentPrintInvisibleSetting = printInvisibleSetting;
        }

        /**
         * Set currentPrintInvisibleSettings to the default
         */
        protected void resetPrintInvisibles() {
            this.currentPrintInvisibleSetting = defaultInvisiblesSetting;
        }

        /** print to sb keyWord followed by field.  NULL types are substituted with
         * their annotations followed by " Void"
         */
        @SideEffectFree
        protected void printBound(final String keyWord, final AnnotatedTypeMirror field,
                                  final Set<AnnotatedTypeMirror> visiting, final StringBuilder sb) {
            sb.append(" ");
            sb.append(keyWord);
            sb.append(" ");

            if (field == null) {
                sb.append("<null>");
            } else if (field.getKind() != TypeKind.NULL) {
                sb.append(visit(field, visiting));
            } else {
                sb.append(annoFormatter.formatAnnotationString(field.getAnnotations(), currentPrintInvisibleSetting));
                sb.append("Void");
            }
        }

        @SideEffectFree
        @Override
        public String visit(AnnotatedTypeMirror type) {
            return type.accept(this, Collections.newSetFromMap(new IdentityHashMap<AnnotatedTypeMirror, Boolean>()));
        }

        @Override
        public String visit(AnnotatedTypeMirror type, Set<AnnotatedTypeMirror> annotatedTypeVariables) {
            return type.accept(this, annotatedTypeVariables);
        }

        @Override
        public String visitDeclared(AnnotatedDeclaredType type, Set<AnnotatedTypeMirror> visiting) {
            StringBuilder sb = new StringBuilder();
            if (type.isDeclaration()) {
                sb.append("/*DECL*/ ");
            }
            final Element typeElt = type.getUnderlyingType().asElement();
            String smpl = typeElt.getSimpleName().toString();
            if (smpl.isEmpty()) {
                // For anonymous classes smpl is empty - toString
                // of the element is more useful.
                smpl = typeElt.toString();
            }
            sb.append(annoFormatter.formatAnnotationString(type.getAnnotations(), currentPrintInvisibleSetting));
            sb.append(smpl);

            final List<AnnotatedTypeMirror> typeArgs = type.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                sb.append("<");

                boolean isFirst = true;
                for (AnnotatedTypeMirror typeArg : typeArgs) {
                    if (!isFirst) sb.append(", ");
                    sb.append(visit(typeArg, visiting));
                    isFirst = false;
                }
                sb.append(">");
            }
            return sb.toString();
        }

        @Override
        public String visitIntersection(AnnotatedIntersectionType type, Set<AnnotatedTypeMirror> visiting) {
            StringBuilder sb = new StringBuilder();

            boolean isFirst = true;
            for (AnnotatedDeclaredType adt : type.directSuperTypes()) {
                if (!isFirst) sb.append(" & ");
                sb.append(visit(adt, visiting));
                isFirst = false;
            }
            return sb.toString();
        }

        @Override
        public String visitUnion(AnnotatedUnionType type, Set<AnnotatedTypeMirror> visiting) {
            StringBuilder sb = new StringBuilder();

            boolean isFirst = true;
            for (AnnotatedDeclaredType adt : type.getAlternatives()) {
                if (!isFirst) sb.append(" | ");
                sb.append(visit(adt, visiting));
                isFirst = false;
            }
            return sb.toString();
        }

        @Override
        public String visitExecutable(AnnotatedExecutableType type, Set<AnnotatedTypeMirror> visiting) {
            StringBuilder sb = new StringBuilder();
            if (!type.getTypeVariables().isEmpty()) {
                sb.append('<');
                for (AnnotatedTypeVariable atv : type.getTypeVariables()) {
                    sb.append(visit(atv, visiting));
                }
                sb.append("> ");
            }
            if (type.getReturnType() != null) {
                sb.append(visit(type.getReturnType(), visiting));
            } else {
                sb.append("<UNKNOWNRETURN>");
            }
            sb.append(' ');
            if (type.getElement() != null) {
                sb.append(type.getElement().getSimpleName());
            } else {
                sb.append("METHOD");
            }
            sb.append('(');
            AnnotatedDeclaredType rcv = type.getReceiverType();
            if (rcv != null) {
                sb.append(visit(rcv, visiting));
                sb.append(" this");
            }
            if (!type.getParameterTypes().isEmpty()) {
                int p = 0;
                for (AnnotatedTypeMirror atm : type.getParameterTypes()) {
                    if (rcv != null ||
                            p > 0) {
                        sb.append(", ");
                    }
                    sb.append(visit(atm, visiting));
                    // Output some parameter names to make it look more like a method.
                    // TODO: go to the element and look up real parameter names, maybe.
                    sb.append(" p");
                    sb.append(p++);
                }
            }
            sb.append(')');
            if (!type.getThrownTypes().isEmpty()) {
                sb.append(" throws ");
                for (AnnotatedTypeMirror atm : type.getThrownTypes()) {
                    sb.append(visit(atm, visiting));
                }
            }
            return sb.toString();
        }

        @Override
        public String visitArray(AnnotatedArrayType type, Set<AnnotatedTypeMirror> visiting) {
            StringBuilder sb = new StringBuilder();

            AnnotatedArrayType array = type;
            AnnotatedTypeMirror component;
            while (true) {
                component = array.getComponentType();
                if (array.getAnnotations().size() > 0) {
                    sb.append(' ');
                    sb.append(annoFormatter.formatAnnotationString(array.getAnnotations(), currentPrintInvisibleSetting));
                }
                sb.append("[]");
                if (!(component instanceof AnnotatedArrayType)) {
                    sb.insert(0, visit(component, visiting));
                    break;
                }
                array = (AnnotatedArrayType) component;
            }
            return sb.toString();
        }

        @Override
        public String visitTypeVariable(AnnotatedTypeVariable type, Set<AnnotatedTypeMirror> visiting) {
            StringBuilder sb = new StringBuilder();
            if (type.isDeclaration()) {
                sb.append("/*DECL*/ ");
            }

            sb.append(type.actualType);
            if (!visiting.contains(type)) {
                try {
                    visiting.add(type);
                    sb.append("[");
                    printBound("extends", type.getUpperBoundField(), visiting, sb);
                    printBound("super", type.getLowerBoundField(), visiting, sb);
                    sb.append("]");
                } finally {
                    visiting.remove(type);
                }
            }
            return sb.toString();
        }

        @SideEffectFree
        @Override
        public String visitPrimitive(AnnotatedPrimitiveType type, Set<AnnotatedTypeMirror> visiting) {
            return formatFlatType(type);
        }

        @SideEffectFree
        @Override
        public String visitNoType(AnnotatedNoType type, Set<AnnotatedTypeMirror> visiting) {
            return formatFlatType(type);
        }

        @SideEffectFree
        @Override
        public String visitNull(AnnotatedNullType type, Set<AnnotatedTypeMirror> visiting) {
            return annoFormatter.formatAnnotationString(type.getAnnotations(), currentPrintInvisibleSetting) + " null";
        }

        @Override
        public String visitWildcard(AnnotatedWildcardType type, Set<AnnotatedTypeMirror> visiting) {
            StringBuilder sb = new StringBuilder();
            sb.append(annoFormatter.formatAnnotationString(type.annotations, currentPrintInvisibleSetting));
            sb.append("?");
            if (!visiting.contains(type)) {
                try {
                    visiting.add(type);
                    sb.append("[");
                    printBound("extends", type.getExtendsBoundField(), visiting, sb);
                    printBound("super", type.getSuperBoundField(), visiting, sb);
                    sb.append("]");
                } finally {
                    visiting.remove(type);
                }
            }
            return sb.toString();
        }

        @SideEffectFree
        protected String formatFlatType(final AnnotatedTypeMirror flatType) {
            return annoFormatter.formatAnnotationString(flatType.getAnnotations(), currentPrintInvisibleSetting) + flatType.actualType;
        }
    }
}
