package org.checkerframework.framework.util;

/*>>>
import org.checkerframework.checker.nullness.qual.*;
*/

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import org.checkerframework.framework.flow.util.LubTypeVariableAnnotator;
import org.checkerframework.framework.qual.PolyAll;
import org.checkerframework.framework.qual.TypeQualifier;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.*;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.visitor.SimpleAnnotatedTypeVisitor;

import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.ErrorReporter;
import org.checkerframework.javacutil.InternalUtils;
import org.checkerframework.javacutil.Pair;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;

/**
 * Utility methods for operating on {@code AnnotatedTypeMirror}. This
 * class mimics the class {@link Types}.
 */
public class AnnotatedTypes {
    // Class cannot be instantiated.
    private AnnotatedTypes() { throw new AssertionError("Class AnnotatedTypes cannot be instantiated.");}

    /**
     * Returns the most specific base type of {@code t} whose erasure type
     * is {@code superType}.  It returns null if {@code t} is not a subtype
     * of {@code superType}.
     *
     * @param types the type utilities to use
     * @param atypeFactory the type factory to use
     * @param t      a type
     * @param superType   a type that is a supertype of {@code t}
     * @return the base type of t of the given element
     */
    public static AnnotatedTypeMirror asSuper(Types types, AnnotatedTypeFactory atypeFactory,
                                              AnnotatedTypeMirror t,
                                              AnnotatedTypeMirror superType) {
        if (asSuper == null ||
                asSuper.types != types ||
                asSuper.atypeFactory != atypeFactory) {
            asSuper = new AsSuperTypeVisitor(types, atypeFactory);
        }
        AnnotatedTypeMirror result = asSuper.visit(t, superType);
        return result;
    }

    private static AsSuperTypeVisitor asSuper;

    private static class AsSuperTypeVisitor extends SimpleAnnotatedTypeVisitor<AnnotatedTypeMirror, AnnotatedTypeMirror> {
        private final Types types;
        private final AnnotatedTypeFactory atypeFactory;

        AsSuperTypeVisitor(Types types, AnnotatedTypeFactory atypeFactory) {
            this.types = types;
            this.atypeFactory = atypeFactory;
        }

        @Override
        protected AnnotatedTypeMirror defaultAction(AnnotatedTypeMirror type, AnnotatedTypeMirror p) {
            return type;
        }

        @Override
        public AnnotatedTypeMirror visitPrimitive(AnnotatedPrimitiveType type, AnnotatedTypeMirror p) {
            if (!p.getKind().isPrimitive())
                return visit(atypeFactory.getBoxedType(type), p);

            AnnotatedPrimitiveType pt = (AnnotatedPrimitiveType)p;
            AnnotatedPrimitiveType st = pt.getCopy(false);
            st.addAnnotations(type.getAnnotations());
            return st;
        }

        @Override
        public AnnotatedTypeMirror visitTypeVariable(AnnotatedTypeVariable type, AnnotatedTypeMirror p) {
            if (p.getKind() == TypeKind.TYPEVAR)
                return type;
            // Operate on the effective upper bound
            AnnotatedTypeMirror res = asSuper(types, atypeFactory, type.getEffectiveUpperBound(), p);
            if (res != null) {
                res.addMissingAnnotations(atypeFactory.getQualifierHierarchy().getTopAnnotations());
                // TODO: or should it be the default?
                // Test MultiBoundTypeVar fails otherwise.
                // Is there a better place for this?
            }
            return res;
        }

        @Override
        public AnnotatedTypeMirror visitWildcard(AnnotatedWildcardType type, AnnotatedTypeMirror p) {
            if (p.getKind() == TypeKind.WILDCARD)
                return type;
            // Operate on the effective extends bound
            return asSuper(types, atypeFactory, type.getEffectiveExtendsBound(), p);
        }


        @Override
        public AnnotatedTypeMirror visitArray(AnnotatedArrayType type, AnnotatedTypeMirror p) {
            // Check if array component is subtype of the element
            // first
            if (shouldStop(p, type))
                return type;
            for (AnnotatedTypeMirror st : type.directSuperTypes()) {
                AnnotatedTypeMirror x = asSuper(types, atypeFactory, st, p);
                if (x != null) {
                    return isErased(types, x, p) ? x.getErased() : x;
                }
            }
            return null;
        }

        @Override
        public AnnotatedTypeMirror visitDeclared(AnnotatedDeclaredType type, AnnotatedTypeMirror p) {
            // If visited Element is the desired one, we are done
            if (p.getKind().isPrimitive()) {
                if (TypesUtils.isBoxedPrimitive(type.getUnderlyingType())) {
                    return visit(atypeFactory.getUnboxedType(type), p);
                } else {
                    // TODO: is there something better we could do?
                    // See tests/framework/Unboxing.java
                    return null;
                }
            }

            /* Something like the following seemed sensible for intersection types,
             * which came up in the Ternary test case with classes MethodSymbol and ClassSymbol.
             * However, it results in an infinite recursion with the IGJ Checker.
             * For now, let's handle the null result in the caller, TypeFromTree.visitConditionalExpression.
            if (p.getKind() == TypeKind.DECLARED &&
                    ((AnnotatedDeclaredType)p).getUnderlyingType().asElement().getSimpleName().length() == 0) {
                p = ((AnnotatedDeclaredType)p).directSuperTypes().get(0);
            }
            */

            if (shouldStop(p, type))
                return type;

            // Visit the superclass first!
            for (AnnotatedDeclaredType st : type.directSuperTypes()) {
                if (st.getKind() == TypeKind.DECLARED) {
                    AnnotatedDeclaredType x = (AnnotatedDeclaredType) asSuper(types, atypeFactory, st, p);
                    if (x != null) {
                        return x;
                    }
                }
            }

            if (p.getKind() == TypeKind.TYPEVAR) {
                return asSuper(types, atypeFactory, type, ((AnnotatedTypeVariable)p).getEffectiveUpperBound());
            }
            if (p.getKind() == TypeKind.WILDCARD) {
                return asSuper(types, atypeFactory, type, ((AnnotatedWildcardType)p).getEffectiveExtendsBound());
            }
            return null;
        }

        @Override
        public AnnotatedTypeMirror visitIntersection(AnnotatedIntersectionType type, AnnotatedTypeMirror p) {
            if (shouldStop(p, type))
                return type;

            for (AnnotatedDeclaredType st : type.directSuperTypes()) {
                AnnotatedDeclaredType x = (AnnotatedDeclaredType) asSuper(types, atypeFactory, st, p);
                if (x != null) {
                    return x;
                }
            }

            return null;
        }
    };

    /**
     * Return the base type of t or any of its outer types that starts
     * with the given type. If none exists, return null.
     *
     * @param t     a type
     * @param elem   a type
     */
    private static AnnotatedTypeMirror asOuterSuper(Types types, AnnotatedTypeFactory atypeFactory, AnnotatedTypeMirror t,
                                                    AnnotatedTypeMirror elem) {
        switch (t.getKind()) {
            case DECLARED:
                AnnotatedDeclaredType dt = (AnnotatedDeclaredType) t;
                do {
                    // Search among supers for a desired supertype
                    AnnotatedTypeMirror s = asSuper(types, atypeFactory, dt, elem);
                    if (s != null)
                        return s;
                    // if not found immediately, try enclosing type
                    // like A in A.B
                    dt = dt.getEnclosingType();
                } while (dt != null);
                return null;
            case ARRAY:     // intentional follow-through
            case TYPEVAR:   // intentional follow-through
            case WILDCARD:
                return asSuper(types, atypeFactory, t, elem);
            default:
                return null;
        }
    }

    /*
     * Returns true if sup and sub are the same type.
     * Returns false otherwise (including if sub cannot be a subtype of sup).
     */
    private static boolean shouldStop(AnnotatedTypeMirror sup, AnnotatedTypeMirror sub) {
        // Check if it's the same type
        // if sup is primitive, but not sub
        if (sup.getKind().isPrimitive() && !sub.getKind().isPrimitive())
            /// XXX shouldn't this be "return false"?
            return true;
        if (sup.getKind().isPrimitive() && sub.getKind().isPrimitive())
            return sup.getKind() == sub.getKind();
        // if both are declared
        if (sup.getKind() == TypeKind.DECLARED && sub.getKind() == TypeKind.DECLARED) {
            AnnotatedDeclaredType supdt = (AnnotatedDeclaredType) sup;
            AnnotatedDeclaredType subdt = (AnnotatedDeclaredType) sub;

            // Check if it's the same name
            if (!supdt.getUnderlyingType().asElement().equals(
                    subdt.getUnderlyingType().asElement()))
                return false;

            return true;
        }

        if (sup.getKind() == TypeKind.ARRAY && sub.getKind() == TypeKind.ARRAY) {
            AnnotatedArrayType supat = (AnnotatedArrayType) sup;
            AnnotatedArrayType subat = (AnnotatedArrayType) sub;
            return shouldStop(supat.getComponentType(), subat.getComponentType());
        }
        // horrible horrible hack
        // Types.isSameType() doesn't work for type variables or wildcards
        return sup.getUnderlyingType().toString().equals(sub.getUnderlyingType().toString());
    }

