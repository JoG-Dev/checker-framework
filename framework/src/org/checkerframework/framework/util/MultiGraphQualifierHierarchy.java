package org.checkerframework.framework.util;

/*>>>
import org.checkerframework.checker.interning.qual.*;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;
*/

import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.framework.qual.PolymorphicQualifier;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.ErrorReporter;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;

/**
 * Represents the type qualifier hierarchy of a type system.
 *
 * This class is immutable and can be only created through {@link MultiGraphFactory}.
 *
 * A QualifierHierarchy that supports multiple separate subtype hierarchies.
 */
public class MultiGraphQualifierHierarchy extends QualifierHierarchy {

    /**
     * Factory used to create an instance of {@link GraphQualifierHierarchy}.
     * A factory can be used to create at most one {@link GraphQualifierHierarchy}.
     *
     * To create a hierarchy, a client may do so in three steps:
     * 1. add qualifiers using {@link #addQualifier(AnnotationMirror)};
     * 2. add subtype relations using {@link #addSubtype(AnnotationMirror, AnnotationMirror)}
     * 3. build the hierarchy and gets using {@link #build()}.
     *
     * Notice that {@link #addSubtype(AnnotationMirror, AnnotationMirror)} adds
     * the two qualifiers to the hierarchy if they are not already in.
     *
     * Also, once the client builds a hierarchy through {@link #build()},
     * no further modifications are allowed nor can it making a new instance.
     *
     * Clients build the hierarchy using {@link #addQualifier(AnnotationMirror)}
     * and {@link #addSubtype(AnnotationMirror, AnnotationMirror)}, then get
     * the instance with calling {@link #build()}
     */
    public static class MultiGraphFactory {
        /**
         * Map from qualifiers to the direct supertypes of the qualifier.
         * Only the subtype relations given by addSubtype are in this mapping,
         * no transitive relationships.
         * It is immutable once GraphQualifierHierarchy is built.
         * No polymorphic qualifiers are contained in this map.
         */
        protected final Map<AnnotationMirror, Set<AnnotationMirror>> supertypes;

        /**
         * Map from qualifier hierarchy to the corresponding polymorphic qualifier.
         * The key is the declared qualifier given in @PolymorphicQualifier.
         * Note that the non-type-qualifier key "PolymorphicQualifier" is used
         * for a type system with a single hierarchy that gives no explicit top.
         * Key "null" is used for the PolyAll qualifier.
         */
        protected final Map<AnnotationMirror, AnnotationMirror> polyQualifiers;

        protected final AnnotatedTypeFactory atypeFactory;

        public MultiGraphFactory(AnnotatedTypeFactory atypeFactory) {
            this.supertypes = AnnotationUtils.createAnnotationMap();
            this.polyQualifiers = new HashMap<AnnotationMirror, AnnotationMirror>();
            this.atypeFactory = atypeFactory;
        }

        /**
         * Adds the passed qualifier to the hierarchy.  Clients need to specify
         * its super qualifiers in subsequent calls to
         * {@link #addSubtype(AnnotationMirror, AnnotationMirror)}.
         */
        public void addQualifier(AnnotationMirror qual) {
            assertNotBuilt();
            if (supertypes.containsKey(qual))
                return;

            Class<? extends Annotation> pqtopclass = QualifierPolymorphism.getPolymorphicQualifierTop(atypeFactory.getElementUtils(), qual);
            if (pqtopclass != null) {
                AnnotationMirror pqtop = AnnotationUtils.fromClass(atypeFactory.getElementUtils(), pqtopclass);
                if (QualifierPolymorphism.isPolyAll(qual)) {
                    // Use key null as marker for polyall
                    this.polyQualifiers.put(null, qual);
                } else {
                    // use given top (which might be PolymorphicQualifier) as key
                    this.polyQualifiers.put(pqtop, qual);
                }
            } else {
                supertypes.put(qual, AnnotationUtils.createAnnotationSet());
            }
        }

