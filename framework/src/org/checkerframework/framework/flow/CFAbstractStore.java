package org.checkerframework.framework.flow;

/*>>>
import org.checkerframework.checker.nullness.qual.Nullable;
*/

import org.checkerframework.dataflow.analysis.FlowExpressions;
import org.checkerframework.dataflow.analysis.FlowExpressions.ArrayAccess;
import org.checkerframework.dataflow.analysis.FlowExpressions.FieldAccess;
import org.checkerframework.dataflow.analysis.FlowExpressions.LocalVariable;
import org.checkerframework.dataflow.analysis.FlowExpressions.PureMethodCall;
import org.checkerframework.dataflow.analysis.FlowExpressions.Receiver;
import org.checkerframework.dataflow.analysis.Store;
import org.checkerframework.dataflow.cfg.node.ArrayAccessNode;
import org.checkerframework.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.ThisLiteralNode;
import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.dataflow.util.PurityUtils;
import org.checkerframework.framework.qual.MonotonicQualifier;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * A store for the checker framework analysis tracks the annotations of memory
 * locations such as local variables and fields.
 *
 * @author Charlie Garrett
 * @author Stefan Heule
 */
// TODO: this class should be split into parts that are reusable generally, and
// parts specific to the checker framework
public abstract class CFAbstractStore<V extends CFAbstractValue<V>, S extends CFAbstractStore<V, S>>
        implements Store<S> {

    /**
     * The analysis class this store belongs to.
     */
    protected final CFAbstractAnalysis<V, S, ?> analysis;

    /**
     * Information collected about local variables, which are identified by the
     * corresponding element.
     */
    protected final Map<Element, V> localVariableValues;

    /**
     * Information collected about the current object.
     */
    protected V thisValue;

    /**
     * Information collected about fields, using the internal representation
     * {@link FieldAccess}.
     */
    protected Map<FlowExpressions.FieldAccess, V> fieldValues;

    /**
     * Information collected about arrays, using the internal representation
     * {@link ArrayAccess}.
     */
    protected Map<FlowExpressions.ArrayAccess, V> arrayValues;

    /**
     * Information collected about pure method calls, using the internal
     * representation {@link PureMethodCall}.
     */
    protected Map<FlowExpressions.PureMethodCall, V> methodValues;

    protected Map<FlowExpressions.ClassName, V> classValues;

    /**
     * Should the analysis use sequential Java semantics (i.e., assume that only
     * one thread is running at all times)?
     */
    protected final boolean sequentialSemantics;

    /* --------------------------------------------------------- */
    /* Initialization */
    /* --------------------------------------------------------- */

    public CFAbstractStore(CFAbstractAnalysis<V, S, ?> analysis,
            boolean sequentialSemantics) {
        this.analysis = analysis;
        localVariableValues = new HashMap<>();
        thisValue = null;
        fieldValues = new HashMap<>();
        methodValues = new HashMap<>();
        arrayValues = new HashMap<>();
        classValues = new HashMap<>();
        this.sequentialSemantics = sequentialSemantics;
    }

    /** Copy constructor. */
    protected CFAbstractStore(CFAbstractStore<V, S> other) {
        this.analysis = other.analysis;
        localVariableValues = new HashMap<>(other.localVariableValues);
        thisValue = other.thisValue;
        fieldValues = new HashMap<>(other.fieldValues);
        methodValues = new HashMap<>(other.methodValues);
        arrayValues = new HashMap<>(other.arrayValues);
        classValues = new HashMap<>(other.classValues);
        sequentialSemantics = other.sequentialSemantics;
    }

    /**
     * Set the abstract value of a method parameter (only adds the information
     * to the store, does not remove any other knowledge). Any previous
     * information is erased; this method should only be used to initialize the
     * abstract value.
     */
    public void initializeMethodParameter(LocalVariableNode p, /*@Nullable*/ V value) {
        if (value != null) {
            localVariableValues.put(p.getElement(), value);
        }
    }

    /**
     * Set the value of the current object. Any previous information is erased;
     * this method should only be used to initialize the value.
     */
    public void initializeThisValue(AnnotationMirror a, TypeMirror underlyingType) {
        if (a != null) {
            thisValue = analysis.createSingleAnnotationValue(a, underlyingType);
        }
    }

    /* --------------------------------------------------------- */
    /* Handling of fields */
    /* --------------------------------------------------------- */

    /**
     * Remove any information that might not be valid any more after a method
     * call, and add information guaranteed by the method.
     *
     * <ol>
     * <li>If the method is side-effect-free (as indicated by
     * {@link org.checkerframework.dataflow.qual.SideEffectFree} or {@link org.checkerframework.dataflow.qual.Pure}),
     * then no information needs to be removed.
     * <li>Otherwise, all information about field accesses {@code a.f} needs to
     * be removed, except if the method {@code n} cannot modify {@code a.f}
     * (e.g., if {@code a} is a local variable or {@code this}, and {@code f} is
     * final).
     * <li>Furthermore, if the field has a monotonic annotation, then its
     * information can also be kept.
     * </ol>
     *
     * Furthermore, if the method is deterministic, we store its result
     * {@code val} in the store.
     */
    public void updateForMethodCall(MethodInvocationNode n,
            AnnotatedTypeFactory atypeFactory, V val) {
        ExecutableElement method = n.getTarget().getMethod();

        // case 1: remove information if necessary
        if (!(analysis.checker.hasOption("assumeSideEffectFree")
              || PurityUtils.isSideEffectFree(atypeFactory, method))) {
            // update field values
            Map<FlowExpressions.FieldAccess, V> newFieldValues = new HashMap<>();
            for (Entry<FlowExpressions.FieldAccess, V> e : fieldValues.entrySet()) {
                FlowExpressions.FieldAccess fieldAccess = e.getKey();
                V otherVal = e.getValue();

                // case 3:
                List<Pair<AnnotationMirror, AnnotationMirror>> fieldAnnotations = atypeFactory
                        .getAnnotationWithMetaAnnotation(
                                fieldAccess.getField(),
                                MonotonicQualifier.class);
                V newOtherVal = null;
                for (Pair<AnnotationMirror, AnnotationMirror> fieldAnnotation : fieldAnnotations) {
                    AnnotationMirror monotonicAnnotation = fieldAnnotation.second;
                    Name annotation = AnnotationUtils.getElementValueClassName(
                            monotonicAnnotation, "value", false);
                    AnnotationMirror target = AnnotationUtils.fromName(
                            atypeFactory.getElementUtils(), annotation);
                    AnnotationMirror anno = otherVal.getType()
                            .getAnnotationInHierarchy(target);
                    // Make sure the 'target' annotation is present.
                    if (anno != null && AnnotationUtils.areSame(anno, target)) {
                        newOtherVal = analysis.createSingleAnnotationValue(
                                target, otherVal.getType().getUnderlyingType())
                                .mostSpecific(newOtherVal, null);
                    }
                }
                if (newOtherVal != null) {
                    // keep information for all hierarchies where we had a
                    // monotone annotation.
                    newFieldValues.put(fieldAccess, newOtherVal);
                    continue;
                }

                // case 2:
                if (!fieldAccess.isUnmodifiableByOtherCode()) {
                    continue; // remove information completely
                }

                // keep information
                newFieldValues.put(fieldAccess, otherVal);
            }
            fieldValues = newFieldValues;

            // update method values
            methodValues.clear();

            arrayValues.clear();
        }

        // store information about method call if possible
        Receiver methodCall = FlowExpressions.internalReprOf(
                analysis.getTypeFactory(), n);
        replaceValue(methodCall, val);
    }

    /**
     * Add the annotation {@code a} for the expression {@code r} (correctly
     * deciding where to store the information depending on the type of the
     * expression {@code r}).
     *
     * <p>
     * This method does not take care of removing other information that might
     * be influenced by changes to certain parts of the state.
     *
     * <p>
     * If there is already a value {@code v} present for {@code r}, then the
     * stronger of the new and old value are taken (according to the lattice).
     * Note that this happens per hierarchy, and if the store already contains
     * information about a hierarchy other than {@code a}s hierarchy, that
     * information is preserved.
     */
    public void insertValue(FlowExpressions.Receiver r, AnnotationMirror a) {
        insertValue(r, analysis.createSingleAnnotationValue(a, r.getType()));
    }

    /**
     * Returns true if the receiver {@code r} can be stored in this store.
     */
    public static boolean canInsertReceiver(Receiver r) {
        if (r instanceof FlowExpressions.FieldAccess
                || r instanceof FlowExpressions.ThisReference
                || r instanceof FlowExpressions.LocalVariable
                || r instanceof FlowExpressions.PureMethodCall
                || r instanceof FlowExpressions.ArrayAccess) {
            return !r.containsUnknown();
        }
        return false;
    }

    /**
     * Add the abstract value {@code value} for the expression {@code r}
     * (correctly deciding where to store the information depending on the type
     * of the expression {@code r}).
     *
     * <p>
     * This method does not take care of removing other information that might
     * be influenced by changes to certain parts of the state.
     *
     * <p>
     * If there is already a value {@code v} present for {@code r}, then the
     * stronger of the new and old value are taken (according to the lattice).
     * Note that this happens per hierarchy, and if the store already contains
     * information about a hierarchy for which {@code value} does not contain
     * information, then that information is preserved.
     */
    public void insertValue(FlowExpressions.Receiver r, /*@Nullable*/ V value) {
        if (value == null) {
            // No need to insert a null abstract value because it represents
            // top and top is also the default value.
            return;
        }
        if (r.containsUnknown()) {
            // Expressions containing unknown expressions are not stored.
            return;
        }
        if (r instanceof FlowExpressions.LocalVariable) {
            Element localVar = ((FlowExpressions.LocalVariable) r).getElement();
            V oldValue = localVariableValues.get(localVar);
            V newValue = value.mostSpecific(oldValue, null);
            if (newValue != null) {
                localVariableValues.put(localVar, newValue);
            }
        } else if (r instanceof FlowExpressions.FieldAccess) {
            FlowExpressions.FieldAccess fieldAcc = (FlowExpressions.FieldAccess) r;
            // Only store information about final fields (where the receiver is
            // also fixed) if concurrent semantics are enabled.
            boolean isMonotonic = isMonotonicUpdate(fieldAcc, value);
            if (sequentialSemantics || isMonotonic || fieldAcc.isUnmodifiableByOtherCode()) {
                V oldValue = fieldValues.get(fieldAcc);
                V newValue = value.mostSpecific(oldValue, null);
                if (newValue != null) {
                    fieldValues.put(fieldAcc, newValue);
                }
            }
        } else if (r instanceof FlowExpressions.PureMethodCall) {
            FlowExpressions.PureMethodCall method = (FlowExpressions.PureMethodCall) r;
            // Don't store any information if concurrent semantics are enabled.
            if (sequentialSemantics) {
                V oldValue = methodValues.get(method);
                V newValue = value.mostSpecific(oldValue, null);
                if (newValue != null) {
                    methodValues.put(method, newValue);
                }
            }
        } else if (r instanceof FlowExpressions.ArrayAccess) {
            FlowExpressions.ArrayAccess arrayAccess = (ArrayAccess) r;
            if (sequentialSemantics) {
                V oldValue = arrayValues.get(arrayAccess);
                V newValue = value.mostSpecific(oldValue, null);
                if (newValue != null) {
                    arrayValues.put(arrayAccess, newValue);
                }
            }
        } else if (r instanceof FlowExpressions.ThisReference) {
            FlowExpressions.ThisReference thisRef = (FlowExpressions.ThisReference) r;
            if (sequentialSemantics || thisRef.isUnmodifiableByOtherCode()) {
                V oldValue = thisValue;
                V newValue = value.mostSpecific(oldValue, null);
                if (newValue != null) {
                    thisValue = newValue;
                }
            }
        } else if (r instanceof FlowExpressions.ClassName) {
            FlowExpressions.ClassName className = (FlowExpressions.ClassName) r;
            if (sequentialSemantics || className.isUnmodifiableByOtherCode()) {
                V oldValue = classValues.get(className);
                V newValue = value.mostSpecific(oldValue, null);
                if (newValue != null) {
                    classValues.put(className, newValue);
                }
            }
        }
        else {
            // No other types of expressions need to be stored.
        }
    }

    /**
     * @return true if fieldAcc is an update of a monotonic qualifier to its target qualifier.
     * (e.g. @MonotonicNonNull to @NonNull)
     */
    private boolean isMonotonicUpdate(FieldAccess fieldAcc, V value) {
        boolean isMonotonic = false;
        if (!sequentialSemantics) { // only compute if necessary
            AnnotatedTypeFactory atypeFactory = this.analysis.atypeFactory;
            List<Pair<AnnotationMirror, AnnotationMirror>> fieldAnnotations =
                    atypeFactory.getAnnotationWithMetaAnnotation(
                            fieldAcc.getField(),
                            MonotonicQualifier.class);
            for (Pair<AnnotationMirror, AnnotationMirror> fieldAnnotation : fieldAnnotations) {
                AnnotationMirror monotonicAnnotation = fieldAnnotation.second;
                Name annotation = AnnotationUtils.getElementValueClassName(
                        monotonicAnnotation, "value", false);
                AnnotationMirror target = AnnotationUtils.fromName(
                        atypeFactory.getElementUtils(), annotation);
                AnnotationMirror valueAM = value.getType().getAnnotationInHierarchy(target);
                // Make sure the 'target' annotation is present.
                if (valueAM != null && AnnotationUtils.areSame(valueAM, target)) {
                    isMonotonic = true;
                    break;
                }
            }
        }
        return isMonotonic;
    }

    public void insertValueToLocalVariableByName(String identifier, AnnotationMirror a) {
        if (a == null) {
            return;
        }

        for (Entry<Element, V> e : localVariableValues.entrySet()) {
            Element localVar = e.getKey();
            if (localVar.getSimpleName().toString().equals(identifier)) {
                V value = analysis.createSingleAnnotationValue(a, localVar.asType());

                V oldValue = localVariableValues.get(localVar);
                V newValue = value.mostSpecific(oldValue, null);
                if (newValue != null) {
                    localVariableValues.put(localVar, newValue);
                }
            }
        }
    }

    public void insertThisValue(AnnotationMirror a, TypeMirror underlyingType) {
        if (a == null) {
            return;
        }

        V value = analysis.createSingleAnnotationValue(a, underlyingType);

        V oldValue = thisValue;
        V newValue = value.mostSpecific(oldValue, null);
        if (newValue != null) {
            thisValue = newValue;
        }
    }

    /**
     * Completely replaces the abstract value {@code value} for the expression
     * {@code r} (correctly deciding where to store the information depending on
     * the type of the expression {@code r}). Any previous information is
     * discarded.
     *
     * <p>
     * This method does not take care of removing other information that might
     * be influenced by changes to certain parts of the state.
     */
    public void replaceValue(FlowExpressions.Receiver r, /*@Nullable*/ V value) {
        clearValue(r);
        insertValue(r, value);
    }

    /**
     * Remove any knowledge about the expression {@code r} (correctly deciding
     * where to remove the information depending on the type of the expression
     * {@code r}).
     */
    public void clearValue(FlowExpressions.Receiver r) {
        if (r.containsUnknown()) {
            // Expressions containing unknown expressions are not stored.
            return;
        }
        if (r instanceof FlowExpressions.LocalVariable) {
            Element localVar = ((FlowExpressions.LocalVariable) r).getElement();
            localVariableValues.remove(localVar);
        } else if (r instanceof FlowExpressions.FieldAccess) {
            FlowExpressions.FieldAccess fieldAcc = (FlowExpressions.FieldAccess) r;
            fieldValues.remove(fieldAcc);
        } else if (r instanceof FlowExpressions.PureMethodCall) {
            PureMethodCall method = (PureMethodCall) r;
            methodValues.remove(method);
        } else if (r instanceof FlowExpressions.ArrayAccess) {
            ArrayAccess a = (ArrayAccess) r;
            arrayValues.remove(a);
        } else if (r instanceof FlowExpressions.ClassName) {
            FlowExpressions.ClassName c = (FlowExpressions.ClassName) r;
            classValues.remove(c);
        } else { // thisValue ...
            // No other types of expressions are stored.
        }
    }

    /**
     * @return Current abstract value of a flow expression, or {@code null} if
     *         no information is available.
     */
    public /*@Nullable*/ V getValue(FlowExpressions.Receiver expr) {
        if (expr instanceof FlowExpressions.LocalVariable) {
            Element localVar = ((FlowExpressions.LocalVariable) expr)
                    .getElement();
            return localVariableValues.get(localVar);
        } else if (expr instanceof FlowExpressions.ThisReference) {
            return thisValue;
        } else if (expr instanceof FlowExpressions.FieldAccess) {
            FlowExpressions.FieldAccess fieldAcc = (FlowExpressions.FieldAccess) expr;
            return fieldValues.get(fieldAcc);
        } else if (expr instanceof FlowExpressions.PureMethodCall) {
            FlowExpressions.PureMethodCall method = (FlowExpressions.PureMethodCall) expr;
            return methodValues.get(method);
        } else if (expr instanceof FlowExpressions.ArrayAccess) {
            FlowExpressions.ArrayAccess a = (FlowExpressions.ArrayAccess) expr;
            return arrayValues.get(a);
        } else if (expr instanceof FlowExpressions.ClassName) {
            FlowExpressions.ClassName c = (FlowExpressions.ClassName) expr;
            return classValues.get(c);
        } else {
            assert false;
            return null;
        }
    }

    public V getValueOfLocalVariableByName(String identifier)
    {
        for (Entry<Element, V> e : localVariableValues.entrySet()) {
            if (e.getKey().getSimpleName().toString().equals(identifier)) {
                return e.getValue();
            }
        }

        return null;
    }

    /**
     * @return Current abstract value of a field access, or {@code null} if no
     *         information is available.
     */
    public /*@Nullable*/ V getValue(FieldAccessNode n) {
        FlowExpressions.FieldAccess fieldAccess = FlowExpressions
                .internalReprOfFieldAccess(analysis.getTypeFactory(), n);
        return fieldValues.get(fieldAccess);
    }

    /**
     * @return Current abstract value of a method call, or {@code null} if no
     *         information is available.
     */
    public /*@Nullable*/ V getValue(MethodInvocationNode n) {
        Receiver method = FlowExpressions.internalReprOf(analysis.getTypeFactory(),
                n, true);
        if (method == null) {
            return null;
        }
        return methodValues.get(method);
    }

    /**
     * @return Current abstract value of a field access, or {@code null} if no
     *         information is available.
     */
    public /*@Nullable*/ V getValue(ArrayAccessNode n) {
        FlowExpressions.ArrayAccess arrayAccess = FlowExpressions
                .internalReprOfArrayAccess(analysis.getTypeFactory(), n);
        return arrayValues.get(arrayAccess);
    }

    /**
     * Update the information in the store by considering an assignment with
     * target {@code n}.
     */
    public void updateForAssignment(Node n, /*@Nullable*/ V val) {
        Receiver receiver = FlowExpressions.internalReprOf(
                analysis.getTypeFactory(), n);
        if (receiver instanceof ArrayAccess) {
            updateForArrayAssignment((ArrayAccess) receiver, val);
        } else if (receiver instanceof FieldAccess) {
            updateForFieldAccessAssignment((FieldAccess) receiver, val);
        } else if (receiver instanceof LocalVariable) {
            updateForLocalVariableAssignment((LocalVariable) receiver, val);
        } else {
            assert false : "Unexpected receiver of class "
                    + receiver.getClass();
        }
    }

    /**
     * Update the information in the store by considering a field assignment
     * with target {@code n}, where the right hand side has the abstract value
     * {@code val}.
     *
     * @param val
     *            The abstract value of the value assigned to {@code n} (or
     *            {@code null} if the abstract value is not known).
     */
    protected void updateForFieldAccessAssignment(FieldAccess fieldAccess,
            /*@Nullable*/ V val) {
        removeConflicting(fieldAccess, val);
        if (!fieldAccess.containsUnknown() && val != null) {
            // Only store information about final fields (where the receiver is
            // also fixed) if concurrent semantics are enabled.
            boolean isMonotonic = isMonotonicUpdate(fieldAccess, val);
            if (sequentialSemantics || isMonotonic || fieldAccess.isUnmodifiableByOtherCode()) {
                fieldValues.put(fieldAccess, val);
            }
        }
    }

    /**
     * Update the information in the store by considering an assignment with
     * target {@code n}, where the target is an array access.
     *
     * <ol>
     * <li value="1">Remove any abstract values for field accesses <em>b.g</em>
     * where {@code n} might alias any expression in the receiver <em>b</em>.
     * <li value="2">Remove any information about pure method calls.
     * </ol>
     */
    protected void updateForArrayAssignment(ArrayAccess arrayAccess,
            /*@Nullable*/ V val) {
        removeConflicting(arrayAccess, val);
        if (!arrayAccess.containsUnknown() && val != null) {
            // Only store information about final fields (where the receiver is
            // also fixed) if concurrent semantics are enabled.
            if (sequentialSemantics) {
                arrayValues.put(arrayAccess, val);
            }
        }
    }

    /**
     * Set the abstract value of a local variable in the store. Overwrites any
     * value that might have been available previously.
     *
     * @param val
     *            The abstract value of the value assigned to {@code n} (or
     *            {@code null} if the abstract value is not known).
     */
    protected void updateForLocalVariableAssignment(LocalVariable receiver,
            /*@Nullable*/ V val) {
        removeConflicting(receiver);
        if (val != null) {
            localVariableValues.put(receiver.getElement(), val);
        }
    }

    /**
     * Remove any information in this store that might not be true any more
     * after {@code fieldAccess} has been assigned a new value (with the
     * abstract value {@code val}). This includes the following steps (assume
     * that {@code fieldAccess} is of the form <em>a.f</em> for some <em>a</em>.
     *
     * <ol>
     * <li value="1">Update the abstract value of other field accesses
     * <em>b.g</em> where the field is equal (that is, <em>f=g</em>), and the
     * receiver <em>b</em> might alias the receiver of {@code fieldAccess},
     * <em>a</em>. This update will raise the abstract value for such field
     * accesses to at least {@code val} (or the old value, if that was less
     * precise). However, this is only necessary if the field <em>g</em> is not
     * final.
     * <li value="2">Remove any abstract values for field accesses <em>b.g</em>
     * where {@code fieldAccess} might alias any expression in the receiver
     * <em>b</em>.
     * <li value="3">Remove any information about pure method calls.
     * <li value="4">Remove any abstract values an arrary access <em>b[i]</em>
     * where {@code fieldAccess} might alias any expression in the receiver
     * <em>a</em> or index <em>i</em>.
     * </ol>
     *
     * @param val
     *            The abstract value of the value assigned to {@code n} (or
     *            {@code null} if the abstract value is not known).
     */
    protected void removeConflicting(FlowExpressions.FieldAccess fieldAccess,
            /*@Nullable*/ V val) {
        Map<FlowExpressions.FieldAccess, V> newFieldValues = new HashMap<>();
        for (Entry<FlowExpressions.FieldAccess, V> e : fieldValues.entrySet()) {
            FlowExpressions.FieldAccess otherFieldAccess = e.getKey();
            V otherVal = e.getValue();
            // case 2:
            if (otherFieldAccess.getReceiver().containsModifiableAliasOf(this,
                    fieldAccess)) {
                continue; // remove information completely
            }
            // case 1:
            if (fieldAccess.getField().equals(otherFieldAccess.getField())) {
                if (canAlias(fieldAccess.getReceiver(),
                        otherFieldAccess.getReceiver())) {
                    if (!otherFieldAccess.isFinal()) {
                        if (val != null) {
                            V newVal = val.leastUpperBound(otherVal);
                            newFieldValues.put(otherFieldAccess, newVal);
                        } else {
                            // remove information completely
                        }
                        continue;
                    }
                }
            }
            // information is save to be carried over
            newFieldValues.put(otherFieldAccess, otherVal);
        }
        fieldValues = newFieldValues;

        Map<FlowExpressions.ArrayAccess, V> newArrayValues = new HashMap<>();
        for (Entry<ArrayAccess, V> e : arrayValues.entrySet()) {
            FlowExpressions.ArrayAccess otherArrayAccess = e.getKey();
            V otherVal = e.getValue();
            if (otherArrayAccess.containsModifiableAliasOf(this, fieldAccess)) {
                // remove information completely
                continue;
            }
            newArrayValues.put(otherArrayAccess, otherVal);
        }
        arrayValues = newArrayValues;

        // case 3:
        methodValues = new HashMap<>();
    }

    /**
     * Remove any information in the store that might not be true any more after
     * {@code arrayAccess} has been assigned a new value (with the abstract
     * value {@code val}). This includes the following steps (assume that
     * {@code arrayAccess} is of the form <em>a[i]</em> for some <em>a</em>.
     *
     * <ol>
     * <li value="1">Remove any abstract value for other array access
     * <em>b[j]</em> where <em>a</em> and <em>b</em> can be aliases, or where
     * either <em>b</em> or <em>j</em> contains a modifiable alias of
     * <em>a[i]</em>.
     * <li value="2">Remove any abstract values for field accesses <em>b.g</em>
     * where <em>a[i]</em> might alias any expression in the receiver <em>b</em>
     * and there is an array expression somewhere in the receiver.
     * <li value="3">Remove any information about pure method calls.
     * </ol>
     *
     * @param val
     *            The abstract value of the value assigned to {@code n} (or
     *            {@code null} if the abstract value is not known).
     */
    protected void removeConflicting(FlowExpressions.ArrayAccess arrayAccess,
            /*@Nullable*/ V val) {
        Map<FlowExpressions.ArrayAccess, V> newArrayValues = new HashMap<>();
        for (Entry<FlowExpressions.ArrayAccess, V> e : arrayValues.entrySet()) {
            FlowExpressions.ArrayAccess otherArrayAccess = e.getKey();
            V otherVal = e.getValue();
            // case 1:
            if (otherArrayAccess.containsModifiableAliasOf(this, arrayAccess)) {
                continue; // remove information completely
            }
            if (canAlias(arrayAccess.getReceiver(),
                    otherArrayAccess.getReceiver())) {
                // TODO: one could be less strict here, and only raise the
                // abstract value
                // for all array expressions with potentially aliasing receivers
                continue; // remove information completely
            }
            // information is save to be carried over
            newArrayValues.put(otherArrayAccess, otherVal);
        }
        arrayValues = newArrayValues;

        // case 2:
        Map<FlowExpressions.FieldAccess, V> newFieldValues = new HashMap<>();
        for (Entry<FieldAccess, V> e : fieldValues.entrySet()) {
            FlowExpressions.FieldAccess otherFieldAccess = e.getKey();
            V otherVal = e.getValue();
            Receiver receiver = otherFieldAccess.getReceiver();
            if (receiver.containsModifiableAliasOf(this, arrayAccess) && receiver.containsOfClass(ArrayAccess.class)) {
                // remove information completely
                continue;
            }
            newFieldValues.put(otherFieldAccess, otherVal);
        }
        fieldValues = newFieldValues;

        // case 3:
        methodValues = new HashMap<>();
    }

    /**
     * Remove any information in this store that might not be true any more
     * after {@code localVar} has been assigned a new value. This includes the
     * following steps:
     *
     * <ol>
     * <li value="1">Remove any abstract values for field accesses <em>b.g</em>
     * where {@code localVar} might alias any expression in the receiver
     * <em>b</em>.
     * <li value="1">Remove any abstract values for array accesses <em>a[i]</em>
     * where {@code localVar} might alias the receiver <em>a</em>.
     * <li value="3">Remove any information about pure method calls where the
     * receiver or any of the parameters contains {@code localVar}.
     * </ol>
     */
    protected void removeConflicting(LocalVariable var) {
        Map<FlowExpressions.FieldAccess, V> newFieldValues = new HashMap<>();
        for (Entry<FlowExpressions.FieldAccess, V> e : fieldValues.entrySet()) {
            FlowExpressions.FieldAccess otherFieldAccess = e.getKey();
            // case 1:
            if (otherFieldAccess.containsSyntacticEqualReceiver(var)) {
                continue;
            }
            newFieldValues.put(otherFieldAccess, e.getValue());
        }
        fieldValues = newFieldValues;

        Map<FlowExpressions.ArrayAccess, V> newArrayValues = new HashMap<>();
        for (Entry<FlowExpressions.ArrayAccess, V> e : arrayValues.entrySet()) {
            FlowExpressions.ArrayAccess otherArrayAccess = e.getKey();
            // case 2:
            if (otherArrayAccess.containsSyntacticEqualReceiver(var)) {
                continue;
            }
            newArrayValues.put(otherArrayAccess, e.getValue());
        }
        arrayValues = newArrayValues;

        Map<FlowExpressions.PureMethodCall, V> newMethodValues = new HashMap<>();
        for (Entry<FlowExpressions.PureMethodCall, V> e : methodValues
                .entrySet()) {
            FlowExpressions.PureMethodCall otherMethodAccess = e.getKey();
            // case 3:
            if (otherMethodAccess.containsSyntacticEqualReceiver(var)
                    || otherMethodAccess.containsSyntacticEqualParameter(var)) {
                continue;
            }
            newMethodValues.put(otherMethodAccess, e.getValue());
        }
        methodValues = newMethodValues;
    }

    /**
     * Can the objects {@code a} and {@code b} be aliases? Returns a
     * conservative answer (i.e., returns {@code true} if not enough information
     * is available to determine aliasing).
     */
    @Override
    public boolean canAlias(FlowExpressions.Receiver a,
            FlowExpressions.Receiver b) {
        TypeMirror tb = b.getType();
        TypeMirror ta = a.getType();
        Types types = analysis.getTypes();
        return types.isSubtype(ta, tb) || types.isSubtype(tb, ta);
    }

    /* --------------------------------------------------------- */
    /* Handling of local variables */
    /* --------------------------------------------------------- */

    /**
     * @return Current abstract value of a local variable, or {@code null} if no
     *         information is available.
     */
    public /*@Nullable*/ V getValue(LocalVariableNode n) {
        Element el = n.getElement();
        return localVariableValues.get(el);
    }

    /* --------------------------------------------------------- */
    /* Handling of the current object */
    /* --------------------------------------------------------- */

    /**
     * @return Current abstract value of a local variable, or {@code null} if no
     *         information is available.
     */
    public /*@Nullable*/ V getValue(ThisLiteralNode n) {
        return thisValue;
    }

    /* --------------------------------------------------------- */
    /* Helper and miscellaneous methods */
    /* --------------------------------------------------------- */

    @SuppressWarnings("unchecked")
    @Override
    public S copy() {
        return analysis.createCopiedStore((S) this);
    }

    @Override
    public S leastUpperBound(S other) {
        S newStore = analysis.createEmptyStore(sequentialSemantics);

        for (Entry<Element, V> e : other.localVariableValues.entrySet()) {
            // local variables that are only part of one store, but not the
            // other are discarded, as one of store implicitly contains 'top'
            // for that variable.
            Element el = e.getKey();
            if (localVariableValues.containsKey(el)) {
                V otherVal = e.getValue();
                V thisVal = localVariableValues.get(el);
                V mergedVal = thisVal.leastUpperBound(otherVal);
                if (mergedVal != null) {
                    newStore.localVariableValues.put(el, mergedVal);
                }
            }
        }

        // information about the current object
        {
            V otherVal = other.thisValue;
            V myVal = thisValue;
            V mergedVal = myVal == null ? null : myVal.leastUpperBound(otherVal);
            if (mergedVal != null) {
                newStore.thisValue = mergedVal;
            }
        }

        for (Entry<FlowExpressions.FieldAccess, V> e : other.fieldValues
                .entrySet()) {
            // information about fields that are only part of one store, but not
            // the other are discarded, as one store implicitly contains 'top'
            // for that field.
            FlowExpressions.FieldAccess el = e.getKey();
            if (fieldValues.containsKey(el)) {
                V otherVal = e.getValue();
                V thisVal = fieldValues.get(el);
                V mergedVal = thisVal.leastUpperBound(otherVal);
                if (mergedVal != null) {
                    newStore.fieldValues.put(el, mergedVal);
                }
            }
        }
        for (Entry<FlowExpressions.ArrayAccess, V> e : other.arrayValues
                .entrySet()) {
            // information about arrays that are only part of one store, but not
            // the other are discarded, as one store implicitly contains 'top'
            // for that array access.
            FlowExpressions.ArrayAccess el = e.getKey();
            if (arrayValues.containsKey(el)) {
                V otherVal = e.getValue();
                V thisVal = arrayValues.get(el);
                V mergedVal = thisVal.leastUpperBound(otherVal);
                if (mergedVal != null) {
                    newStore.arrayValues.put(el, mergedVal);
                }
            }
        }
        for (Entry<PureMethodCall, V> e : other.methodValues.entrySet()) {
            // information about methods that are only part of one store, but
            // not the other are discarded, as one store implicitly contains
            // 'top' for that field.
            FlowExpressions.PureMethodCall el = e.getKey();
            if (methodValues.containsKey(el)) {
                V otherVal = e.getValue();
                V thisVal = methodValues.get(el);
                V mergedVal = thisVal.leastUpperBound(otherVal);
                if (mergedVal != null) {
                    newStore.methodValues.put(el, mergedVal);
                }
            }
        }
        for (Entry<FlowExpressions.ClassName, V> e : other.classValues
                .entrySet()) {
            FlowExpressions.ClassName el = e.getKey();
            if (classValues.containsKey(el)) {
                V otherVal = e.getValue();
                V thisVal = classValues.get(el);
                V mergedVal = thisVal.leastUpperBound(otherVal);
                if (mergedVal != null) {
                    newStore.classValues.put(el, mergedVal);
                }
            }
        }
        return newStore;
    }

    /**
     * Returns true iff this {@link CFAbstractStore} contains a superset of the
     * map entries of the argument {@link CFAbstractStore}. Note that we test
     * the entry keys and values by Java equality, not by any subtype
     * relationship. This method is used primarily to simplify the equals
     * predicate.
     */
    protected boolean supersetOf(CFAbstractStore<V, S> other) {
        for (Entry<Element, V> e : other.localVariableValues.entrySet()) {
            Element key = e.getKey();
            if (!localVariableValues.containsKey(key)
                    || !localVariableValues.get(key).equals(e.getValue())) {
                return false;
            }
        }
        for (Entry<FlowExpressions.FieldAccess, V> e : other.fieldValues
                .entrySet()) {
            FlowExpressions.FieldAccess key = e.getKey();
            if (!fieldValues.containsKey(key)
                    || !fieldValues.get(key).equals(e.getValue())) {
                return false;
            }
        }
        for (Entry<FlowExpressions.ArrayAccess, V> e : other.arrayValues
                .entrySet()) {
            FlowExpressions.ArrayAccess key = e.getKey();
            if (!arrayValues.containsKey(key)
                    || !arrayValues.get(key).equals(e.getValue())) {
                return false;
            }
        }
        for (Entry<PureMethodCall, V> e : other.methodValues.entrySet()) {
            FlowExpressions.PureMethodCall key = e.getKey();
            if (!methodValues.containsKey(key)
                    || !methodValues.get(key).equals(e.getValue())) {
                return false;
            }
        }
        for (Entry<FlowExpressions.ClassName, V> e : other.classValues
                .entrySet()) {
            FlowExpressions.ClassName key = e.getKey();
            if (!classValues.containsKey(key)
                    || !classValues.get(key).equals(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (o != null && o instanceof CFAbstractStore) {
            @SuppressWarnings("unchecked")
            CFAbstractStore<V, S> other = (CFAbstractStore<V, S>) o;
            return this.supersetOf(other) && other.supersetOf(this);
        } else {
            return false;
        }
    }

    @SideEffectFree
    @Override
    public String toString() {
        return toDOToutput().replace("\\n", "\n");
    }

    @Pure
    @Override
    public boolean hasDOToutput() {
        return true;
    }

    /**
     * @return DOT representation of the store (may contain control characters
     *         such as "\n").
     */
    @Override
    public String toDOToutput() {
        StringBuilder result = new StringBuilder(this.getClass()
                .getCanonicalName() + " (\\n");
        internalDotOutput(result);
        result.append(")");
        return result.toString();
    }

    /**
     * Adds a DOT representation of the internal information of this store to
     * {@code result}.
     */
    protected void internalDotOutput(StringBuilder result) {
        for (Entry<Element, V> entry : localVariableValues.entrySet()) {
            result.append("  " + entry.getKey() + " > " + entry.getValue()
                    + "\\n");
        }
        if (thisValue != null) {
            result.append("  this > " + thisValue
                    + "\\n");
        }
        for (Entry<FlowExpressions.FieldAccess, V> entry : fieldValues
                .entrySet()) {
            result.append("  " + entry.getKey() + " > " + entry.getValue()
                    + "\\n");
        }
        for (Entry<FlowExpressions.ArrayAccess, V> entry : arrayValues
                .entrySet()) {
            result.append("  " + entry.getKey() + " > " + entry.getValue()
                    + "\\n");
        }
        for (Entry<PureMethodCall, V> entry : methodValues.entrySet()) {
            result.append("  " + entry.getKey().toString().replace("\"", "\\\"")
                    + " > " + entry.getValue() + "\\n");
        }
        for (Entry<FlowExpressions.ClassName, V> entry : classValues
                .entrySet()) {
            result.append("  " + entry.getKey() + " > " + entry.getValue()
                    + "\\n");
        }
    }
}