    /**
     * Tests that t2 is the erased type of t2
     *
     * @return true iff t2 is erased type of t1
     */
    private static boolean isErased(Types types, AnnotatedTypeMirror t1, AnnotatedTypeMirror t2) {
        return types.isSameType(
                types.erasure(t1.getUnderlyingType()), t2.getUnderlyingType());
    }

    /**
     * @see #asMemberOf(Types, AnnotatedTypeFactory, AnnotatedTypeMirror, Element)
     */
    public static AnnotatedExecutableType asMemberOf(Types types, AnnotatedTypeFactory atypeFactory, AnnotatedTypeMirror t,
                                                     ExecutableElement elem) {
        return (AnnotatedExecutableType) asMemberOf(types, atypeFactory, t, (Element) elem);
    }

    /**
     * Returns the type of an element when that element is viewed as a member
     * of, or otherwise directly contained by, a given type.
     *
     * For example, when viewed as a member of the parameterized type
     * {@code Set<@NonNull String>}, the {@code Set.add} method is an
     * {@code ExecutableType} whose parameter is of type
     * {@code @NonNull String}.
     *
     * The result is customized according to the type system semantics,
     * according to {@link AnnotatedTypeFactory#postAsMemberOf(
     * AnnotatedTypeMirror, AnnotatedTypeMirror, Element)}.
     *
     * @param t    a type
     * @param elem  an element
     */
    public static AnnotatedTypeMirror asMemberOf(Types types, AnnotatedTypeFactory atypeFactory,
                                                 AnnotatedTypeMirror t, Element elem) {
        // asMemberOf is only for fields, variables, and methods!
        // Otherwise, simply use fromElement.
        switch (elem.getKind()) {
            case PACKAGE:
            case INSTANCE_INIT:
            case OTHER:
            case STATIC_INIT:
            case TYPE_PARAMETER:
                return atypeFactory.fromElement(elem);
            default:
                AnnotatedTypeMirror type = asMemberOfImpl(types, atypeFactory, t, elem);
                if (!ElementUtils.isStatic(elem))
                    atypeFactory.postAsMemberOf(type, t, elem);
                return type;
        }
    }

    private static AnnotatedTypeMirror asMemberOfImpl(final Types types, final AnnotatedTypeFactory atypeFactory,
                                                      final AnnotatedTypeMirror t, final Element elem) {
        if (ElementUtils.isStatic(elem)) {
            return atypeFactory.getAnnotatedType(elem);
        }

        // For type variables and wildcards, operate on the upper bound
        if (t.getKind() == TypeKind.TYPEVAR &&
                ((AnnotatedTypeVariable)t).getUpperBound() != null) {
            return asMemberOf(types, atypeFactory, ((AnnotatedTypeVariable) t).getEffectiveUpperBound(),
                    elem);
        }
        if (t.getKind() == TypeKind.WILDCARD &&
                ((AnnotatedWildcardType)t).getExtendsBound() != null) {
            return asMemberOf(types, atypeFactory, ((AnnotatedWildcardType) t).getEffectiveExtendsBound(),
                    elem);
        }
        if (t.getKind() == TypeKind.ARRAY
                && elem.getKind() == ElementKind.METHOD
                && elem.getSimpleName().contentEquals("clone")) {
            AnnotatedExecutableType method = (AnnotatedExecutableType) atypeFactory.getAnnotatedType(elem);
            return method.substitute(Collections.singletonMap(method.getReturnType(), t));
        }

        final AnnotatedTypeMirror elemType = atypeFactory.getAnnotatedType(elem);

        // t.getKind() may be a TypeKind.ARRAY for Array.length calls.
        // We don't _think_ there are any other cases where t.getKind() != TypeKind.DECLARED
        if (t.getKind() != TypeKind.DECLARED) {
            return elemType;
        }

        // Basic Algorithm:
        // 1. Find the owner of the element
        // 2. Find the base type of owner (e.g. type of owner as supertype
        //      of passed type)
        // 3. Substitute for type variables if any exist
        TypeElement owner = ElementUtils.enclosingClass(elem);
        // Is the owner or any enclosing class generic?
        boolean ownerGeneric = false;
        {
            TypeElement encl = owner;
            while (encl != null) {
                if (!encl.getTypeParameters().isEmpty()) {
                    ownerGeneric = true;
                    break;
                }
                encl = ElementUtils.enclosingClass(encl.getEnclosingElement());
            }
        }

        // TODO: Potential bug if Raw type is used
        if (!ownerGeneric) {
            return elemType;
        }

        AnnotatedDeclaredType ownerType = atypeFactory.getAnnotatedType(owner);
        AnnotatedDeclaredType base =
                (AnnotatedDeclaredType) asOuterSuper(types, atypeFactory, t, ownerType);

        if (base == null) {
            return elemType;
        }

        List<? extends AnnotatedTypeMirror> ownerParams =
                ownerType.getTypeArguments();
        List<? extends AnnotatedTypeMirror> baseParams =
                base.getTypeArguments();
        if (!ownerParams.isEmpty()) {
            if (baseParams.isEmpty()) {
                List<AnnotatedTypeMirror> baseParamsEr = new ArrayList<>();
                for (AnnotatedTypeMirror arg : ownerParams) {
                    baseParamsEr.add(arg.getErased());
                }
                return subst(elemType, ownerParams, baseParamsEr);
            }
            return subst(elemType, ownerParams, baseParams);
        }

        return elemType;
    }

    /**
     * Returns a new type, a copy of the passed {@code t}, with all
     * instances of {@code from} type substituted with their correspondents
     * in {@code to}.
     *
     * @param t     the type
     * @param from  the from types
     * @param to    the to types
     * @return  the new type after substitutions
     */
    public static AnnotatedTypeMirror subst(AnnotatedTypeMirror t,
                                            List<? extends AnnotatedTypeMirror> from,
                                            List<? extends AnnotatedTypeMirror> to) {
        Map<AnnotatedTypeMirror, AnnotatedTypeMirror> mappings = new HashMap<>();

        for (int i = 0; i < from.size(); ++i) {
            mappings.put(from.get(i), to.get(i));
        }
        return t.substitute(mappings);
    }


    /**
     * Returns a deep copy of the passed type.
     *
     * @param type  the annotated type to be copied
     * @return a deep copy of the passed type
     */
    @SuppressWarnings("unchecked")
    public static <ATM extends AnnotatedTypeMirror> ATM deepCopy(ATM type) {
        // TODO: Test this, specify behavior, merge/compare to ATM.copy
        ATM result = (ATM) type.substitute(Collections.<AnnotatedTypeMirror,
                AnnotatedTypeMirror>emptyMap(), true);
        return result;
    }

    /**
     * Returns the iterated type of the passed iterable type, and throws
     * {@link IllegalArgumentException} if the passed type is not iterable.
     *
     * The iterated type is the component type of an array, and the type
     * argument of {@link Iterable} for declared types.
     *
     * @param iterableType  the iterable type (either array or declared)
     * @return the types of elements in the iterable type
     */
    public static AnnotatedTypeMirror getIteratedType(ProcessingEnvironment processingEnv,
                                                      AnnotatedTypeFactory atypeFactory,
                                                      AnnotatedTypeMirror iterableType) {
        if (iterableType.getKind() == TypeKind.ARRAY) {
            return ((AnnotatedArrayType) iterableType).getComponentType();
        }

        // For type variables and wildcards take the effective upper bound.
        if (iterableType.getKind() == TypeKind.WILDCARD)
            return getIteratedType(processingEnv, atypeFactory,
                    ((AnnotatedWildcardType) iterableType).getEffectiveExtendsBound());
        if (iterableType.getKind() == TypeKind.TYPEVAR)
            return getIteratedType(processingEnv, atypeFactory,
                    ((AnnotatedTypeVariable) iterableType).getEffectiveUpperBound());

        if (iterableType.getKind() != TypeKind.DECLARED) {
            ErrorReporter.errorAbort("AnnotatedTypes.getIteratedType: not iterable type: " + iterableType);
            return null; // dead code
        }

        TypeElement iterableElement = processingEnv.getElementUtils().getTypeElement("java.lang.Iterable");
        AnnotatedDeclaredType iterableElmType = atypeFactory.getAnnotatedType(iterableElement);
        AnnotatedDeclaredType dt = (AnnotatedDeclaredType) asSuper(processingEnv.getTypeUtils(), atypeFactory, iterableType, iterableElmType);
        if (dt == null) {
            ErrorReporter.errorAbort("AnnotatedTypes.getIteratedType: not an iterable type: " + iterableType);
            return null; // dead code
        } else if (dt.getTypeArguments().isEmpty()) {
            TypeElement e = processingEnv.getElementUtils().getTypeElement("java.lang.Object");
            AnnotatedDeclaredType t = atypeFactory.getAnnotatedType(e);
            return t;
        } else {
            return dt.getTypeArguments().get(0);
        }
    }

