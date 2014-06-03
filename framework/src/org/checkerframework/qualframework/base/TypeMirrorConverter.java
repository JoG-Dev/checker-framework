package org.checkerframework.qualframework.base;

import java.util.*;

import javax.annotation.processing.*;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;

import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.framework.qual.SubtypeOf;
import org.checkerframework.framework.qual.TypeQualifier;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.*;
import org.checkerframework.framework.type.visitor.SimpleAnnotatedTypeVisitor;
import org.checkerframework.framework.util.AnnotatedTypes;
import org.checkerframework.framework.util.AnnotationBuilder;

import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;

import org.checkerframework.qualframework.base.QualifiedTypeMirror.*;
import org.checkerframework.qualframework.util.ExtendedArrayType;
import org.checkerframework.qualframework.util.ExtendedDeclaredType;
import org.checkerframework.qualframework.util.ExtendedErrorType;
import org.checkerframework.qualframework.util.ExtendedExecutableType;
import org.checkerframework.qualframework.util.ExtendedIntersectionType;
import org.checkerframework.qualframework.util.ExtendedNoType;
import org.checkerframework.qualframework.util.ExtendedNullType;
import org.checkerframework.qualframework.util.ExtendedPrimitiveType;
import org.checkerframework.qualframework.util.ExtendedTypeVariable;
import org.checkerframework.qualframework.util.ExtendedUnionType;
import org.checkerframework.qualframework.util.ExtendedWildcardType;
import org.checkerframework.qualframework.util.ExtendedParameterDeclaration;
import org.checkerframework.qualframework.util.ExtendedTypeDeclaration;
import org.checkerframework.qualframework.util.ExtendedTypeMirror;
import org.checkerframework.qualframework.util.ExtendedTypeVisitor;
import org.checkerframework.qualframework.util.WrappedAnnotatedTypeMirror;
import org.checkerframework.qualframework.util.WrappedAnnotatedTypeMirror.*;

/**
 * Helper class used by adapters to convert between {@link QualifiedTypeMirror}
 * and {@link AnnotatedTypeMirror}.
 *
 * Only adapters should ever have a reference to this class.  All adapters for
 * a single type system must use the same {@link TypeMirrorConverter} instance,
 * since converting from {@link QualifiedTypeMirror} to {@link
 * AnnotatedTypeMirror} and back will fail if the two conversion steps are
 * performed with different instances.
 */
/* This class uses a lookup table and a special annotation '@Key' to encode
 * qualifiers as annotations.  Each '@Key' annotation contains a single index,
 * which is a key into the lookup table indicating a particular qualifier.
 */
class TypeMirrorConverter<Q> {
    /** The checker adapter, used for lazy initialization of {@link
     * typeFactory}. */
    private CheckerAdapter<Q> checkerAdapter;
    /** Annotation processing environment, used to construct new {@link Key}
     * {@link AnnotationMirror}s. */
    private ProcessingEnvironment processingEnv;
    /** The {@link Element} corresponding to the {@link Key.index} field. */
    private ExecutableElement indexElement;
    /** A {@link Key} annotation with no <code>index</code> set. */
    private AnnotationMirror blankKey;
    /** The type factory adapter, used to construct {@link
     * AnnotatedTypeMirror}s. */
    private QualifiedTypeFactoryAdapter<Q> typeFactory;

    /** The next unused index in the lookup table. */
    private int nextIndex = 0;

    /** The qualifier-to-index half of the lookup table.  This lets us ensure
     * that the same qualifier maps to the same <code>@Key</code> annotation.
     */
    private HashMap<Q, Integer> qualToIndex;
    /** The index-to-qualifier half of the lookup table.  This is used for
     * annotated-to-qualified conversions. */
    private HashMap<Integer, Q> indexToQual;

    /** Default qualifier to use for {@link QualifiedExecutableType}, when the
     * underlying Checker Framework loses track of the actual annotation. */
    Q executableDefault;

    @TypeQualifier
    @SubtypeOf({})
    public static @interface Key {
        /** An index into the lookup table. */
        int index() default -1;
        /** A string representation of the qualifier this {@link Key}
         * represents.  This lets us have slightly nicer error messages. */
        String desc() default "";
    }