        /**
         * Adds a subtype relationship between the two type qualifiers.
         * Assumes that both qualifiers are part of the same qualifier hierarchy;
         * callers should ensure this.
         *
         * @param sub   the sub type qualifier
         * @param sup   the super type qualifier
         */
        public void addSubtype(AnnotationMirror sub, AnnotationMirror sup) {
            assertNotBuilt();
            addQualifier(sub);
            addQualifier(sup);
            supertypes.get(sub).add(sup);
        }

        /**
         * Returns an instance of {@link GraphQualifierHierarchy} that
         * represents the hierarchy built so far
         */
        public QualifierHierarchy build() {
            assertNotBuilt();
            QualifierHierarchy result = createQualifierHierarchy();
            wasBuilt = true;
            return result;
        }

        protected QualifierHierarchy createQualifierHierarchy() {
            return atypeFactory.createQualifierHierarchy(this);
        }

        private boolean wasBuilt = false;

        protected void assertNotBuilt() {
            if (wasBuilt) {
                ErrorReporter.errorAbort("MultiGraphQualifierHierarchy.Factory was already built. Method build can only be called once.");
            }
        }
    }

    /**
     * The declared, direct supertypes for each qualifier, without added
     * transitive relations.
     * Immutable after construction finishes.
     * No polymorphic qualifiers are contained in this map.
     *
     * @see MultiGraphQualifierHierarchy.MultiGraphFactory#supertypes
     */
    protected final Map<AnnotationMirror, Set<AnnotationMirror>> supertypesGraph;

    /**
     * The transitive closure of the supertypesGraph.
     * Immutable after construction finishes.
     */
    protected final Map<AnnotationMirror, Set<AnnotationMirror>> supertypesMap;

    /**
     * The top qualifiers of the individual type hierarchies.
     */
    protected final Set<AnnotationMirror> tops;

    /**
     * The bottom qualifiers of the type hierarchies.
     * TODO: clarify relation to tops.
     */
    protected final Set<AnnotationMirror> bottoms;

    /**
     * Reference to the special qualifier org.checkerframework.framework.qual.PolymorphicQualifier.
     * It is used as a key in polyQualifiers, if the qualifier hierarchy
     * consists of a single top and no specific qualifier was specified.
     */
    protected final AnnotationMirror polymorphicQualifier;

    /**
     * @see MultiGraphQualifierHierarchy.MultiGraphFactory#polyQualifiers
     */
    protected final Map<AnnotationMirror, AnnotationMirror> polyQualifiers;

    public MultiGraphQualifierHierarchy(MultiGraphFactory f) {
        this(f, (Object[]) null);
    }

    // Allow a subclass to provide additional constructor parameters that
    // are simply passed back via a call to the "finish" method.
    public MultiGraphQualifierHierarchy(MultiGraphFactory f, Object... args) {
        super();
        // no need for copying as f.supertypes has no mutable references to it
        // TODO: also make the Set of supertypes immutable?
        this.supertypesGraph = Collections.unmodifiableMap(f.supertypes);

        // Calculate the transitive closure
        Map<AnnotationMirror, Set<AnnotationMirror>>  fullMap = buildFullMap(f.supertypes);

        Set<AnnotationMirror> newtops = findTops(fullMap);
        Set<AnnotationMirror> newbottoms = findBottoms(fullMap);

        this.polymorphicQualifier = AnnotationUtils.fromClass(f.atypeFactory.getElementUtils(), PolymorphicQualifier.class);
        this.polyQualifiers = f.polyQualifiers;

        addPolyRelations(this,
                fullMap, this.polyQualifiers,
                newtops, newbottoms);

        finish(this, fullMap, this.polyQualifiers,
                newtops, newbottoms, args);

        this.tops = Collections.unmodifiableSet(newtops);
        this.bottoms = Collections.unmodifiableSet(newbottoms);
        // TODO: make polyQualifiers immutable also?

        this.supertypesMap = Collections.unmodifiableMap(fullMap);
        // System.out.println("MGH: " + this);
    }