    /**
     * Returns all the super types of the given declared type.
     *
     * @param type a declared type
     * @return  all the supertypes of the given type
     */
    public static Set<AnnotatedDeclaredType> getSuperTypes(AnnotatedDeclaredType type) {

        Set<AnnotatedDeclaredType> supertypes = new HashSet<>();
        if (type == null)
            return supertypes;

        // Set up a stack containing the type mirror of subtype, which
        // is our starting point.
        Deque<AnnotatedDeclaredType> stack = new ArrayDeque<>();
        stack.push(type);

        while (!stack.isEmpty()) {
            AnnotatedDeclaredType current = stack.pop();

            // For each direct supertype of the current type, if it
            // hasn't already been visited, push it onto the stack and
            // add it to our supertypes set.
            for (AnnotatedDeclaredType supertype : current.directSuperTypes()) {
                if (!supertypes.contains(supertype)) {
                    stack.push(supertype);
                    supertypes.add(supertype);
                }
            }
        }

        return Collections.<AnnotatedDeclaredType>unmodifiableSet(supertypes);
    }

    /**
     * A utility method that takes a Method element and returns a set
     * of all elements that this method overrides (as
     * {@link ExecutableElement}s)
     *
     * @param method
     *            the overriding method
     * @return an unmodifiable set of {@link ExecutableElement}s
     *         representing the elements that method overrides
     */
    public static Map<AnnotatedDeclaredType, ExecutableElement> overriddenMethods(
            Elements elements,
            AnnotatedTypeFactory atypeFactory,
            ExecutableElement method) {
        final TypeElement elem = (TypeElement) method.getEnclosingElement();
        final AnnotatedDeclaredType type = atypeFactory.getAnnotatedType(elem);
        final Collection<AnnotatedDeclaredType> supertypes = getSuperTypes(type);
        return overriddenMethods(elements, method, supertypes);
    }

    /**
     * A utility method that takes the element for a method and the
     * set of all supertypes of the method's containing class and
     * returns the set of all elements that method overrides (as
     * {@link ExecutableElement}s).
     *
     * @param method
     *            the overriding method
     * @param supertypes
     *            the set of supertypes to check for methods that are
     *            overridden by {@code method}
     * @return an unmodified set of {@link ExecutableElement}s
     *         representing the elements that {@code method} overrides
     *         among {@code supertypes}
     */
    public static Map<AnnotatedDeclaredType, ExecutableElement> overriddenMethods(
            Elements elements,
            ExecutableElement method, Collection<AnnotatedDeclaredType> supertypes) {

        Map<AnnotatedDeclaredType, ExecutableElement> overrides = new HashMap<>();

        for (AnnotatedDeclaredType supertype : supertypes) {
            /*@Nullable*/ TypeElement superElement =
                    (TypeElement) supertype.getUnderlyingType().asElement();
            assert superElement != null; /*nninvariant*/
            // For all method in the supertype, add it to the set if
            // it overrides the given method.
            for (ExecutableElement supermethod : ElementFilter.methodsIn(superElement.getEnclosedElements())) {
                if (elements.overrides(method, supermethod,
                        superElement)) {
                    overrides.put(supertype, supermethod);
                    break;
                }
            }
        }

        return Collections.</*@NonNull*/ AnnotatedDeclaredType,
            /*@NonNull*/ ExecutableElement>unmodifiableMap(overrides);
    }

    /**
     * Given a method or constructor invocation, return a mapping
     * of the type variables to their type arguments, if any exist.
     *
     * It uses the method or constructor invocation type arguments if they
     * were specified and otherwise it infers them based on the passed arguments
     * or the return type context, according to JLS 15.12.2.
     *
     * @param the processing environment
     * @param atypeFactory the annotated type factory
     * @param expr the method or constructor invocation tree; the passed argument
     *   has to be a subtype of MethodInvocationTree or NewClassTree.
     * @param elt the element corresponding to the tree.
     * @param preType the (partially annotated) type corresponding to the tree -
     *   the result of AnnotatedTypes.asMemberOf with the receiver and elt.
     *
     * @return the mapping of the type variables to type arguments for
     *   this method or constructor invocation.
     */
    public static Map<AnnotatedTypeVariable, AnnotatedTypeMirror>
    findTypeArguments(final ProcessingEnvironment processingEnv,
                      final AnnotatedTypeFactory atypeFactory,
                      final ExpressionTree expr,
                      final ExecutableElement elt,
                      final AnnotatedExecutableType preType) {

        // Is the method a generic method?
        if (elt.getTypeParameters().isEmpty()) {
            return Collections.emptyMap();
        }

        List<? extends Tree> targs;
        if (expr instanceof MethodInvocationTree) {
            targs = ((MethodInvocationTree) expr).getTypeArguments();
        } else if (expr instanceof NewClassTree) {
            targs = ((NewClassTree) expr).getTypeArguments();
        } else {
            // This case should never happen.
            ErrorReporter.errorAbort("AnnotatedTypes.findTypeArguments: unexpected tree: " + expr);
            return null; // dead code
        }

        // Has the user supplied type arguments?
        if (!targs.isEmpty()) {
            List<? extends AnnotatedTypeVariable> tvars = preType.getTypeVariables();

            Map<AnnotatedTypeVariable, AnnotatedTypeMirror> typeArguments = new HashMap<>();
            for (int i = 0; i < elt.getTypeParameters().size(); ++i) {
                AnnotatedTypeVariable typeVar = tvars.get(i);
                AnnotatedTypeMirror typeArg = atypeFactory.getAnnotatedTypeFromTypeTree(targs.get(i));
                // TODO: the call to getTypeParameterDeclaration shouldn't be necessary - typeVar already
                // should be a declaration.
                typeArguments.put(typeVar.getTypeParameterDeclaration(), typeArg);
            }
            return typeArguments;
        } else {
            Map<AnnotatedTypeVariable, AnnotatedTypeMirror> typeArguments =
                    inferTypeArguments(processingEnv, atypeFactory, expr, elt, preType);
            return typeArguments;
        }
    }


    /**
     * Infer the method or constructor invocation type arguments based on the return type
     * context and the passed arguments.
     * No type arguments are given in the tree and they need to be inferred.
     *
     * See <a href="http://docs.oracle.com/javase/specs/jls/se7/html/jls-15.html#jls-15.12.2.7">JLS7 15.12.2.7</a>
     * and <a href="http://docs.oracle.com/javase/specs/jls/se8/html/jls-18.html">JLS8 18</a>.
     *
     * Eventually, we will need a better inference implementation, maybe based
     * on com.sun.tools.javac.comp.Infer.
     *
     * @param processingEnv the processing environment
     * @param atypeFactory the annotated type factory
     * @param expr the method or constructor invocation tree; the passed argument
     *   has to be a subtype of MethodInvocationTree or NewClassTree.
     * @param elt the element corresponding to the tree.
     * @param preType the (partially annotated) type corresponding to the tree -
     *   the result of AnnotatedTypes.asMemberOf with the receiver and elt.
     *
     * @return the mapping of the type variables to type arguments for
     *   this method or constructor invocation.
     */
    private static Map<AnnotatedTypeVariable, AnnotatedTypeMirror>
    inferTypeArguments(final ProcessingEnvironment processingEnv,
                       final AnnotatedTypeFactory atypeFactory,
                       final ExpressionTree expr,
                       final ExecutableElement elt,
                       final AnnotatedExecutableType preType) {
        final Types types = processingEnv.getTypeUtils();

        // Assignment context
        final AnnotatedTypeMirror assigned =
                assignedTo(types, atypeFactory, atypeFactory.getPath(expr));

        final AnnotatedTypeMirror returnType = preType.getReturnType();

        final AnnotatedTypeMirror returnTypeAsAssigned;
        if (assigned != null) {
            if (assigned.getKind() == TypeKind.TYPEVAR) {
                if (assigned.getAnnotations().isEmpty()) {
                    returnTypeAsAssigned = returnType;
                } else {
                    // TODO do we need to use partial annotations?
                    // E.g. in @NonNull T only ignore nullness
                    returnTypeAsAssigned = null;
                }
            } else {
                returnTypeAsAssigned = asSuper(types, atypeFactory, returnType, assigned);
            }
        } else {
            returnTypeAsAssigned = null;
        }

        Map<AnnotatedTypeVariable, AnnotatedTypeMirror> typeArgumentsFromAssignment;
        if (returnTypeAsAssigned != null) {
            typeArgumentsFromAssignment = matchTypeVars(assigned, returnTypeAsAssigned);
        } else {
            if (assigned != null &&
                    returnType.getKind() == TypeKind.TYPEVAR &&
                    preType.getTypeVariables().contains(returnType)) {
                // Find the defaulted return type: this is the type from the
                // Tree, with defaults applied.
                final AnnotatedTypeMirror basicReturnType = atypeFactory.type(expr);
                atypeFactory.annotateImplicit(expr, basicReturnType);
                AnnotatedTypeMirror ret = assigned.getCopy(true);
                ret.replaceAnnotations(basicReturnType.getAnnotations());
                typeArgumentsFromAssignment = Collections.singletonMap(((AnnotatedTypeVariable) returnType).getTypeParameterDeclaration(), ret);
            } else {
                typeArgumentsFromAssignment = Collections.emptyMap();
            }
        }

        Map<AnnotatedTypeVariable, AnnotatedTypeMirror> typeArguments;
        typeArguments = inferTypeArgsUsingArgs(processingEnv, atypeFactory, expr, preType, returnType, typeArgumentsFromAssignment);

        if (typeArguments.size() != preType.getTypeVariables().size()) {
            // We still haven't found all type arguments - use wildcard dummies.
            for (AnnotatedTypeVariable atv : preType.getTypeVariables()) {
                if (!typeArguments.containsKey(atv)) {
                    AnnotatedTypeMirror dummy = atypeFactory.getUninferredWildcardType(atv);
                    typeArguments.put(atv.getTypeParameterDeclaration(), dummy);
                }
            }
        }
        return typeArguments;
    }