    public TypeMirrorConverter(ProcessingEnvironment processingEnv, CheckerAdapter<Q> checkerAdapter,
            Q executableDefault) {
        this.checkerAdapter = checkerAdapter;
        this.processingEnv = processingEnv;
        this.indexElement = TreeUtils.getMethod(Key.class.getCanonicalName(), "index", 0, processingEnv);
        this.blankKey = AnnotationUtils.fromClass(processingEnv.getElementUtils(), Key.class);
        // typeFactory will be lazily initialized, to break a circular
        // dependency between this class and QualifiedTypeFactoryAdapter.
        this.typeFactory = null;

        this.qualToIndex = new HashMap<>();
        this.indexToQual = new HashMap<>();

        this.executableDefault = executableDefault;
    }

    /** Returns the type factory to use for building {@link
     * AnnotatedTypeMirror}s, running lazy initialization if necessary. */
    private QualifiedTypeFactoryAdapter<Q> getTypeFactory() {
        if (typeFactory == null) {
            typeFactory = checkerAdapter.getTypeFactory();
        }
        return typeFactory;
    }

    /** Constructs a new {@link Key} annotation with the provided index, using
     * <code>desc.toString()</code> to set the {@link Key.desc} field. */
    private AnnotationMirror createKey(int index, Object desc) {
        AnnotationBuilder builder = new AnnotationBuilder(processingEnv, Key.class.getCanonicalName());
        builder.setValue("index", index);
        builder.setValue("desc", "" + desc);
        return builder.build();
    }


    /** Returns the index that represents <code>qual</code>.  If
     * <code>qual</code> has not been assigned an index yet, a new index will
     * be generated and assigned to it.
     */
    private int getIndexForQualifier(Q qual) {
        if (qualToIndex.containsKey(qual)) {
            return qualToIndex.get(qual);
        } else {
            int index = nextIndex++;
            qualToIndex.put(qual, index);
            indexToQual.put(index, qual);
            return index;
        }
    }

    /** Returns the <code>index</code> field of a {@link Key} {@link
     * AnnotationMirror}. */
    private int getIndex(AnnotationMirror anno) {
        return getAnnotationField(anno, indexElement);
    }

    /** Helper function to obtain an integer field value from an {@link
     * AnnotationMirror}. */
    private int getAnnotationField(AnnotationMirror anno, ExecutableElement element) {
        AnnotationValue value = anno.getElementValues().get(element);
        if (value == null) {
            throw new IllegalArgumentException("@Key annotation contains no " + element);
        }
        assert(value.getValue() instanceof Integer);
        Integer index = (Integer)value.getValue();
        return index;
    }


    /* QTM -> ATM conversion functions */

    /** Given a QualifiedTypeMirror and an AnnotatedTypeMirror with the same
     * underlying TypeMirror, recursively update annotations on the
     * AnnotatedTypeMirror to match the qualifiers present on the
     * QualifiedTypeMirror.  After running applyQualifiers, calling
     * getQualifiedType on the AnnotatedTypeMirror should produce a
     * QualifiedTypeMirror which is identical to the one initially passed to
     * applyQualifiers.
     */
    public void applyQualifiers(QualifiedTypeMirror<Q> qtm, AnnotatedTypeMirror atm) {
        if (qtm == null && atm == null) {
            return;
        }
        assert qtm != null && atm != null;

        atm.clearAnnotations();

        if (qtm.getQualifier() != null) {
            // Apply the qualifier for this QTM-ATM pair.
            int index = getIndexForQualifier(qtm.getQualifier());
            AnnotationMirror key = createKey(index, qtm.getQualifier());

            atm.addAnnotation(key);
        }

        // Recursively create entries for all component QTM-ATM pairs.
        APPLY_COMPONENT_QUALIFIERS_VISITOR.visit(qtm, atm);
    }