    /**
     * Method to finalize the qualifier hierarchy before it becomes unmodifiable.
     * The parameters pass all fields and allow modification.
     */
    protected void finish(QualifierHierarchy qualHierarchy,
            Map<AnnotationMirror, Set<AnnotationMirror>> fullMap,
            Map<AnnotationMirror, AnnotationMirror> polyQualifiers,
            Set<AnnotationMirror> tops, Set<AnnotationMirror> bottoms,
            Object... args) { }

    @SideEffectFree
    @Override
    public String toString() {
        // TODO: it would be easier to debug if the graph and map were sorted by the key.
        // Simply creating a TreeMap here doesn't work, because AnnotationMirrors are not comparable.
        return "Supertypes Graph: " + supertypesGraph.toString() +
                "\nSupertypes Map: " + String.valueOf(supertypesMap) +
                "\nTops: " + tops +
                "\nBottoms: " + bottoms;
    }

    @Override
    public Set<? extends AnnotationMirror> getTopAnnotations() {
        return this.tops;
    }

    @Override
    public AnnotationMirror getTopAnnotation(AnnotationMirror start) {
        for (AnnotationMirror top : tops) {
            if (AnnotationUtils.areSame(start, top) ||
                    isSubtype(start, top)) {
                return top;
            }
        }
        ErrorReporter.errorAbort("MultiGraphQualifierHierarchy: did not find the top corresponding to qualifier " + start +
                " all tops: " + tops);
        return null;
    }

    @Override
    public Set<? extends AnnotationMirror> getBottomAnnotations() {
        return this.bottoms;
    }

    @Override
    public AnnotationMirror getBottomAnnotation(AnnotationMirror start) {
        for (AnnotationMirror bot : bottoms) {
            if (AnnotationUtils.areSame(start, bot) ||
                    isSubtype(bot, start)) {
                return bot;
            }
        }
        ErrorReporter.errorAbort("MultiGraphQualifierHierarchy: did not find the bottom corresponding to qualifier " + start +
                "; all bottoms: " + bottoms + "; this: " + this);
        return null;
    }

    @Override
    public AnnotationMirror getPolymorphicAnnotation(AnnotationMirror start) {
        AnnotationMirror top = getTopAnnotation(start);
        if (polyQualifiers.containsKey(top)) {
            return polyQualifiers.get(top);
        } else if (polyQualifiers.containsKey(polymorphicQualifier)) {
            return polyQualifiers.get(polymorphicQualifier);
        } else {
            // No polymorphic qualifier exists for that hierarchy.
            ErrorReporter.errorAbort("MultiGraphQualifierHierarchy: did not find the polymorphic qualifier corresponding to qualifier " + start +
                    "; all polymorphic qualifiers: " + polyQualifiers  + "; this: " + this);
            return null;
        }
    }

    @Override
    public boolean isSubtype(Collection<? extends AnnotationMirror> rhs, Collection<? extends AnnotationMirror> lhs) {
        if (lhs.isEmpty() || rhs.isEmpty()) {
            ErrorReporter.errorAbort("MultiGraphQualifierHierarchy: empty annotations in lhs: " + lhs + " or rhs: " + rhs);
        }
        if (lhs.size() != rhs.size()) {
            ErrorReporter.errorAbort("MultiGraphQualifierHierarchy: mismatched number of annotations in lhs: " + lhs + " and rhs: " + rhs);
        }
        int valid = 0;
        for (AnnotationMirror lhsAnno : lhs) {
            for (AnnotationMirror rhsAnno : rhs) {
                if (AnnotationUtils.areSame(getTopAnnotation(lhsAnno), getTopAnnotation(rhsAnno)) &&
                        isSubtype(rhsAnno, lhsAnno)) {
                    ++valid;
                }
            }
        }
        return lhs.size() == valid;
    }

    @Override
    public boolean isSubtypeTypeVariable(Collection<? extends AnnotationMirror> rhs, Collection<? extends AnnotationMirror> lhs) {
        for (AnnotationMirror top : getTopAnnotations()) {
            AnnotationMirror rhsForTop = getAnnotationInHierarchy(rhs, top);
            AnnotationMirror lhsForTop = getAnnotationInHierarchy(lhs, top);
            if (!isSubtypeTypeVariable(rhsForTop, lhsForTop)) {
                return false;
            }
        }
        return true;
    }