    private static Map<AnnotatedTypeVariable, AnnotatedTypeMirror> matchTypeVars(
            AnnotatedTypeMirror lhs,
            AnnotatedTypeMirror rhs) {
        Map<AnnotatedTypeVariable, AnnotatedTypeMirror> result = new HashMap<>();
        matchTypeVars(lhs, rhs, result, true);
        return result;
    }

    private static void matchTypeVars(
            AnnotatedTypeMirror lhs,
            AnnotatedTypeMirror rhs,
            Map<AnnotatedTypeVariable, AnnotatedTypeMirror> accum,
            boolean toplevel) {
        if (rhs.getKind().isPrimitive()) {
            // TODO: handle boxing?
            return;
        }

        switch (rhs.getKind()) {
            case TYPEVAR:
                AnnotatedTypeVariable key = ((AnnotatedTypeVariable) rhs).getTypeParameterDeclaration();

                if (toplevel && lhs.getKind() == TypeKind.WILDCARD) {
                    accum.put(key, ((AnnotatedWildcardType)lhs).getEffectiveExtendsBound());
                } else {
                    accum.put(key, lhs);
                }
                break;
            case WILDCARD:
                break;
            case DECLARED:
                if (lhs.getKind() == TypeKind.TYPEVAR) {
                    // type variables don't help
                    break;
                }
                AnnotatedDeclaredType rhsDT = (AnnotatedDeclaredType) rhs;
                // assert lhs.getKind() == TypeKind.DECLARED : "Mismatch! lhs: " + lhs + " rhs: " + rhs;
                if (lhs.getKind() == TypeKind.DECLARED) {
                    AnnotatedDeclaredType lhsDT = (AnnotatedDeclaredType) lhs;
                    // assert rhsDT.getTypeArguments().size() == lhsDT.getTypeArguments().size() : "rhs: " + rhsDT + " lhs: " + lhsDT;
                    if (rhsDT.getTypeArguments().size() == lhsDT.getTypeArguments().size()) {
                        for (int i = 0; i < rhsDT.getTypeArguments().size(); ++i) {
                            matchTypeVars(lhsDT.getTypeArguments().get(i), rhsDT.getTypeArguments().get(i), accum, false);
                        }
                    }
                }
                break;
            case ARRAY:
                if (lhs.getKind() != TypeKind.ARRAY) {
                    // TODO: is some matching possible?
                    break;
                }
                matchTypeVars(((AnnotatedArrayType)lhs).getComponentType(), ((AnnotatedArrayType)rhs).getComponentType(), accum, false);
                break;
            case VOID:
                // Nothing to do.
                break;
            default:
                ErrorReporter.errorAbort("AnnotatedTypes.matchTypeVars: unexpected rhs: " + rhs + " lhs: " + lhs);
        }
    }

    /**
     * Infer the type argument for a single type variable.
     *
     * @param typeVar the method or constructor type variable to infer
     * @param returnType the return type
     * @param typeArgumentsFromAssignment
     * @param exeType the executable type of the method or constructor
     * @param expr the method or constructor invocation tree; the passed argument
     *   has to be a subtype of MethodInvocationTree or NewClassTree.
     * @return the type argument
     */
    private static Map<AnnotatedTypeVariable, AnnotatedTypeMirror> inferTypeArgsUsingArgs(
            final ProcessingEnvironment processingEnv,
            final AnnotatedTypeFactory atypeFactory,
            final ExpressionTree expr,
            final AnnotatedExecutableType preType,
            final AnnotatedTypeMirror returnType,
            final Map<AnnotatedTypeVariable, AnnotatedTypeMirror> typeArgumentsFromAssignment) {
        final Types types = processingEnv.getTypeUtils();

        final List<? extends ExpressionTree> argumentExprs =
                expr.getKind() == Tree.Kind.METHOD_INVOCATION ?
                        ((MethodInvocationTree) expr).getArguments() :
                        (expr.getKind() == Tree.Kind.NEW_CLASS ?
                                ((NewClassTree) expr).getArguments() :
                                null);

        if (argumentExprs == null) {
            ErrorReporter.errorAbort("AnnotatedTypes.inferTypeArguments: couldn't determine arguments from tree: " + expr);
            return null;
        }

        final List<AnnotatedTypeMirror> requiredParams = expandVarArgs(atypeFactory, preType, argumentExprs);
        final List<AnnotatedTypeMirror> passedArgs = new ArrayList<>();
        for (Tree argExp : argumentExprs) {
            passedArgs.add(atypeFactory.getAnnotatedType(argExp));
        }

        Map<AnnotatedTypeVariable, AnnotatedTypeMirror> typeArgumentsFromArguments = new HashMap<>(typeArgumentsFromAssignment);

        for (int i = 0; i < requiredParams.size(); ++i) {
            AnnotatedTypeMirror requiredParam = requiredParams.get(i);
            AnnotatedTypeMirror passedArg = passedArgs.get(i);
            AnnotatedTypeMirror argumentAsParamType = asSuper(types, atypeFactory, passedArg, requiredParam);

            Map<AnnotatedTypeVariable, AnnotatedTypeMirror> typeArgsFromArgument;
            if (requiredParam.getKind() == TypeKind.TYPEVAR) {
                typeArgsFromArgument = matchTypeVars(passedArg, requiredParam);

                AnnotatedTypeMirror pre = typeArgsFromArgument.get(((AnnotatedTypeVariable) requiredParam).getTypeParameterDeclaration());
                if (pre != null) {
                    for (AnnotationMirror am : requiredParam.getAnnotations()) {
                        pre.replaceAnnotation(atypeFactory.getQualifierHierarchy().getBottomAnnotation(am));
                    }
                    typeArgsFromArgument.put((AnnotatedTypeVariable) requiredParam, pre);
                }
            } else if (argumentAsParamType == null) {
                typeArgsFromArgument = matchTypeVars(passedArg, requiredParam);
            } else {
                typeArgsFromArgument = matchTypeVars(argumentAsParamType, requiredParam);
            }
            typeArgumentsFromArguments = mergeTypeArgs(atypeFactory, typeArgumentsFromArguments, typeArgsFromArgument);
        }
        return typeArgumentsFromArguments;
    }

    // TODO: this method needs some more thought and cleanup.
    private static Map<AnnotatedTypeVariable, AnnotatedTypeMirror> mergeTypeArgs(
            final AnnotatedTypeFactory atypeFactory,
            final Map<AnnotatedTypeVariable, AnnotatedTypeMirror> accum,
            final Map<AnnotatedTypeVariable, AnnotatedTypeMirror> add) {
        for (Map.Entry<AnnotatedTypeVariable, AnnotatedTypeMirror> entry : add.entrySet()) {
            if (accum.isEmpty()) {
                accum.put(entry.getKey().getTypeParameterDeclaration(), entry.getValue());
            } else if (accum.containsKey(entry.getKey())) {
                AnnotatedTypeMirror prev = accum.get(entry.getKey());
                AnnotatedTypeMirror toadd = entry.getValue();
                AnnotatedTypeMirror merged;
                if (prev.getKind() == TypeKind.WILDCARD) {
                    if (toadd.getKind() != TypeKind.WILDCARD) {
                        merged = toadd;
                    } else {
                        merged = prev;
                    }
                } else if (prev.getKind() == TypeKind.TYPEVAR &&
                        toadd.getKind() != TypeKind.TYPEVAR) {
                    merged = toadd;
                } else if (prev.getKind() == TypeKind.TYPEVAR &&
                        toadd.getKind() == TypeKind.TYPEVAR) {
                    // noop
                    merged = toadd;
                } else {
                    merged = prev;
                    merged.replaceAnnotations(atypeFactory.getQualifierHierarchy()
                            .leastUpperBounds(merged.getAnnotations(), entry.getValue().getAnnotations()));
                }
                accum.put(entry.getKey(), merged);
            } else {
                // TODO tests break without this :-(
                // What does this do??
                for (Map.Entry<AnnotatedTypeVariable, AnnotatedTypeMirror> accumentry : accum.entrySet()) {
                    if (accumentry.getKey().getUnderlyingType() == entry.getKey().getUnderlyingType()) {
                        // GLB
                        // System.out.println("222What should be done with: " + accumentry.getKey() + " and: " + entry.getKey());
                    } else {
                        accum.put(entry.getKey().getTypeParameterDeclaration(), entry.getValue());
                    }
                }
            }
        }
        return accum;
    }

