package org.jetbrains.jet.lang.types.inference;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor;
import org.jetbrains.jet.lang.descriptors.TypeParameterDescriptor;
import org.jetbrains.jet.lang.types.*;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * @author abreslav
 */
public class ConstraintSystemImpl implements ConstraintSystem {

    public static TypeSubstitutor makeConstantSubstitutor(Collection<TypeParameterDescriptor> typeParameterDescriptors, JetType type) {
        final Set<TypeConstructor> constructors = Sets.newHashSet();
        for (TypeParameterDescriptor typeParameterDescriptor : typeParameterDescriptors) {
            constructors.add(typeParameterDescriptor.getTypeConstructor());
        }
        final TypeProjection projection = new TypeProjection(type);

        return TypeSubstitutor.create(new TypeSubstitutor.TypeSubstitution() {
            @Override
            public TypeProjection get(TypeConstructor key) {
                if (constructors.contains(key)) {
                    return projection;
                }
                return null;
            }

            @Override
            public boolean isEmpty() {
                return false;
            }
        });
    }


    private static class LoopInTypeVariableConstraintsException extends RuntimeException {
        public LoopInTypeVariableConstraintsException() {}
    }

    public static abstract class TypeValue {
        private final Set<TypeValue> upperBounds = Sets.newHashSet();
        private final Set<TypeValue> lowerBounds = Sets.newHashSet();

        @NotNull
        public Set<TypeValue> getUpperBounds() {
            return upperBounds;
        }

        @NotNull
        public Set<TypeValue> getLowerBounds() {
            return lowerBounds;
        }

        @Nullable
        public abstract KnownType getValue();
    }

    private static class UnknownType extends TypeValue {

        private final TypeParameterDescriptor typeParameterDescriptor;
        private final Variance positionVariance;
        private KnownType value;
        private boolean beingComputed = false;

        private UnknownType(TypeParameterDescriptor typeParameterDescriptor, Variance positionVariance) {
            this.typeParameterDescriptor = typeParameterDescriptor;
            this.positionVariance = positionVariance;
        }

        @NotNull
        public TypeParameterDescriptor getTypeParameterDescriptor() {
            return typeParameterDescriptor;
        }

        @Override
        public KnownType getValue() {
            if (beingComputed) {
                throw new LoopInTypeVariableConstraintsException();
            }
            if (value == null) {
                JetTypeChecker typeChecker = JetTypeChecker.INSTANCE;
                beingComputed = true;
                try {
                    if (positionVariance == Variance.IN_VARIANCE) {
                        // maximal solution
                        throw new UnsupportedOperationException();
                    }
                    else {
                        // minimal solution

                        Set<TypeValue> lowerBounds = getLowerBounds();
                        if (!lowerBounds.isEmpty()) {
                            Set<JetType> types = getTypes(lowerBounds);

                            JetType commonSupertype = CommonSupertypes.commonSupertype(types);
                            for (TypeValue upperBound : getUpperBounds()) {
                                if (!typeChecker.isSubtypeOf(commonSupertype, upperBound.getValue().getType())) {
                                    value = null;
                                }
                            }

                            println("minimal solution from lowerbounds for " + this + " is " + commonSupertype);
                            value = new KnownType(commonSupertype);
                        }
                        else {
                            Set<TypeValue> upperBounds = getUpperBounds();
                            Set<JetType> types = getTypes(upperBounds);
                            JetType intersect = TypeUtils.intersect(typeChecker, types);

                            value = new KnownType(intersect);
                        }
                    }
                }
                finally {
                    beingComputed = false;
                }
            }

            return value;
        }

        public boolean setValue(@NotNull KnownType value) {
            if (this.value != null) {
                // If we have already assigned a value to this unknown,
                // it is a conflict to assign another one, unless this new one is equal to the previous
                return TypeUtils.equalTypes(this.value.getType(), value.getType());
            }
            this.value = value;
            return true;
        }

        private Set<JetType> getTypes(Set<TypeValue> lowerBounds) {
            Set<JetType> types = Sets.newHashSet();
            for (TypeValue lowerBound : lowerBounds) {
                types.add(lowerBound.getValue().getType());
            }
            return types;
        }