    protected Set</*@Interned*/ String> typeQualifiers = null;

    @Override
    public Set<? extends AnnotationMirror> getTypeQualifiers() {
        return Collections.unmodifiableSet(supertypesMap.keySet());
    }


    // For caching results of lubs
    private Map<AnnotationPair, AnnotationMirror> lubs = null;

    @Override
    public AnnotationMirror leastUpperBound(AnnotationMirror a1, AnnotationMirror a2) {
        if (!AnnotationUtils.areSameIgnoringValues(getTopAnnotation(a1), getTopAnnotation(a2))) {
            return null;
        } else if (isSubtype(a1, a2)) {
            return a2;
        } else if (isSubtype(a2, a1)) {
            return a1;
        } else if (AnnotationUtils.areSameIgnoringValues(a1, a2)) {
            return getTopAnnotation(a1);
        }
        if (lubs == null) {
            lubs = calculateLubs();
        }
        AnnotationPair pair = new AnnotationPair(a1, a2);
        return lubs.get(pair);
    }

    @Override
    public AnnotationMirror leastUpperBoundTypeVariable(AnnotationMirror a1, AnnotationMirror a2) {
        if (a1 == null || a2 == null) {
            // [] is a supertype of any qualifier, and [] <: []
            return null;
        }
        return leastUpperBound(a1, a2);
    }

    // For caching results of glbs
    private Map<AnnotationPair, AnnotationMirror> glbs = null;

    @Override
    public AnnotationMirror greatestLowerBound(AnnotationMirror a1, AnnotationMirror a2) {
        if (AnnotationUtils.areSameIgnoringValues(a1, a2))
            return AnnotationUtils.areSame(a1, a2) ? a1 : getBottomAnnotation(a1);
        if (glbs == null) {
            glbs = calculateGlbs();
        }
        AnnotationPair pair = new AnnotationPair(a1, a2);
        return glbs.get(pair);
    }

    @Override
    public AnnotationMirror greatestLowerBoundTypeVariable(AnnotationMirror a1, AnnotationMirror a2) {
        if (a1 == null) {
            // [] is a supertype of any qualifier, and [] <: []
            return a2;
        }
        if (a2 == null) {
            // [] is a supertype of any qualifier, and [] <: []
            return a1;
        }
        return greatestLowerBound(a1, a2);
    }

    /**
     * Most qualifiers have no value fields.  However, two annotations with
     * values are subtype of each other only if they have the same values.
     * i.e. I(m) is a subtype of I(n) iff m = n
     *
     * When client specifies an annotation, a1, to be a subtype of annotation
     * with values, a2, then a1 is a subtype of all instances of a2 regardless
     * of a2 values.  i.e. IGJBottom is a subtype of all instances of
     * {@code @I}.
     *
     * @param rhs The right-hand side, i.e. the sub qualifier
     * @param lhs The left-hand side, i.e. the super qualifier
     */
    @Override
    public boolean isSubtype(AnnotationMirror rhs, AnnotationMirror lhs) {
        checkAnnoInGraph(rhs);
        checkAnnoInGraph(lhs);

        /* TODO: this optimization leads to recursion
        for (AnnotationMirror top : tops) {
            System.out.println("Looking at top: " + tops + " and " + anno1);
            // We cannot use getRootAnnotation, as that would use subtyping and recurse
            if (isSubtype(anno1, top) && AnnotationUtils.areSame(top, anno2))
            return true;
        }*/
        if (AnnotationUtils.areSameIgnoringValues(rhs, lhs))
            return AnnotationUtils.areSame(rhs, lhs);
        Set<AnnotationMirror> supermap1 = this.supertypesMap.get(rhs);
        return AnnotationUtils.containsSame(supermap1, lhs);
    }