    /**
     * Returns the annotated type that the leaf of path is assigned to, if it
     * is within an assignment context.
     * Returns the annotated type that the method invocation at the leaf
     * is assigned to.
     *
     * @param path
     * @return type that it path leaf is assigned to
     */
    private static AnnotatedTypeMirror assignedTo(Types types, AnnotatedTypeFactory atypeFactory, TreePath path) {
        Tree assignmentContext = TreeUtils.getAssignmentContext(path);
        if (assignmentContext == null) {
            return null;
        } else if (assignmentContext instanceof AssignmentTree) {
            ExpressionTree variable = ((AssignmentTree)assignmentContext).getVariable();
            return atypeFactory.getAnnotatedType(variable);
        } else if (assignmentContext instanceof CompoundAssignmentTree) {
            ExpressionTree variable =
                    ((CompoundAssignmentTree)assignmentContext).getVariable();
            return atypeFactory.getAnnotatedType(variable);
        } else if (assignmentContext instanceof MethodInvocationTree) {
            MethodInvocationTree methodInvocation = (MethodInvocationTree)assignmentContext;
            // TODO move to getAssignmentContext
            if (methodInvocation.getMethodSelect() instanceof MemberSelectTree
                    && ((MemberSelectTree)methodInvocation.getMethodSelect()).getExpression() == path.getLeaf())
                return null;
            ExecutableElement methodElt = TreeUtils.elementFromUse(methodInvocation);
            AnnotatedTypeMirror receiver = atypeFactory.getReceiverType(methodInvocation);
            AnnotatedExecutableType method = asMemberOf(types, atypeFactory, receiver, methodElt);
            int treeIndex = -1;
            for (int i = 0; i < method.getParameterTypes().size(); ++i) {
                if (TreeUtils.skipParens(methodInvocation.getArguments().get(i)) == path.getLeaf()) {
                    treeIndex = i;
                    break;
                }
            }
            if (treeIndex == -1) return null;
            return method.getParameterTypes().get(treeIndex);
        } else if (assignmentContext instanceof NewArrayTree) {
            // FIXME: This may cause infinite loop
            AnnotatedTypeMirror type =
                    atypeFactory.getAnnotatedType((NewArrayTree)assignmentContext);
            type = AnnotatedTypes.innerMostType(type);
            return type;
        } else if (assignmentContext instanceof NewClassTree) {
            // This need to be basically like MethodTree
            NewClassTree newClassTree = (NewClassTree) assignmentContext;
            ExecutableElement constructorElt = InternalUtils.constructor(newClassTree);
            AnnotatedTypeMirror receiver = atypeFactory.getAnnotatedType(newClassTree.getIdentifier());
            AnnotatedExecutableType constructor =
                    asMemberOf(types, atypeFactory, receiver, constructorElt);
            int treeIndex = -1;
            for (int i = 0; i < constructor.getParameterTypes().size(); ++i) {
                if (TreeUtils.skipParens(newClassTree.getArguments().get(i)) == path.getLeaf()) {
                    treeIndex = i;
                    break;
                }
            }
            if (treeIndex == -1) return null;
            return constructor.getParameterTypes().get(treeIndex);
        } else if (assignmentContext instanceof ReturnTree) {
            MethodTree method = TreeUtils.enclosingMethod(path);
            return (atypeFactory.getAnnotatedType(method)).getReturnType();
        } else if (assignmentContext instanceof VariableTree) {
            return atypeFactory.getAnnotatedType(assignmentContext);
        }

        ErrorReporter.errorAbort("AnnotatedTypes.assignedTo: shouldn't be here!");
        return null; // dead code
    }

    // TODO: compare to leastUpperBound method that is in comments further
    // below and see how to incorporate the logic.
    // Also see CFAbstractValue for other methods that should be in
    // a better location.
    public static AnnotatedTypeMirror leastUpperBound(ProcessingEnvironment processingEnv, AnnotatedTypeFactory atypeFactory,
                                                      AnnotatedTypeMirror a, AnnotatedTypeMirror b) {
        List<AnnotatedTypeMirror> list = new ArrayList<>(2);
        list.add(a);
        list.add(b);
        // It would be nice to use the following
        // return leastUpperBound(processingEnv, atypeFactory, list);
        // see commented-out version below.
        TypeMirror lubType = InternalUtils.leastUpperBound(processingEnv, a.getUnderlyingType(), b.getUnderlyingType());
        AnnotatedTypeMirror res = AnnotatedTypeMirror.createType(lubType, atypeFactory, false);
        annotateAsLub(processingEnv, atypeFactory, res, list);
        return res;
    }

    /* TODO: Add version that takes arbitrary number of arguments.
     * At the moment, InternalUtils.leastUpperBound only exists for two arguments.
    public static AnnotatedTypeMirror leastUpperBound(ProcessingEnvironment processingEnv, AnnotatedTypeFactory atypeFactory,
            Collection<AnnotatedTypeMirror> types) {
        com.sun.tools.javac.util.List<Type> utypes = com.sun.tools.javac.util.List.nil();
        for (AnnotatedTypeMirror atm : types) {
            utypes = utypes.append((Type) atm.getUnderlyingType());
        }
        JavacProcessingEnvironment javacEnv = (JavacProcessingEnvironment) processingEnv;
        com.sun.tools.javac.code.Types jctypes = com.sun.tools.javac.code.Types.instance(javacEnv.getContext());
        // jctypes.lub doesn't do the special handling of NULL that we have in InternalUtils.leastUpperBound
        // Add that tu InternalUtils and use it here. Using the jctypes version crashes.
        TypeMirror lubType = jctypes.lub(utypes);
        AnnotatedTypeMirror res = AnnotatedTypeMirror.createType(lubType, atypeFactory, false);
        annotateAsLub(processingEnv, atypeFactory, res, types);
        return res;
    }
    */

    /**
     * Annotate the lub type as if it is the least upper bound of the rest of
     * the types.  This is a useful method for finding conditional expression
     * types.
     *
     * All the types need to be subtypes of lub.
     *
     * @param lub   the type to be the least upper bound
     * @param types the type arguments
     */
    public static void annotateAsLub(ProcessingEnvironment processingEnv, AnnotatedTypeFactory atypeFactory,
                                     AnnotatedTypeMirror lub, Collection<AnnotatedTypeMirror> types) {
        Types typeutils = processingEnv.getTypeUtils();
        Elements elements = processingEnv.getElementUtils();

        // Is it anonymous?
        if (lub.getKind() == TypeKind.INTERSECTION) {
            // Find the intersect types
            AnnotatedIntersectionType adt = (AnnotatedIntersectionType) lub;

            for (AnnotatedDeclaredType adts : adt.directSuperTypes()) {
                ArrayList<AnnotatedTypeMirror> subtypes = new ArrayList<>(types.size());
                for (AnnotatedTypeMirror type : types) {
                    AnnotatedTypeMirror sup = asSuper(typeutils, atypeFactory, type, adts);
                    if (sup != null) {
                        subtypes.add(sup);
                    }
                }
                if (subtypes.size() > 0) {
                    adts.clearAnnotations();
                }

                addAnnotations(elements, atypeFactory, adts, subtypes);
                ArrayList<AnnotatedTypeMirror> adtslist = new ArrayList<AnnotatedTypeMirror>();
                adtslist.add(adts);
                addAnnotations(elements, atypeFactory, lub, adtslist);
            }
        } else {
            ArrayList<AnnotatedTypeMirror> subtypes = new ArrayList<>(types.size());

            // TODO: This code needs some more serious thought.
            if (lub.getKind() == TypeKind.WILDCARD) {
                subtypes.add(deepCopy(lub));
            } else {
                for (AnnotatedTypeMirror type : types) {
                    if (type == null) {
                        continue;
                    }
                    AnnotatedTypeMirror ass = asSuper(typeutils, atypeFactory, type, lub);
                    if (ass == null) {
                        subtypes.add(deepCopy(type));
                    } else {
                        subtypes.add(ass);
                    }
                }
            }
            if (subtypes.size() > 0) {
                lub.clearAnnotations();
            }

            if(lub.getKind() == TypeKind.TYPEVAR) {
                //TODO: TERRIBLE HACK UNTIL WE FIX LUB
                final AnnotatedTypeVariable lubAtv = (AnnotatedTypeVariable) lub;
                final List<AnnotatedTypeVariable> subtypesAsTvs =
                    LubTypeVariableAnnotator.getSubtypesAsTypevars(lubAtv, subtypes);

                if(subtypesAsTvs != null) {
                    LubTypeVariableAnnotator.annotateTypeVarAsLub(lubAtv, subtypesAsTvs, atypeFactory);
                } else {
                    addAnnotations(elements, atypeFactory, lub, subtypes);
                }

            } else {
                addAnnotations(elements, atypeFactory, lub, subtypes);
            }
        }
    }

    /**
     * Add the 'intersection' of the types provided to alub.  This is a similar
     * method to the one provided
     * TODO: the above sentence should be finished somehow...
     */
    private static void addAnnotations(Elements elements, AnnotatedTypeFactory atypeFactory,
                                       AnnotatedTypeMirror alub,
                                       ArrayList<AnnotatedTypeMirror> types) {
        Set<TypeMirror> visited = new HashSet<>();
        addAnnotationsImpl(elements, atypeFactory, alub, visited, types);
    }