    /** Given a QualifiedTypeMirror, produce an AnnotatedTypeMirror with the
     * same underlying TypeMirror and with annotations corresponding to the
     * qualifiers on the input QualifiedTypeMirror.  As with applyQualifiers,
     * calling getQualifiedType on the resulting AnnotatedTypeMirror should
     * produce a QualifiedTypeMirror that is identical to the one passed as
     * input.
     */
    public AnnotatedTypeMirror getAnnotatedType(QualifiedTypeMirror<Q> qtm) {
        if (qtm == null) {
            return null;
        }
        AnnotatedTypeMirror atm;
        if (qtm.getUnderlyingType() instanceof WrappedAnnotatedTypeMirror) {
            atm = AnnotatedTypes.deepCopy(
                    ((WrappedAnnotatedTypeMirror)qtm.getUnderlyingType()).unwrap());
        } else {
            atm = AnnotatedTypeMirror.createType(
                qtm.getUnderlyingType().getOriginalType(), getTypeFactory(),
                qtm.getUnderlyingType().isDeclaration());
        }
        applyQualifiers(qtm, atm);
        return atm;
    }

    public List<AnnotatedTypeMirror> getAnnotatedTypeList(List<? extends QualifiedTypeMirror<Q>> qtms) {
        if (qtms == null) {
            return null;
        }

        List<AnnotatedTypeMirror> atms = new ArrayList<AnnotatedTypeMirror>();
        for (QualifiedTypeMirror<Q> qtm : qtms) {
            atms.add(getAnnotatedType(qtm));
        }
        return atms;
    }


    /** applyQualifiers lifted to operate on lists of augmented TypeMirrors.
     */
    private void applyQualifiersToLists(List<? extends QualifiedTypeMirror<Q>> qtms, List<? extends AnnotatedTypeMirror> atms) {
        assert(qtms.size() == atms.size());
        for (int i = 0; i < qtms.size(); ++i) {
            applyQualifiers(qtms.get(i), atms.get(i));
        }
    }