        @Override
        public String toString() {
            return "?" + typeParameterDescriptor;
        }

    }

    private static class KnownType extends TypeValue {

        private final JetType type;

        public KnownType(@NotNull JetType type) {
            this.type = type;
        }

        @NotNull
        public JetType getType() {
            return type;
        }

        @Override
        public KnownType getValue() {
            return this;
        }

        @Override
        public String toString() {
            return type.toString();
        }
    }

    private final Map<JetType, KnownType> knownTypes = Maps.newHashMap();
    private final Map<TypeParameterDescriptor, UnknownType> unknownTypes = Maps.newHashMap();
    private final JetTypeChecker typeChecker = JetTypeChecker.INSTANCE;

    private static final class TypeConstraintBuilderAdapter implements JetTypeChecker.TypingConstraintBuilder {
        private final JetTypeChecker.TypingConstraintBuilder delegate;

        private TypeConstraintBuilderAdapter(JetTypeChecker.TypingConstraintBuilder delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean assertEqualTypes(@NotNull JetType a, @NotNull JetType b) {
            boolean result = delegate.assertEqualTypes(a, b);
            if (!result) {
                println("-- Failed to equate " + a + " and " + b);
            }
            return result;
        }

        @Override
        public boolean assertSubtype(@NotNull JetType subtype, @NotNull JetType supertype) {
            boolean result = delegate.assertSubtype(subtype, supertype);
            if (!result) {
                println("-- " + subtype + " can't be a subtype of " + supertype);
            }
            return result;
        }

        @Override
        public boolean noCorrespondingSupertype(@NotNull JetType subtype, @NotNull JetType supertype) {
            boolean result = delegate.noCorrespondingSupertype(subtype, supertype);
            if (!result) {
                println("-- " + subtype + " has supertype corresponding to " + supertype);
            }
            return result;
        }
    }
    
    private final JetTypeChecker.TypeCheckingProcedure constraintExpander = new JetTypeChecker.TypeCheckingProcedure(new TypeConstraintBuilderAdapter(new JetTypeChecker.TypingConstraintBuilder() {
        @Override
        public boolean assertEqualTypes(@NotNull JetType a, @NotNull JetType b) {
            TypeValue aValue = getTypeValueFor(a);
            TypeValue bValue = getTypeValueFor(b);

            if (aValue instanceof UnknownType) {
                UnknownType aUnknown = (UnknownType) aValue;
                if (bValue instanceof UnknownType) {
                    UnknownType bUnknown = (UnknownType) bValue;
                    mergeUnknowns(aUnknown, bUnknown);
                }
                else {
                    if (!aUnknown.setValue((KnownType) bValue)) return false;
                }
            }
            else if (bValue instanceof UnknownType) {
                UnknownType bUnknown = (UnknownType) bValue;
                if (!bUnknown.setValue((KnownType) aValue)) return false;
            }
            else {
              return TypeUtils.equalTypes(a, b);
            }

            return true;
        }

        @Override
        public boolean assertSubtype(@NotNull JetType subtype, @NotNull JetType supertype) {
            TypeValue subtypeValue = getTypeValueFor(subtype);
            TypeValue supertypeValue = getTypeValueFor(supertype);

            if (someUnknown(subtypeValue, supertypeValue)) {
                addSubtypingConstraintOnTypeValues(subtypeValue, supertypeValue);
            }
            return true;
        }

        @Override
        public boolean noCorrespondingSupertype(@NotNull JetType subtype, @NotNull JetType supertype) {
            // If some of the types is an unknown, the constraint is already generated, and we should carry on
            // otherwise there can be no solution, and we should fail
            return someUnknown(getTypeValueFor(subtype), getTypeValueFor(supertype));
        }

        private boolean someUnknown(TypeValue subtypeValue, TypeValue supertypeValue) {
            return subtypeValue instanceof UnknownType || supertypeValue instanceof UnknownType;
        }

    }));

    public ConstraintSystemImpl() {}