    private static void addAnnotationsImpl(Elements elements, AnnotatedTypeFactory atypeFactory,
                                           AnnotatedTypeMirror alub,
                                           Set<TypeMirror> visited,
                                           ArrayList<AnnotatedTypeMirror> types) {
        // System.out.println("AnnotatedTypes.addAnnotationsImpl: alub: " + alub +
        //        "\n   visited: " + visited +
        //        "\n   types: " + types);

        final AnnotatedTypeMirror origalub = alub;
        boolean shouldAnnoOrig = false;
        Set<AnnotationMirror> putOnOrig = AnnotationUtils.createAnnotationSet();

        // get rid of wildcards and type variables
        if (alub.getKind() == TypeKind.WILDCARD) {
            alub = ((AnnotatedWildcardType)alub).getExtendsBound();
            // TODO using the getEffective versions copies objects, losing side-effects.
        }
        while (alub.getKind() == TypeKind.TYPEVAR) {
            alub = ((AnnotatedTypeVariable)alub).getUpperBound();
        }

        if (visited.contains(alub.getUnderlyingType())) {
            return;
        }
        visited.add(alub.getUnderlyingType());

        for (int i = 0; i < types.size(); ++i) {
            final AnnotatedTypeMirror typei = types.get(i);

            if (!(typei.getAnnotations().isEmpty() ||
                    bottomsOnly(elements, atypeFactory, typei.getAnnotations()))) {
                shouldAnnoOrig = true;
            }

            if (typei.getKind() == TypeKind.WILDCARD) {
                putOnOrig.addAll(typei.getAnnotations());
                AnnotatedWildcardType wildcard = (AnnotatedWildcardType) typei;
                if (wildcard.getExtendsBound() != null)
                    types.set(i, wildcard.getEffectiveExtendsBound());
                else if (wildcard.getSuperBound() != null)
                    types.set(i, wildcard.getEffectiveSuperBound());
            }
            if (typei.getKind() == TypeKind.TYPEVAR) {
                putOnOrig.addAll(typei.getAnnotations());
                AnnotatedTypeVariable typevar = (AnnotatedTypeVariable) types.get(i);
                if (typevar.getUpperBound() != null)
                    types.set(i, typevar.getEffectiveUpperBound());
                else if (typevar.getLowerBound() != null)
                    types.set(i, typevar.getEffectiveLowerBound());
            }
        }

        Collection<? extends AnnotationMirror> unification = Collections.emptySet();

        boolean isFirst = true;
        for (AnnotatedTypeMirror type : types) {
            if (type.getAnnotations().isEmpty())
                continue;
            // TODO: unification fails with an empty set of annotations.
            // Why are they sometimes empty, e.g. in the FlowNegation test case.

            if (isFirst) {
                unification = type.getAnnotations();
            } else {
                unification = atypeFactory.getQualifierHierarchy().leastUpperBounds(unification, type.getAnnotations());
            }
            isFirst = false;
        }

        // Remove a previously existing unqualified annotation on the type.
        alub.replaceAnnotations(unification);

        if (alub.getKind() == TypeKind.DECLARED) {
            AnnotatedDeclaredType adt = (AnnotatedDeclaredType) alub;

            for (int i = 0; i < adt.getTypeArguments().size(); ++i) {
                AnnotatedTypeMirror adtArg = adt.getTypeArguments().get(i);
                ArrayList<AnnotatedTypeMirror> dTypesArg = new ArrayList<>();
                for (int j = 0; j < types.size(); ++j) {
                    if (types.get(j).getKind() == TypeKind.DECLARED) {
                        AnnotatedDeclaredType adtypej = (AnnotatedDeclaredType) types.get(j);
                        if (adtypej.getTypeArguments().size() == adt.getTypeArguments().size()) {
                            dTypesArg.add(adtypej.getTypeArguments().get(i));
                        } else {
                            // TODO: actually not just the number of type arguments should match, but
                            // the base types should be equal. See test case framework/GenericTest1
                            // for when this test fails.
                        }
                    }
                }
                addAnnotationsImpl(elements, atypeFactory, adtArg, visited, dTypesArg);
            }
        } else if (alub.getKind() == TypeKind.ARRAY) {
            AnnotatedArrayType aat = (AnnotatedArrayType) alub;

            ArrayList<AnnotatedTypeMirror> compTypes = new ArrayList<>();
            for (AnnotatedTypeMirror atype : types)  {
                if (atype.getKind() == TypeKind.ARRAY) {
                    compTypes.add(((AnnotatedArrayType)atype).getComponentType());
                }
            }
            addAnnotationsImpl(elements, atypeFactory, aat.getComponentType(), visited, compTypes);
        }
        if (alub != origalub && shouldAnnoOrig) {
            // These two are not the same if origalub is a wildcard or type variable.
            // In that case, add the found annotations to the type variable also.
            // Do not put the annotations inferred for the declared type
            // on a type variable/wildcard.
            // origalub.replaceAnnotations(alub.getAnnotations());
            // Instead, keep track of the annotations that originally
            // existed on the type variable, stored in putOnOrig, and
            // put them back on now.
            origalub.replaceAnnotations(putOnOrig);
        }
    }

    /*
     * Return true if all the qualifiers are bottom qualifiers. Allow fewer
     * qualifiers to be present, which can happen for type variables and
     * wildcards.
     */
    private static boolean bottomsOnly(Elements elements, AnnotatedTypeFactory atypeFactory,
                                       Set<AnnotationMirror> annotations) {
        Set<AnnotationMirror> bots = AnnotationUtils.createAnnotationSet();
        bots.addAll(atypeFactory.getQualifierHierarchy().getBottomAnnotations());

        for (AnnotationMirror am : annotations) {
            if (!bots.remove(am)) {
                return false;
            }
        }
        return true;
    }