    /** A visitor to recursively applyQualifiers to components of the
     * TypeMirrors.
     *
     * This also has some special handling for ExecutableTypes.  Both
     * AnnotatedTypeMirror and ExtendedTypeMirror (the underlying types used by
     * QualifiedTypeMirror) track the corresponding ExecutableElement (unlike
     * raw javac TypeMirrors), and this visitor updates that element if
     * necessary to make the augmented TypeMirrors correspond.
     */
    private SimpleQualifiedTypeVisitor<Q, Void, AnnotatedTypeMirror> APPLY_COMPONENT_QUALIFIERS_VISITOR =
        new SimpleQualifiedTypeVisitor<Q, Void, AnnotatedTypeMirror>() {
            private IdentityHashMap<AnnotatedTypeVariable, Void> seenATVs = new IdentityHashMap<>();

            public Void visitArray(QualifiedArrayType<Q> qtm, AnnotatedTypeMirror rawAtm) {
                AnnotatedArrayType atm = (AnnotatedArrayType)rawAtm;
                applyQualifiers(qtm.getComponentType(), atm.getComponentType());
                return null;
            }

            public Void visitDeclared(QualifiedDeclaredType<Q> qtm, AnnotatedTypeMirror rawAtm) {
                AnnotatedDeclaredType atm = (AnnotatedDeclaredType)rawAtm;
                applyQualifiersToLists(qtm.getTypeArguments(), atm.getTypeArguments());  
                return null;
            }

            public Void visitExecutable(QualifiedExecutableType<Q> qtm, AnnotatedTypeMirror rawAtm) {
                AnnotatedExecutableType atm = (AnnotatedExecutableType)rawAtm;

                // Update the ExecutableElement if necessary.  This should
                // happen before the recursive applyQualifiers calls, since it
                // may cause the receiver and return types of the ATM to become
                // non-null.
                ExecutableElement elt = qtm.getUnderlyingType().asElement();
                if (atm.getElement() != elt) {
                    assert elt != null;
                    atm.setElement(elt);
                }

                applyQualifiersToLists(qtm.getParameterTypes(), atm.getParameterTypes());
                applyQualifiers(qtm.getReceiverType(), atm.getReceiverType());
                applyQualifiers(qtm.getReturnType(), atm.getReturnType());
                applyQualifiersToLists(qtm.getThrownTypes(), atm.getThrownTypes());
                applyQualifiersToLists(qtm.getTypeParameters(), atm.getTypeVariables());
                return null;
            }

            public Void visitIntersection(QualifiedIntersectionType<Q> qtm, AnnotatedTypeMirror rawAtm) {
                AnnotatedIntersectionType atm = (AnnotatedIntersectionType)rawAtm;
                applyQualifiersToLists(qtm.getBounds(), atm.directSuperTypes());
                return null;
            }

            public Void visitNoType(QualifiedNoType<Q> qtm, AnnotatedTypeMirror rawAtm) {
                // NoType has no components.
                return null;
            }

            public Void visitNull(QualifiedNullType<Q> qtm, AnnotatedTypeMirror rawAtm) {
                // NullType has no components.
                return null;
            }

            public Void visitPrimitive(QualifiedPrimitiveType<Q> qtm, AnnotatedTypeMirror rawAtm) {
                // PrimitiveType has no components.
                return null;
            }

            public Void visitTypeVariable(QualifiedTypeVariable<Q> qtm, AnnotatedTypeMirror rawAtm) {
                AnnotatedTypeVariable atm = (AnnotatedTypeVariable)rawAtm;
                typeVariableHelper(qtm.getDeclaration(), atm);
                return null;
            }

            public Void visitUnion(QualifiedUnionType<Q> qtm, AnnotatedTypeMirror rawAtm) {
                AnnotatedUnionType atm = (AnnotatedUnionType)rawAtm;
                applyQualifiersToLists(qtm.getAlternatives(), atm.getAlternatives());
                return null;
            }

            public Void visitWildcard(QualifiedWildcardType<Q> qtm, AnnotatedTypeMirror rawAtm) {
                AnnotatedWildcardType atm = (AnnotatedWildcardType)rawAtm;
                applyQualifiers(qtm.getExtendsBound(), atm.getExtendsBound());
                applyQualifiers(qtm.getSuperBound(), atm.getSuperBound());
                return null;
            }

            private void typeVariableHelper(QualifiedParameterDeclaration<Q> qtm, AnnotatedTypeVariable atm) {
                if (!seenATVs.containsKey(atm)) {
                    seenATVs.put(atm, null);
                    QualifiedTypeParameterBounds<Q> bounds =
                        getTypeFactory().getUnderlying()
                            .getQualifiedTypeParameterBounds(qtm.getUnderlyingType());
                    try {
                        if (seenATVs.size() == 1) {
                            // We're at the top level.  Make sure both bounds
                            // are filled in.
                            applyQualifiers(bounds.getUpperBound(), atm.getUpperBound());
                            applyQualifiers(bounds.getLowerBound(), atm.getLowerBound());
                        } else {
                            if (atm.getUpperBoundField() != null) {
                                applyQualifiers(bounds.getUpperBound(),
                                        atm.getUpperBoundField());
                            }
                            if (atm.getLowerBoundField() != null) {
                                applyQualifiers(bounds.getLowerBound(),
                                        atm.getLowerBoundField());
                            }
                        }
                    } finally {
                        seenATVs.remove(atm);
                    }
                }
            }

            public Void visitParameterDeclaration(QualifiedParameterDeclaration<Q> qtm, AnnotatedTypeMirror rawAtm) {
                AnnotatedTypeVariable atm = (AnnotatedTypeVariable)rawAtm;
                typeVariableHelper(qtm, atm);
                return null;
            }

            public Void visitTypeDeclaration(QualifiedTypeDeclaration<Q> qtm, AnnotatedTypeMirror rawAtm) {
                AnnotatedDeclaredType atm = (AnnotatedDeclaredType)rawAtm;
                applyQualifiersToLists(qtm.getTypeParameters(), atm.getTypeArguments());  
                return null;
            }
        };


    /* ATM -> QTM conversion functions */

    /** Given an AnnotatedTypeMirror, construct a QualifiedTypeMirror with the
     * same underlying type with the qualifiers extracted from the
     * AnnotatedTypeMirror's @Key annotations.
     */
    public QualifiedTypeMirror<Q> getQualifiedType(AnnotatedTypeMirror atm) {
        if (atm == null) {
            return null;
        }
        return getQualifiedTypeFromWrapped(WrappedAnnotatedTypeMirror.wrap(atm));
    }