    @NotNull
    private TypeValue getTypeValueFor(@NotNull JetType type) {
        DeclarationDescriptor declarationDescriptor = type.getConstructor().getDeclarationDescriptor();
        if (declarationDescriptor instanceof TypeParameterDescriptor) {
            TypeParameterDescriptor typeParameterDescriptor = (TypeParameterDescriptor) declarationDescriptor;
            UnknownType unknownType = unknownTypes.get(typeParameterDescriptor);
            if (unknownType != null) {
                return unknownType;
            }
        }

        KnownType typeValue = knownTypes.get(type);
        if (typeValue == null) {
            typeValue = new KnownType(type);
            knownTypes.put(type, typeValue);
        }
        return typeValue;
    }

    @Override
    public void registerTypeVariable(@NotNull TypeParameterDescriptor typeParameterDescriptor, @NotNull Variance positionVariance) {
        assert !unknownTypes.containsKey(typeParameterDescriptor);
        UnknownType typeValue = new UnknownType(typeParameterDescriptor, positionVariance);
        unknownTypes.put(typeParameterDescriptor, typeValue);
    }

    @NotNull
    private UnknownType getTypeVariable(TypeParameterDescriptor typeParameterDescriptor) {
        UnknownType unknownType = unknownTypes.get(typeParameterDescriptor);
        if (unknownType == null) {
            throw new IllegalArgumentException("This type parameter is not an unknown in this constraint system: " + typeParameterDescriptor);
        }
        return unknownType;
    }

    private void mergeUnknowns(@NotNull UnknownType a, @NotNull UnknownType b) {
        System.err.println("!!!mergeUnknowns() is not implemented!!!");
    }

    @Override
    public void addSubtypingConstraint(@NotNull JetType lower, @NotNull JetType upper) {
        TypeValue typeValueForLower = getTypeValueFor(lower);
        TypeValue typeValueForUpper = getTypeValueFor(upper);
        addSubtypingConstraintOnTypeValues(typeValueForLower, typeValueForUpper);
    }

    private void addSubtypingConstraintOnTypeValues(TypeValue typeValueForLower, TypeValue typeValueForUpper) {
        println(typeValueForLower + " :< " + typeValueForUpper);
        if (typeValueForLower != typeValueForUpper) {
            typeValueForLower.getUpperBounds().add(typeValueForUpper);
            typeValueForUpper.getLowerBounds().add(typeValueForLower);
        }
    }

    @Override
    @NotNull
    public ConstraintSystemSolution solve() {
        // Expand custom bounds, e.g. List<T> <: List<Int>
        for (Map.Entry<JetType, KnownType> entry : Sets.newHashSet(knownTypes.entrySet())) {
            JetType jetType = entry.getKey();
            KnownType typeValue = entry.getValue();

            for (TypeValue upperBound : typeValue.getUpperBounds()) {
                if (upperBound instanceof KnownType) {
                    KnownType knownBoundType = (KnownType) upperBound;
                    boolean ok = constraintExpander.run(jetType, knownBoundType.getType());
                    if (!ok) {
                        return new Solution().registerError("Mismatch while expanding constraints");
                    }
                }
            }

            // Lower bounds?

        }

        // Fill in upper bounds from type parameter bounds
        for (Map.Entry<TypeParameterDescriptor, UnknownType> entry : Sets.newHashSet(unknownTypes.entrySet())) {
            TypeParameterDescriptor typeParameterDescriptor = entry.getKey();
            UnknownType typeValue = entry.getValue();
            for (JetType upperBound : typeParameterDescriptor.getUpperBounds()) {
                addSubtypingConstraintOnTypeValues(typeValue, getTypeValueFor(upperBound));
            }
        }

        // effective bounds for each node
        Set<TypeValue> visited = Sets.newHashSet();
        for (KnownType knownType : knownTypes.values()) {
            transitiveClosure(knownType, visited);
        }
        for (UnknownType unknownType : unknownTypes.values()) {
            transitiveClosure(unknownType, visited);
        }

        // Find inconsistencies
        Solution solution = new Solution();

        for (UnknownType unknownType : unknownTypes.values()) {
            check(unknownType, solution);
        }
        for (KnownType knownType : knownTypes.values()) {
            check(knownType, solution);
        }

        // TODO : check that all bounds are respected by solutions:
        //  we have set some of them from equality constraints with known types
        //  and thus the bounds may be violated if some of the constraints conflict

        return solution;
    }