    /* TODO: This least upper bound computation was originally
     * in org.checkerframework.framework.flow.CFAbstractValue<V>.
     * It should be checked to make sure the implementation here is consistent.
     * Afterwards it can be removed.
     *
     * Computes and returns the least upper bound of two
     * {@link AnnotatedTypeMirror}.
     *
     * <p>
     * TODO: The code in this method is rather similar to
     * {@link CFAbstractValue#mostSpecific(CFAbstractValue, CFAbstractValue)}.
     * Can code be reused?
    public AnnotatedTypeMirror leastUpperBound(AnnotatedTypeMirror type,
            AnnotatedTypeMirror otherType) {
        GenericAnnotatedTypeFactory<V, ?, ?, ?> factory = analysis.getTypeFactory();
        ProcessingEnvironment processingEnv = factory.getProcessingEnv();
        QualifierHierarchy qualifierHierarchy = factory.getQualifierHierarchy();

        AnnotatedTypeMirror lubAnnotatedType;

        if (type.getKind() == TypeKind.ARRAY
                && otherType.getKind() == TypeKind.ARRAY) {
            // for arrays, we have:
            // lub(@A1 A @A2[],@B1 B @B2[]) = lub(@A1 A, @B1 B) lub(@A2,@B2) []
            AnnotatedArrayType a = (AnnotatedArrayType) type;
            AnnotatedArrayType b = (AnnotatedArrayType) otherType;
            AnnotatedTypeMirror componentLub = leastUpperBound(
                    a.getComponentType(), b.getComponentType());
            if (componentLub.getUnderlyingType().getKind() == TypeKind.NONE) {
                // If the components do not have an upper bound, then Object
                // is still an upper bound of the array types.
                Elements elements = analysis.getEnv().getElementUtils();
                TypeMirror underlyingType = elements.getTypeElement(
                        "java.lang.Object").asType();
                lubAnnotatedType = AnnotatedTypeMirror.createType(
                        underlyingType, factory, false);
            } else {
                TypeMirror underlyingType = TypesUtils.createArrayType(
                        analysis.getTypes(), componentLub.getUnderlyingType());
                lubAnnotatedType = AnnotatedTypeMirror.createType(
                        underlyingType, factory, false);
                AnnotatedArrayType aLubAnnotatedType = (AnnotatedArrayType) lubAnnotatedType;
                aLubAnnotatedType.setComponentType(componentLub);
            }
        } else {
            TypeMirror lubType = InternalUtils.leastUpperBound(processingEnv,
                    type.getUnderlyingType(), otherType.getUnderlyingType());
            lubAnnotatedType = AnnotatedTypeMirror.createType(lubType, factory, false);
        }

        Set<AnnotationMirror> annos1;
        Set<AnnotationMirror> annos2;
        if (QualifierHierarchy.canHaveEmptyAnnotationSet(lubAnnotatedType)) {
            annos1 = type.getAnnotations();
            annos2 = otherType.getAnnotations();
        } else {
            annos1 = type.getEffectiveAnnotations();
            annos2 = otherType.getEffectiveAnnotations();
        }

        lubAnnotatedType.addAnnotations(qualifierHierarchy.leastUpperBounds(
                type, otherType, annos1, annos2));

        TypeKind kind = lubAnnotatedType.getKind();
        if (kind == TypeKind.WILDCARD) {
            AnnotatedWildcardType wLubAnnotatedType = (AnnotatedWildcardType) lubAnnotatedType;
            AnnotatedTypeMirror extendsBound = wLubAnnotatedType
                    .getExtendsBound();
            extendsBound.clearAnnotations();
            Collection<AnnotationMirror> extendsBound1 = getUpperBound(type);
            Collection<AnnotationMirror> extendsBound2 = getUpperBound(otherType);
            extendsBound.addAnnotations(qualifierHierarchy.leastUpperBounds(
                    extendsBound1, extendsBound2));
        } else if (kind == TypeKind.TYPEVAR) {
            AnnotatedTypeVariable tLubAnnotatedType = (AnnotatedTypeVariable) lubAnnotatedType;
            AnnotatedTypeMirror upperBound = tLubAnnotatedType.getUpperBound();
            Collection<AnnotationMirror> upperBound1 = getUpperBound(type);
            Collection<AnnotationMirror> upperBound2 = getUpperBound(otherType);

            // TODO: how is it possible that uppBound1 or 2 does not have any
            // annotations?
            if (upperBound1.size() != 0 && upperBound2.size() != 0) {
                upperBound.clearAnnotations();
                upperBound.addAnnotations(qualifierHierarchy.leastUpperBounds(
                        upperBound1, upperBound2));
            }

            // if only one of the input types were type variables, then we want
            // the effective annotations and take the lub of them
            if (type.getKind() != TypeKind.TYPEVAR || otherType.getKind() != TypeKind.TYPEVAR) {
                // TODO Why the special treatment for NULL?
                if (otherType.getKind() == TypeKind.NULL) {
                    // TODO Why the flipping between the two?
                    if (type.getKind() != TypeKind.TYPEVAR) {
                        AnnotatedTypeMirror tmp = otherType;
                        otherType = type;
                        type = tmp;
                    }
                    // Do these hold?
                    // assert type.getKind() == TypeKind.TYPEVAR ||
                    //        type.getKind() == TypeKind.WILDCARD : "Unexpected type: " + type;
                    // assert otherType.getKind() != TypeKind.TYPEVAR : "Unexpected type variable: " + otherType;

                    lubAnnotatedType.clearAnnotations();
                    lubAnnotatedType.addAnnotations(type.getAnnotations());
                    for (AnnotationMirror top : qualifierHierarchy.getTopAnnotations()) {
                        AnnotationMirror o = otherType.getAnnotationInHierarchy(top);
                        assert o != null : "null should have all annotations.";
                        if (AnnotationUtils.areSame(o,
                                qualifierHierarchy.getBottomAnnotation(top))) {
                            // if the annotation on 'null' is the bottom
                            // annotation, take whatever is present on the type
                            // variable (even if it is nothing)...
                            // (already done)
                        } else {
                            // ... otherwise, take the LUB of the effective
                            // annotations.
                            lubAnnotatedType.replaceAnnotation(
                                    qualifierHierarchy.leastUpperBound(o,
                                            type.getEffectiveAnnotationInHierarchy(top)));
                        }
                    }
                }
            }
        } else if (kind == TypeKind.ARRAY
                && !(type.getKind() == TypeKind.ARRAY && otherType.getKind() == TypeKind.ARRAY)) {
            AnnotatedArrayType aLubAnnotatedType = (AnnotatedArrayType) lubAnnotatedType;
            // lub(a,b) is an array, but not both a and b are arrays -> either a
            // or b must be the null type.
            AnnotatedArrayType array;
            if (type.getKind() == TypeKind.ARRAY) {
                assert otherType.getKind() == TypeKind.NULL;
                array = (AnnotatedArrayType) type;
            } else {
                assert otherType.getKind() == TypeKind.ARRAY;
                assert type.getKind() == TypeKind.NULL;
                array = (AnnotatedArrayType) otherType;
            }
            // copy over annotations
            copyArrayComponentAnnotations(array, aLubAnnotatedType);
        }
        return lubAnnotatedType;
    }
    */


    /**
     * Returns the method parameters for the invoked method, with the same number
     * of arguments passed in the methodInvocation tree.
     *
     * If the invoked method is not a vararg method or it is a vararg method
     * but the invocation passes an array to the vararg parameter, it would simply
     * return the method parameters.
     *
     * Otherwise, it would return the list of parameters as if the vararg is expanded
     * to match the size of the passed arguments.
     *
     * @param method the method's type
     * @param args the arguments to the method invocation
     * @return  the types that the method invocation arguments need to be subtype of
     */
    public static List<AnnotatedTypeMirror> expandVarArgs(AnnotatedTypeFactory atypeFactory,
                                                          AnnotatedExecutableType method,
                                                          List<? extends ExpressionTree> args) {
        List<AnnotatedTypeMirror> parameters = method.getParameterTypes();
        if (!method.getElement().isVarArgs()) {
            return parameters;
        }

        AnnotatedArrayType varargs = (AnnotatedArrayType)parameters.get(parameters.size() - 1);

        if (parameters.size() == args.size()) {
            // Check if one sent an element or an array
            AnnotatedTypeMirror lastArg = atypeFactory.getAnnotatedType(args.get(args.size() - 1));
            if (lastArg.getKind() == TypeKind.ARRAY &&
                    getArrayDepth(varargs) == getArrayDepth((AnnotatedArrayType)lastArg)) {
                return parameters;
            }
        }

        parameters = new ArrayList<>(parameters.subList(0, parameters.size() - 1));
        for (int i = args.size() - parameters.size(); i > 0; --i)
            parameters.add(varargs.getComponentType());

        return parameters;
    }

    /**
     * Return a list of the AnnotatedTypeMirror of the passed
     * expression trees, in the same order as the trees.
     *
     * @param paramTypes The parameter types to use as assignment context
     * @param trees the AST nodes
     * @return  a list with the AnnotatedTypeMirror of each tree in trees.
     */
    public static List<AnnotatedTypeMirror> getAnnotatedTypes(AnnotatedTypeFactory atypeFactory,
                                                              List<AnnotatedTypeMirror> paramTypes, List<? extends ExpressionTree> trees) {
        assert paramTypes.size() == trees.size() : "AnnotatedTypes.getAnnotatedTypes: size mismatch! " +
                "Parameter types: " + paramTypes + " Arguments: " + trees;
        List<AnnotatedTypeMirror> types = new ArrayList<>();
        Pair<Tree, AnnotatedTypeMirror> preAssCtxt = atypeFactory.getVisitorState().getAssignmentContext();

        try {
            for (int i = 0; i < trees.size(); ++i) {
                AnnotatedTypeMirror param = paramTypes.get(i);
                atypeFactory.getVisitorState().setAssignmentContext(Pair.<Tree, AnnotatedTypeMirror>of((Tree) null, param));
                ExpressionTree arg = trees.get(i);
                types.add(atypeFactory.getAnnotatedType(arg));
            }
        } finally {
            atypeFactory.getVisitorState().setAssignmentContext(preAssCtxt);
        }
        return types;
    }

    // TODO: can't we do better than comparing the strings?
    public static boolean areSame(AnnotatedTypeMirror t1, AnnotatedTypeMirror t2) {
        return t1.toString().equals(t2.toString());
    }

    /**
     * Returns the depth of the array type of the provided array.
     *
     * @param array the type of the array
     * @return  the depth of the provided array
     */
    public static int getArrayDepth(AnnotatedArrayType array) {
        int counter = 0;
        AnnotatedTypeMirror type = array;
        while (type.getKind() == TypeKind.ARRAY) {
            counter++;
            type = ((AnnotatedArrayType)type).getComponentType();
        }
        return counter;
    }

    // The innermost *array* type.
    public static AnnotatedTypeMirror innerMostType(AnnotatedTypeMirror t) {
        AnnotatedTypeMirror inner = t;
        while (inner.getKind() == TypeKind.ARRAY)
            inner = ((AnnotatedArrayType)inner).getComponentType();
        return inner;
    }


    /**
     * Checks whether type contains the given modifier, also recursively in type arguments and arrays.
     * This method might be easier to implement directly as instance method in AnnotatedTypeMirror;
     * it corresponds to a "deep" version of
     * {@link AnnotatedTypeMirror#hasAnnotation(AnnotationMirror)}.
     *
     * @param type the type to search.
     * @param modifier the modifier to search for.
     * @return whether the type contains the modifier.
     */
    public static boolean containsModifier(AnnotatedTypeMirror type, AnnotationMirror modifier) {
        return containsModifierImpl(type, modifier, new LinkedList<AnnotatedTypeMirror>());
    }