    public List<QualifiedTypeMirror<Q>> getQualifiedTypeList(List<? extends AnnotatedTypeMirror> atms) {
        if (atms == null) {
            return null;
        }

        List<QualifiedTypeMirror<Q>> qtms = new ArrayList<>();
        for (AnnotatedTypeMirror atm : atms) {
            qtms.add(getQualifiedType(atm));
        }
        return qtms;
    }

    private QualifiedTypeMirror<Q> getQualifiedTypeFromWrapped(WrappedAnnotatedTypeMirror watm) {
        if (watm == null) {
            return null;
        }
        return watm.accept(UPDATED_QTM_BUILDER, getQualifier(watm.unwrap()));
    }

    /** getQualifiedType lifted to operate on a list of AnnotatedTypeMirrors.
     */
    private List<QualifiedTypeMirror<Q>> getQualifiedTypeListFromWrapped(List<? extends ExtendedTypeMirror> watms) {
        List<QualifiedTypeMirror<Q>> result = new ArrayList<>();
        for (int i = 0; i < watms.size(); ++i) {
            result.add(getQualifiedTypeFromWrapped((WrappedAnnotatedTypeMirror)watms.get(i)));
        }
        return result;
    }

    /** A visitor to recursively construct QualifiedTypeMirrors from
     * AnnotatedTypeMirrors.
     */
    private ExtendedTypeVisitor<QualifiedTypeMirror<Q>, Q> UPDATED_QTM_BUILDER =
        new ExtendedTypeVisitor<QualifiedTypeMirror<Q>, Q>() {
            private IdentityHashMap<ExtendedTypeVariable, QualifiedTypeVariable<Q>> typeVarMap =
                new IdentityHashMap<>();

            public QualifiedTypeMirror<Q> visitArray(ExtendedArrayType extended, Q qual) {
                WrappedAnnotatedArrayType watm = (WrappedAnnotatedArrayType)extended;
                return new QualifiedArrayType<Q>(watm, qual,
                        getQualifiedTypeFromWrapped(watm.getComponentType()));
            }

            public QualifiedTypeMirror<Q> visitDeclared(ExtendedDeclaredType extended, Q qual) {
                WrappedAnnotatedDeclaredType watm = (WrappedAnnotatedDeclaredType)extended;
                return new QualifiedDeclaredType<Q>(watm, qual,
                        getQualifiedTypeListFromWrapped(watm.getTypeArguments()));
            }

            public QualifiedTypeMirror<Q> visitError(ExtendedErrorType extended, Q qual) {
                throw new IllegalArgumentException("impossible: ERROR type in WrappedAnnotatedTypeMirror");
            }

            public QualifiedTypeMirror<Q> visitExecutable(ExtendedExecutableType extended, Q qual) {
                WrappedAnnotatedExecutableType watm = (WrappedAnnotatedExecutableType)extended;

                List<? extends ExtendedParameterDeclaration> extendedParameters = watm.getTypeParameters();
                List<QualifiedParameterDeclaration<Q>> qualifiedParameters = new ArrayList<>();
                for (int i = 0; i < extendedParameters.size(); ++i) {
                    QualifiedParameterDeclaration<Q> qualified =
                        (QualifiedParameterDeclaration<Q>)getQualifiedTypeFromWrapped(
                                (WrappedAnnotatedTypeMirror)extendedParameters.get(i));
                    qualifiedParameters.add(qualified);
                }

                if (qual == null) {
                    qual = executableDefault;
                }

                return new QualifiedExecutableType<Q>(watm,
                        getQualifiedTypeListFromWrapped(watm.getParameterTypes()),
                        getQualifiedTypeFromWrapped(watm.getReceiverType()),
                        getQualifiedTypeFromWrapped(watm.getReturnType()),
                        getQualifiedTypeListFromWrapped(watm.getThrownTypes()),
                        qualifiedParameters);
            }

            public QualifiedTypeMirror<Q> visitIntersection(ExtendedIntersectionType extended, Q qual) {
                WrappedAnnotatedIntersectionType watm = (WrappedAnnotatedIntersectionType)extended;
                return new QualifiedIntersectionType<Q>(watm, qual,
                        getQualifiedTypeListFromWrapped(watm.getBounds()));
            }

            public QualifiedTypeMirror<Q> visitNoType(ExtendedNoType extended, Q qual) {
                // NoType has no components.
                return new QualifiedNoType<Q>(extended, qual);
            }

            public QualifiedTypeMirror<Q> visitNull(ExtendedNullType extended, Q qual) {
                // NullType has no components.
                return new QualifiedNullType<Q>(extended, qual);
            }

            public QualifiedTypeMirror<Q> visitPrimitive(ExtendedPrimitiveType extended, Q qual) {
                // PrimitiveType has no components.
                return new QualifiedPrimitiveType<Q>(extended, qual);
            }

            public QualifiedTypeMirror<Q> visitTypeVariable(ExtendedTypeVariable extended, Q qual) {
                WrappedAnnotatedTypeVariable watm = (WrappedAnnotatedTypeVariable)extended;
                return new QualifiedTypeVariable<Q>(watm, qual);
            }

            public QualifiedTypeMirror<Q> visitUnion(ExtendedUnionType extended, Q qual) {
                WrappedAnnotatedUnionType watm = (WrappedAnnotatedUnionType)extended;
                return new QualifiedUnionType<Q>(watm, qual,
                        getQualifiedTypeListFromWrapped(watm.getAlternatives()));
            }

            public QualifiedTypeMirror<Q> visitWildcard(ExtendedWildcardType extended, Q qual) {
                WrappedAnnotatedWildcardType watm = (WrappedAnnotatedWildcardType)extended;
                return new QualifiedWildcardType<Q>(watm,
                        getQualifiedTypeFromWrapped(watm.getExtendsBound()),
                        getQualifiedTypeFromWrapped(watm.getSuperBound()));
            }

            public QualifiedTypeMirror<Q> visitParameterDeclaration(ExtendedParameterDeclaration extended, Q qual) {
                WrappedAnnotatedTypeVariable watm = (WrappedAnnotatedTypeVariable)extended;
                return new QualifiedParameterDeclaration<Q>(watm);
            }

            public QualifiedTypeMirror<Q> visitTypeDeclaration(ExtendedTypeDeclaration extended, Q qual) {
                WrappedAnnotatedDeclaredType watm = (WrappedAnnotatedDeclaredType)extended;

                List<? extends ExtendedParameterDeclaration> extendedParameters = watm.getTypeParameters();
                List<QualifiedParameterDeclaration<Q>> qualifiedParameters = new ArrayList<>();
                for (int i = 0; i < extendedParameters.size(); ++i) {
                    QualifiedParameterDeclaration<Q> qualified =
                        (QualifiedParameterDeclaration<Q>)getQualifiedTypeFromWrapped(
                                (WrappedAnnotatedTypeVariable)extendedParameters.get(i));
                    qualifiedParameters.add(qualified);
                }

                return new QualifiedTypeDeclaration<Q>(watm, qual,
                        qualifiedParameters);
            }
        };