    private void check(TypeValue typeValue, Solution solution) {
        try {
            KnownType resultingValue = typeValue.getValue();
            JetType type = solution.getSubstitutor().substitute(resultingValue.getType(), Variance.INVARIANT); // TODO
            for (TypeValue upperBound : typeValue.getUpperBounds()) {
                JetType boundingType = solution.getSubstitutor().substitute(upperBound.getValue().getType(), Variance.INVARIANT);
                if (!typeChecker.isSubtypeOf(type, boundingType)) { // TODO
                    solution.registerError("Constraint violation: " + type + " is not a subtype of " + boundingType);
                    println("Constraint violation: " + type + " :< " + boundingType);
                }
            }
            for (TypeValue lowerBound : typeValue.getLowerBounds()) {
                JetType boundingType = solution.getSubstitutor().substitute(lowerBound.getValue().getType(), Variance.INVARIANT);
                if (!typeChecker.isSubtypeOf(boundingType, type)) {
                    solution.registerError("Constraint violation: " + boundingType + " is not a subtype of " + type);
                    println("Constraint violation: " + boundingType + " :< " + type);
                }
            }
        }
        catch (LoopInTypeVariableConstraintsException e) {
            println("-------------------------------------------------------------------");
            for (Map.Entry<TypeParameterDescriptor, UnknownType> entry : unknownTypes.entrySet()) {
                println("Unknown: " + entry.getKey());
                UnknownType unknownType = entry.getValue();
                println("Lower bounds: ");
                for (TypeValue lowerBound : unknownType.getLowerBounds()) {
                    println("  " + lowerBound);
                }
                println("Upper bounds: ");
                for (TypeValue lowerBound : unknownType.getUpperBounds()) {
                    println("  " + lowerBound);
                }
            }
            solution.registerError("[TODO] Loop in constraints");
            e.printStackTrace();
        }
    }

    private void transitiveClosure(TypeValue current, Set<TypeValue> visited) {
        if (!visited.add(current)) {
            return;
        }

        for (TypeValue upperBound : Sets.newHashSet(current.getUpperBounds())) {
            transitiveClosure(upperBound, visited);
            Set<TypeValue> upperBounds = upperBound.getUpperBounds();
            for (TypeValue transitiveBound : upperBounds) {
                addSubtypingConstraintOnTypeValues(current, transitiveBound);
            }
        }
    }

    private static class Error implements SolutionStatus {

        private final String message;

        private Error(String message) {
            this.message = message;
        }

        @Override
        public boolean isSuccessful() {
            return false;
        }

        @Override
        public String toString() {
            return message;
        }
    }

    public class Solution implements ConstraintSystemSolution {
        private final TypeSubstitutor typeSubstitutor = TypeSubstitutor.create(new TypeSubstitutor.TypeSubstitution() {
            @Override
            public TypeProjection get(TypeConstructor key) {
                DeclarationDescriptor declarationDescriptor = key.getDeclarationDescriptor();
                if (declarationDescriptor instanceof TypeParameterDescriptor) {
                    TypeParameterDescriptor descriptor = (TypeParameterDescriptor) declarationDescriptor;
                    if (!unknownTypes.containsKey(descriptor)) return null;
                    println(descriptor + " |-> " + getValue(descriptor));
                    return new TypeProjection(getValue(descriptor));
                }
                return null;
            }

            @Override
            public boolean isEmpty() {
                return false;
            }
        });

        private SolutionStatus status;

        public Solution() {
            this.status = SolutionStatus.SUCCESS;
        }

        private Solution registerError(String message) {
            status = new Error(message);
            return this;
        }

        @NotNull
        @Override
        public SolutionStatus getStatus() {
            return status;
        }

        @Override
        public JetType getValue(TypeParameterDescriptor typeParameterDescriptor) {
            KnownType value = getTypeVariable(typeParameterDescriptor).getValue();
            return value == null ? null : value.getType();
        }

        @NotNull
        @Override
        public TypeSubstitutor getSubstitutor() {
            return typeSubstitutor;
        }

    }

    private static void println(String message) {
//        System.out.println(message);
    }
}