    /*
     * For type variables we might hit the same type again. We keep a list of visited types.
     */
    private static boolean containsModifierImpl(AnnotatedTypeMirror type, AnnotationMirror modifier,
                                                List<AnnotatedTypeMirror> visited) {
        boolean found = type.hasAnnotation(modifier);
        boolean vis = visited.contains(type);
        visited.add(type);

        if (!found && !vis) {
            if (type.getKind() == TypeKind.DECLARED) {
                AnnotatedDeclaredType declaredType = (AnnotatedDeclaredType) type;
                for (AnnotatedTypeMirror typeMirror : declaredType.getTypeArguments()) {
                    found |= containsModifierImpl(typeMirror, modifier, visited);
                    if (found) {
                        break;
                    }
                }
            } else if (type.getKind() == TypeKind.ARRAY) {
                AnnotatedArrayType arrayType = (AnnotatedArrayType) type;
                found = containsModifierImpl(arrayType.getComponentType(), modifier, visited);
            } else if (type.getKind() == TypeKind.TYPEVAR) {
                AnnotatedTypeVariable atv = (AnnotatedTypeVariable) type;
                if (atv.getUpperBound() != null) {
                    found = containsModifierImpl(atv.getUpperBound(), modifier, visited);
                }
                if (!found && atv.getLowerBound() != null) {
                    found = containsModifierImpl(atv.getLowerBound(), modifier, visited);
                }
            } else if (type.getKind() == TypeKind.WILDCARD) {
                AnnotatedWildcardType awc = (AnnotatedWildcardType) type;
                if (awc.getExtendsBound() != null) {
                    found = containsModifierImpl(awc.getExtendsBound(), modifier, visited);
                }
                if (!found && awc.getSuperBound() != null) {
                    found = containsModifierImpl(awc.getSuperBound(), modifier, visited);
                }
            }
        }

        return found;
    }


    private static Map<TypeElement, Boolean> isTypeAnnotationCache = new IdentityHashMap<>();

    public static boolean isTypeAnnotation(AnnotationMirror anno) {
        TypeElement elem = (TypeElement)anno.getAnnotationType().asElement();
        if (isTypeAnnotationCache.containsKey(elem))
            return isTypeAnnotationCache.get(elem);

        boolean result = isTypeAnnotationImpl(elem);
        isTypeAnnotationCache.put(elem, result);
        return result;
    }

    private static boolean isTypeAnnotationImpl(TypeElement type) {
        return type.getAnnotation(TypeQualifier.class) != null;
    }

    public static boolean containsTypeAnnotation(Collection<? extends AnnotationMirror> annos) {
        for(AnnotationMirror am : annos) {
            if(isTypeAnnotation(am)) return true;
        }
        return false;
    }

    /**
     * Returns true if the given {@link AnnotatedTypeMirror} passed a set of
     * well-formedness checks. The method will never return false for valid
     * types, but might not catch all invalid types.
     *
     * <p>
     * Currently, the following is checked:
     * <ol>
     * <li>There should not be multiple annotations from the same hierarchy.
     * <li>There should not be more annotations than the width of the qualifier
     * hierarchy.
     * <li>If the type is not a type variable, then the number of annotations
     * should be the same as the width of the qualifier hierarchy.
     * <li>These properties should also hold recursively for component types of
     * arrays, as wells as bounds of type variables and wildcards.
     * </ol>
     */
    public static boolean isValidType(QualifierHierarchy qualifierHierarchy,
                                      AnnotatedTypeMirror type) {
        boolean res = isValidType(qualifierHierarchy, type,
                Collections.<AnnotatedTypeMirror> emptySet());
        return res;
    }

    private static boolean isValidType(QualifierHierarchy qualifierHierarchy,
                                       AnnotatedTypeMirror type, Set<AnnotatedTypeMirror> v) {
        if (type == null) {
            return false;
        }

        Set<AnnotatedTypeMirror> visited = new HashSet<>(v);
        if (visited.contains(type)) {
            return true; // prevent infinite recursion
        }
        visited.add(type);

        // multiple annotations from the same hierarchy
        Set<AnnotationMirror> annotations = type.getAnnotations();
        Set<AnnotationMirror> seenTops = AnnotationUtils.createAnnotationSet();
        int n = 0;
        for (AnnotationMirror anno : annotations) {
            if (QualifierPolymorphism.isPolyAll(anno)) {
                // ignore PolyAll when counting annotations
                continue;
            }
            n++;
            AnnotationMirror top = qualifierHierarchy.getTopAnnotation(anno);
            if (seenTops.contains(top)) {
                return false;
            }
            seenTops.add(top);
        }

        // too many annotations
        int expectedN = qualifierHierarchy.getWidth();
        if (n > expectedN) {
            return false;
        }

        // treat types that have polyall like type variables
        boolean hasPolyAll = type.hasAnnotation(PolyAll.class);
        boolean canHaveEmptyAnnotationSet =
                QualifierHierarchy.canHaveEmptyAnnotationSet(type) ||
                        hasPolyAll;

        // wrong number of annotations
        if (!canHaveEmptyAnnotationSet && n != expectedN) {
            return false;
        }

        // recurse for composite types
        if (type instanceof AnnotatedArrayType) {
            AnnotatedArrayType at = (AnnotatedArrayType) type;
            if (!isValidType(qualifierHierarchy, at.getComponentType(), visited)) {
                return false;
            }
        } else if (type instanceof AnnotatedTypeVariable) {
            AnnotatedTypeVariable at = (AnnotatedTypeVariable) type;
            AnnotatedTypeMirror lowerBound = at.getLowerBound();
            AnnotatedTypeMirror upperBound = at.getUpperBound();
            if (lowerBound != null
                    && !isValidType(qualifierHierarchy, lowerBound, visited)) {
                return false;
            }
            if (upperBound != null
                    && !isValidType(qualifierHierarchy, upperBound, visited)) {
                return false;
            }
        } else if (type instanceof AnnotatedWildcardType) {
            AnnotatedWildcardType at = (AnnotatedWildcardType) type;
            AnnotatedTypeMirror extendsBound = at.getExtendsBound();
            AnnotatedTypeMirror superBound = at.getSuperBound();
            if (extendsBound != null
                    && !isValidType(qualifierHierarchy, extendsBound, visited)) {
                return false;
            }
            if (superBound != null
                    && !isValidType(qualifierHierarchy, superBound, visited)) {
                return false;
            }
        }
        // TODO: the recursive checks on type arguments are currently skipped, because
        // this breaks various tests.  it seems that checking the validity changes the
        // annotations on some types.
//        } else if (type instanceof AnnotatedDeclaredType) {
//            AnnotatedDeclaredType at = (AnnotatedDeclaredType) type;
//            for (AnnotatedTypeMirror typeArgument : at.getTypeArguments()) {
//                if (!isValidType(qualifierHierarchy, typeArgument, visited)) {
//                    return false;
//                }
//            }
//        }
        return true;
    }

    private static String annotationClassName = java.lang.annotation.Annotation.class.getCanonicalName();

    /**
     * @return true if the underlying type of this atm is a java.lang.annotation.Annotation
     */
    public static boolean isJavaLangAnnotation(final AnnotatedTypeMirror atm) {
        return TypesUtils.isDeclaredOfName(atm.getUnderlyingType(), annotationClassName);
    }

    /**
     * @return true if atm is an Annotation interface, i.e. an implementation of java.lang.annotation.Annotation
     */
    public static boolean implementsAnnotation(final AnnotatedTypeMirror atm) {
        if(atm.getKind() != TypeKind.DECLARED) {
            return false;
        }
        final AnnotatedTypeMirror.AnnotatedDeclaredType declaredType = (AnnotatedTypeMirror.AnnotatedDeclaredType) atm;

        Symbol.ClassSymbol classSymbol = (Symbol.ClassSymbol) declaredType.getUnderlyingType().asElement();
        for(final Type iface : classSymbol.getInterfaces() ) {
            if( TypesUtils.isDeclaredOfName(iface, annotationClassName ) ) {
                return true;
            }
        }

        return false;
    }


    /**
     * Do these two type variables share a declaration.
     */
    public static boolean haveSameDeclaration(Types types, final AnnotatedTypeVariable typeVar1, final AnnotatedTypeVariable typeVar2) {
        return types.isSameType(typeVar1.getUnderlyingType(), typeVar2.getUnderlyingType());
    }

    public static boolean areCorrespondingTypeVariables(Elements elements, AnnotatedTypeVariable subtype, AnnotatedTypeVariable supertype) {
        final TypeParameterElement subtypeParamElem   = (TypeParameterElement) subtype.getUnderlyingType().asElement();
        final TypeParameterElement supertypeParamElem = (TypeParameterElement) supertype.getUnderlyingType().asElement();


        if( subtypeParamElem.getGenericElement() instanceof ExecutableElement
         && supertypeParamElem.getGenericElement() instanceof ExecutableElement ) {
            final ExecutableElement subtypeExecutable   = (ExecutableElement) subtypeParamElem.getGenericElement();
            final ExecutableElement supertypeExecutable = (ExecutableElement) supertypeParamElem.getGenericElement();

            final TypeElement subtypeClass = (TypeElement) subtypeExecutable.getEnclosingElement();
            final TypeElement supertypeClass = (TypeElement) supertypeExecutable.getEnclosingElement();

            boolean methodIsOverriden = elements.overrides(subtypeExecutable, supertypeExecutable, subtypeClass)
                                     || elements.overrides(supertypeExecutable, subtypeExecutable, supertypeClass);
            if(methodIsOverriden) {
                boolean haveSameIndex = subtypeExecutable.getTypeParameters().indexOf(subtypeParamElem) ==
                                        supertypeExecutable.getTypeParameters().indexOf(supertypeParamElem);
                return haveSameIndex;
            }
        }

        return false;
    }
}