    /* Conversion functions between qualifiers and @Key AnnotationMirrors */

    /** Get the qualifier corresponding to a Key annotation.
     */
    public Q getQualifier(AnnotationMirror anno) {
        if (anno == null) {
            return null;
        }
        int index = getIndex(anno);
        return indexToQual.get(index);
    }

    /** Get the qualifier corresponding to the Key annotation present on an
     * AnnotatedTypeMirror, or null if no such annotation exists.
     */
    public Q getQualifier(AnnotatedTypeMirror atm) {
        return getQualifier(atm.getAnnotation(Key.class));
    }

    /** Get an AnnotationMirror for a Key annotation encoding the specified
     * qualifier.
     */
    public AnnotationMirror getAnnotation(Q qual) {
        return createKey(getIndexForQualifier(qual), qual);
    }


    /* Miscellaneous utility functions */

    /** Check if an AnnotationMirror is a valid Key annotation.
     */
    public boolean isKey(AnnotationMirror anno) {
        // TODO: This should probably also check that 'anno' has a value in its
        // 'index' field.
        return anno != null && blankKey.getAnnotationType().equals(anno.getAnnotationType());
    }

    /** Get a Key annotation containing no index.
     */
    public AnnotationMirror getBlankKeyAnnotation() {
        return blankKey;
    }
}