    @Override
    public boolean isSubtypeTypeVariable(AnnotationMirror rhs, AnnotationMirror lhs) {
        if (lhs == null) {
            // [] is a supertype of any qualifier, and [] <: []
            return true;
        }
        if (rhs == null) {
            // [] is a subtype of no qualifier (only [])
            return false;
        }
        return isSubtype(rhs, lhs);
    }

    private final void checkAnnoInGraph(AnnotationMirror a) {
        if (AnnotationUtils.containsSame(supertypesMap.keySet(), a) ||
                AnnotationUtils.containsSame(polyQualifiers.values(), a))
            return;

        if (a == null) {
            ErrorReporter.errorAbort("MultiGraphQualifierHierarchy found an unqualified type.  Please ensure that " +
                    "your implicit rules cover all cases and/or " +
                    "use a @DefaulQualifierInHierarchy annotation.");
        } else {
            // System.out.println("MultiGraphQH: " + this);
            ErrorReporter.errorAbort("MultiGraphQualifierHierarchy found the unrecognized qualifier: " + a +
                    ". Please ensure that the qualifier is correctly included in the subtype hierarchy.");
        }
    }

    /**
     * Infer the tops of the subtype hierarchy.  Simple finds the qualifiers
     * that have no supertypes.
     */
    // Not static to allow adaptation in subclasses. Only parameters should be modified.
    protected Set<AnnotationMirror>
    findTops(Map<AnnotationMirror, Set<AnnotationMirror>> supertypes) {
        Set<AnnotationMirror> possibleTops = AnnotationUtils.createAnnotationSet();
        for (AnnotationMirror anno : supertypes.keySet()) {
            if (supertypes.get(anno).isEmpty())
                possibleTops.add(anno);
        }
        return possibleTops;
    }

    /**
     * Infer the bottoms of the subtype hierarchy.  Simple finds the qualifiers
     * that are not supertypes of other qualifiers.
     */
    // Not static to allow adaptation in subclasses. Only parameters should be modified.
    protected Set<AnnotationMirror>
    findBottoms(Map<AnnotationMirror, Set<AnnotationMirror>> supertypes) {
        Set<AnnotationMirror> possibleBottoms = AnnotationUtils.createAnnotationSet();
        possibleBottoms.addAll(supertypes.keySet());
        for (Set<AnnotationMirror> supers : supertypes.values()) {
            possibleBottoms.removeAll(supers);
        }
        return possibleBottoms;
    }

    /**
     * Computes the transitive closure of the given map and returns it.
     */
    /* The method gets all required parameters passed in and could be static. However,
     * we want to allow subclasses to adapt the behavior and therefore make it an instance method.
     */
    protected Map<AnnotationMirror, Set<AnnotationMirror>>
    buildFullMap(Map<AnnotationMirror, Set<AnnotationMirror>> supertypes) {
        Map<AnnotationMirror, Set<AnnotationMirror>> fullMap = AnnotationUtils.createAnnotationMap();
        for (AnnotationMirror anno : supertypes.keySet()) {
            // this method directly modifies fullMap and is
            // ignoring the returned value
            findAllSupers(anno, supertypes, fullMap);
        }
        return fullMap;
    }


    /**
     * Add the relationships for polymorphic qualifiers.
     *
     * A polymorphic qualifier needs to be (take {@code PolyNull} for example)
     * 1. a subtype of the top qualifier (e.g. {@code Nullable})
     * 2. a supertype of all the bottom qualifiers  (e.g. {@code NonNull})
     *
     * Field supertypesMap is not set yet when this method is called - use fullMap instead.
     */
    /* The method gets all required parameters passed in and could be static. However,
     * we want to allow subclasses to adapt the behavior and therefore make it an instance method.
     */
    // TODO: document
    protected void addPolyRelations(QualifierHierarchy qualHierarchy,
            Map<AnnotationMirror, Set<AnnotationMirror>> fullMap,
            Map<AnnotationMirror, AnnotationMirror> polyQualifiers,
            Set<AnnotationMirror> tops, Set<AnnotationMirror> bottoms) {
        if (polyQualifiers.isEmpty())
            return;

        for (Map.Entry<AnnotationMirror, AnnotationMirror> kv : polyQualifiers.entrySet()) {
            AnnotationMirror declTop = kv.getKey();
            AnnotationMirror polyQualifier = kv.getValue();
            if (declTop == null || // PolyAll
                AnnotationUtils.areSame(declTop, polymorphicQualifier)) {
                if (declTop == null || // PolyAll
                        tops.size() == 1) { // un-ambigous single top
                    AnnotationUtils.updateMappingToImmutableSet(fullMap, polyQualifier, tops);
                    for (AnnotationMirror bottom : bottoms) {
                        // Add the polyqualifier as a supertype
                        // Need to copy over the set as it is unmodifiable.
                        AnnotationUtils.updateMappingToImmutableSet(fullMap, bottom, Collections.singleton(polyQualifier));
                    }
                    if (declTop == null) { // PolyAll
                        // Make all other polymorphic qualifiers a subtype of PolyAll
                        for (Map.Entry<AnnotationMirror, AnnotationMirror> otherpolyKV : polyQualifiers.entrySet()) {
                            AnnotationMirror otherTop = otherpolyKV.getKey();
                            AnnotationMirror otherPoly = otherpolyKV.getValue();
                            if (otherTop != null) {
                                AnnotationUtils.updateMappingToImmutableSet(fullMap, otherPoly, Collections.singleton(polyQualifier));
                            }
                        }
                    }
                } else {
                    ErrorReporter.errorAbort("MultiGraphQualifierHierarchy.addPolyRelations: " +
                            "incorrect top qualifier given in polymorphic qualifier (specify qualifier): " + polyQualifier +
                            "; possible top qualifiers: " + tops);
                }
            } else {
                // Ensure that it's really the top of the hierarchy
                Set<AnnotationMirror> declSupers = fullMap.get(declTop);
                AnnotationMirror polyTop = null;
                if (declSupers.isEmpty()) {
                    polyTop = declTop;
                } else {
                    for (AnnotationMirror ds : declSupers) {
                        if (AnnotationUtils.containsSameIgnoringValues(tops, ds)) {
                            polyTop = ds;
                        }
                    }
                }
                boolean found = (polyTop != null);
                if (found) {
                    AnnotationUtils.updateMappingToImmutableSet(fullMap, polyQualifier, Collections.singleton(polyTop));
                } else {
                    ErrorReporter.errorAbort("MultiGraphQualifierHierarchy.addPolyRelations: " +
                            "incorrect top qualifier given in polymorphic qualifier: " + polyQualifier +
                            " could not find: " + polyTop);
                }

                found = false;
                AnnotationMirror bottom = null;
                outer: for (AnnotationMirror btm : bottoms) {
                    for (AnnotationMirror btmsuper : fullMap.get(btm)) {
                        if (AnnotationUtils.areSameIgnoringValues(btmsuper, polyTop)) {
                            found = true;
                            bottom = btm;
                            break outer;
                        }
                    }
                }
                if (found) {
                    AnnotationUtils.updateMappingToImmutableSet(fullMap, bottom, Collections.singleton(polyQualifier));
                } else {
                    // TODO: in a type system with a single qualifier this check will fail.
                    //ErrorReporter.errorAbort("MultiGraphQualifierHierarchy.addPolyRelations: " +
                    //        "incorrect top qualifier given in polymorphic qualifier: " + polyQualifier +
                    //        " could not find bottom for: " + polyTop);
                }
            }
        }
    }

    private Map<AnnotationPair, AnnotationMirror>  calculateLubs() {
        Map<AnnotationPair, AnnotationMirror> newlubs = new HashMap<AnnotationPair, AnnotationMirror>();
        for (AnnotationMirror a1 : supertypesGraph.keySet()) {
            for (AnnotationMirror a2 : supertypesGraph.keySet()) {
                if (AnnotationUtils.areSameIgnoringValues(a1, a2))
                    continue;
                if (!AnnotationUtils.areSame(getTopAnnotation(a1), getTopAnnotation(a2)))
                    continue;
                AnnotationPair pair = new AnnotationPair(a1, a2);
                if (newlubs.containsKey(pair))
                    continue;
                AnnotationMirror lub = findLub(a1, a2);
                newlubs.put(pair, lub);
            }
        }
        return newlubs;
    }

    private AnnotationMirror findLub(AnnotationMirror a1, AnnotationMirror a2) {
        if (isSubtype(a1, a2))
            return a2;
        if (isSubtype(a2, a1))
            return a1;

        assert getTopAnnotation(a1) == getTopAnnotation(a2) :
            "MultiGraphQualifierHierarchy.findLub: this method may only be called " +
                "with qualifiers from the same hierarchy. Found a1: " + a1 + " [top: " + getTopAnnotation(a1) +
                "], a2: " + a2 + " [top: " + getTopAnnotation(a2) + "]";

        Set<AnnotationMirror> outset = AnnotationUtils.createAnnotationSet();
        for (AnnotationMirror a1Super : findSmallestTypes(supertypesMap.get(a1))) {
            // TODO: we take the first of the smallest supertypes, maybe we would
            // get a different LUB if we used a different one?
            AnnotationMirror a1Lub = findLub(a1Super, a2);
            if (a1Lub != null) {
                outset.add(a1Lub);
            }
            if (a1Lub == null && a1Super == null) {
                // null is also used for Unqualified! If two qualifiers are separate
                // subtypes of unqualifed, this might happen.
                // I ran into this when KeyFor <: Unqualified and Covariant <: Unqualified.
                // I think it would be much nicer if Unqualified would not be optimized away...
                // TODO This never seems to happen...
                outset.add(null);
            }
        }
        if (outset.size() == 1) {
            return outset.iterator().next();
        }
        if (outset.size() > 1) {
            outset = findSmallestTypes(outset);
            // TODO: more than one, incomparable supertypes. Just pick the first one.
            // if (outset.size()>1) { System.out.println("Still more than one LUB!"); }
            return outset.iterator().next();
        }

        ErrorReporter.errorAbort("GraphQualifierHierarchy could not determine LUB for " + a1 + " and " + a2 +
                                 ". Please ensure that the checker knows about all type qualifiers.");
        return null;
    }

    // remove all supertypes of elements contained in the set
    private Set<AnnotationMirror> findSmallestTypes(Set<AnnotationMirror> inset) {
        Set<AnnotationMirror> outset = AnnotationUtils.createAnnotationSet();
        outset.addAll(inset);

        for (AnnotationMirror a1 : inset) {
            Iterator<AnnotationMirror> outit = outset.iterator();
            while (outit.hasNext()) {
                AnnotationMirror a2 = outit.next();
                if (a1 != a2 && isSubtype(a1, a2)) {
                    outit.remove();
                }
            }
        }
        return outset;
    }

    /**
     * Finds all the super qualifiers for a qualifier.
     *
     * @param anno
     * @param supertypesMap
     */
    private static Set<AnnotationMirror>
    findAllSupers(AnnotationMirror anno,
            Map<AnnotationMirror, Set<AnnotationMirror>> supertypes,
            Map<AnnotationMirror, Set<AnnotationMirror>> allSupersSoFar) {
        Set<AnnotationMirror> supers = AnnotationUtils.createAnnotationSet();
        if (allSupersSoFar.containsKey(anno))
            return Collections.unmodifiableSet(allSupersSoFar.get(anno));

        // Updating the visited list before and after helps avoid
        // infinite loops. TODO: cleaner way?
        allSupersSoFar.put(anno, supers);

        for (AnnotationMirror superAnno : supertypes.get(anno)) {
            supers.add(superAnno);
            supers.addAll(findAllSupers(superAnno, supertypes, allSupersSoFar));
        }
        allSupersSoFar.put(anno, Collections.unmodifiableSet(supers));
        return supers;
    }


    private Map<AnnotationPair, AnnotationMirror>  calculateGlbs() {
        Map<AnnotationPair, AnnotationMirror> newglbs = new HashMap<AnnotationPair, AnnotationMirror>();
        for (AnnotationMirror a1 : supertypesGraph.keySet()) {
            for (AnnotationMirror a2 : supertypesGraph.keySet()) {
                if (AnnotationUtils.areSameIgnoringValues(a1, a2))
                    continue;
                if (!AnnotationUtils.areSame(getTopAnnotation(a1), getTopAnnotation(a2)))
                    continue;
                AnnotationPair pair = new AnnotationPair(a1, a2);
                if (newglbs.containsKey(pair))
                    continue;
                AnnotationMirror glb = findGlb(a1, a2);
                newglbs.put(pair, glb);
            }
        }
        return newglbs;
    }

    private AnnotationMirror findGlb(AnnotationMirror a1, AnnotationMirror a2) {
        if (isSubtype(a1, a2))
            return a1;
        if (isSubtype(a2, a1))
            return a2;

        assert getTopAnnotation(a1) == getTopAnnotation(a2) :
            "MultiGraphQualifierHierarchy.findGlb: this method may only be called " +
                "with qualifiers from the same hierarchy. Found a1: " + a1 + " [top: " + getTopAnnotation(a1) +
                "], a2: " + a2 + " [top: " + getTopAnnotation(a2) + "]";

        Set<AnnotationMirror> outset = AnnotationUtils.createAnnotationSet();
        for (AnnotationMirror a1Sub : supertypesGraph.keySet()) {
            if (isSubtype(a1Sub, a1) && !a1Sub.equals(a1)) {
                AnnotationMirror a1lb = findGlb(a1Sub, a2);
                if (a1lb != null)
                    outset.add(a1lb);
            }
        }
        if (outset.size() == 1) {
            return outset.iterator().next();
        }
        if (outset.size() > 1) {
            outset = findGreatestTypes(outset);
            // TODO: more than one, incomparable subtypes. Pick the first one.
            // if (outset.size()>1) { System.out.println("Still more than one GLB!"); }
            return outset.iterator().next();
        }

        ErrorReporter.errorAbort("MultiGraphQualifierHierarchy could not determine GLB for " + a1 + " and " + a2 +
                ". Please ensure that the checker knows about all type qualifiers.");
        return null;
    }

    // remove all subtypes of elements contained in the set
    private Set<AnnotationMirror> findGreatestTypes(Set<AnnotationMirror> inset) {
        Set<AnnotationMirror> outset = AnnotationUtils.createAnnotationSet();
        outset.addAll(inset);

        for (AnnotationMirror a1 : inset) {
            Iterator<AnnotationMirror> outit = outset.iterator();
            while (outit.hasNext()) {
                AnnotationMirror a2 = outit.next();
                if (a1 != a2 && isSubtype(a2, a1)) {
                    outit.remove();
                }
            }
        }
        return outset;
    }

    private static class AnnotationPair {
        public final AnnotationMirror a1;
        public final AnnotationMirror a2;
        private int hashCode = -1;

        public AnnotationPair(AnnotationMirror a1, AnnotationMirror a2) {
            this.a1 = a1;
            this.a2 = a2;
        }

        @Pure
        @Override
        public int hashCode() {
            if (hashCode == -1) {
                hashCode = 31;
                if (a1 != null)
                    hashCode += 17 * AnnotationUtils.annotationName(a1).toString().hashCode();
                if (a2 != null)
                    hashCode += 17 * AnnotationUtils.annotationName(a2).toString().hashCode();
            }
            return hashCode;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AnnotationPair))
                return false;
            AnnotationPair other = (AnnotationPair)o;
            if (AnnotationUtils.areSameIgnoringValues(a1, other.a1)
                    && AnnotationUtils.areSameIgnoringValues(a2, other.a2))
                return true;
            if (AnnotationUtils.areSameIgnoringValues(a2, other.a1)
                    && AnnotationUtils.areSameIgnoringValues(a1, other.a2))
                return true;
            return false;
        }

        @SideEffectFree
        @Override
        public String toString() {
            return "AnnotationPair(" + a1 + ", " + a2 + ")";
        }
    }
}
