/*
Copyright (C) 2015-2016 Federico Tomassetti
Copyright (C) 2017-2023 The JavaParser Team.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package net.neoforged.javadoctor.injector;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.quality.NotNull;
import com.github.javaparser.resolution.Context;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.SymbolResolver;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedAnnotationDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.LazyType;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.core.resolution.MethodUsageResolutionCapability;
import com.github.javaparser.symbolsolver.core.resolution.SymbolResolutionCapability;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserAnnotationDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserEnumDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserMethodDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserTypeAdapter;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserTypeParameter;
import com.github.javaparser.symbolsolver.logic.AbstractClassDeclaration;
import com.github.javaparser.symbolsolver.resolution.SymbolSolver;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.github.javaparser.resolution.Navigator.demandParentNode;

public class SymbolResolverWithRecordSupport implements SymbolResolver {

    private final TypeSolver typeSolver;
    private final JavaSymbolSolver wrapped;

    public SymbolResolverWithRecordSupport(@NotNull TypeSolver typeSolver) {
        this.typeSolver = typeSolver;

        final JavaParserFacade facade = JavaParserFacade.get(typeSolver);
        try {
            final Field field = facade.getClass().getDeclaredField("symbolResolver");
            field.setAccessible(true);
            field.set(facade, this);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        this.wrapped = new JavaSymbolSolver(typeSolver);
    }

    @Override
    public <T> T resolveDeclaration(Node node, Class<T> resultClass) {
        return wrapped.resolveDeclaration(node, resultClass);
    }

    @Override
    public <T> T toResolvedType(Type javaparserType, Class<T> resultClass) {
        return wrapped.toResolvedType(javaparserType, resultClass);
    }

    @Override
    public ResolvedType calculateType(Expression expression) {
        return wrapped.calculateType(expression);
    }

    @Override
    public ResolvedReferenceTypeDeclaration toTypeDeclaration(Node node) {
        if (node instanceof ClassOrInterfaceDeclaration) {
            final ClassOrInterfaceDeclaration cNode = (ClassOrInterfaceDeclaration) node;
            if (cNode.isInterface()) {
                return new JavaParserInterfaceDeclaration(cNode, typeSolver);
            }
            return new JavaParserClassDeclaration(cNode, typeSolver);
        } else if (node instanceof RecordDeclaration) {
            return new RecordClassDeclaration((RecordDeclaration) node, typeSolver);
        }
        if (node instanceof TypeParameter) {
            return new JavaParserTypeParameter((TypeParameter) node, typeSolver);
        }
        if (node instanceof EnumDeclaration) {
            return new JavaParserEnumDeclaration((EnumDeclaration) node, typeSolver);
        }
        if (node instanceof AnnotationDeclaration) {
            return new JavaParserAnnotationDeclaration((AnnotationDeclaration) node, typeSolver);
        }
        if (node instanceof EnumConstantDeclaration) {
            return new JavaParserEnumDeclaration((EnumDeclaration) demandParentNode(node), typeSolver);
        }
        throw new IllegalArgumentException("Cannot get a reference type declaration from " + node.getClass().getCanonicalName());
    }

    public static final class RecordClassDeclaration extends AbstractClassDeclaration
            implements MethodUsageResolutionCapability, SymbolResolutionCapability {

        private TypeSolver typeSolver;
        private RecordDeclaration wrappedNode;
        private JavaParserTypeAdapter<RecordDeclaration> javaParserTypeAdapter;

        private static final Class<?> RECORD_CLASS;

        static {
            Class<?> recClass = Object.class;
            try {
                recClass = Class.forName("java.lang.Record");
            } catch (Exception exception) {

            }
            RECORD_CLASS = recClass;
        }

        public RecordClassDeclaration(RecordDeclaration wrappedNode,
                                      TypeSolver typeSolver) {
            this.wrappedNode = wrappedNode;
            this.typeSolver = typeSolver;
            this.javaParserTypeAdapter = new JavaParserTypeAdapter<>(wrappedNode, typeSolver);
        }

        ///
        /// Public methods: from Object
        ///

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RecordClassDeclaration that = (RecordClassDeclaration) o;

            return wrappedNode.equals(that.wrappedNode);
        }

        @Override
        public int hashCode() {
            return wrappedNode.hashCode();
        }

        @Override
        public String toString() {
            return "RecordClassDeclaration{" +
                    "wrappedNode=" + wrappedNode +
                    '}';
        }

        ///
        /// Public methods: fields
        ///

        @Override
        public List<ResolvedFieldDeclaration> getAllFields() {
            List<ResolvedFieldDeclaration> fields = javaParserTypeAdapter.getFieldsForDeclaredVariables();

            getAncestors(true).stream().filter(ancestor -> ancestor.getTypeDeclaration().isPresent())
                    .forEach(ancestor -> ancestor.getTypeDeclaration().get().getAllFields()
                            .forEach(f -> {
                                fields.add(new ResolvedFieldDeclaration() {

                                    @Override
                                    public AccessSpecifier accessSpecifier() {
                                        return f.accessSpecifier();
                                    }

                                    @Override
                                    public String getName() {
                                        return f.getName();
                                    }

                                    @Override
                                    public ResolvedType getType() {
                                        return ancestor.useThisTypeParametersOnTheGivenType(f.getType());
                                    }

                                    @Override
                                    public boolean isStatic() {
                                        return f.isStatic();
                                    }

                                    @Override
                                    public boolean isVolatile() {
                                        return f.isVolatile();
                                    }

                                    @Override
                                    public ResolvedTypeDeclaration declaringType() {
                                        return f.declaringType();
                                    }

                                    @Override
                                    public Optional<Node> toAst() {
                                        return f.toAst();
                                    }
                                });
                            }));

            return fields;
        }

        ///
        /// Public methods
        ///

        public SymbolReference<ResolvedMethodDeclaration> solveMethod(String name, List<ResolvedType> parameterTypes) {
            Context ctx = getContext();
            return ctx.solveMethod(name, parameterTypes, false);
        }

        @Override
        public Optional<MethodUsage> solveMethodAsUsage(String name, List<ResolvedType> argumentTypes,
                                                        Context invocationContext, List<ResolvedType> typeParameters) {
            return getContext().solveMethodAsUsage(name, argumentTypes);
        }

        /**
         * This method is deprecated because the context is an implementation detail that should not be exposed.
         * Ideally this method should become private. For this reason all further usages of this method are discouraged.
         */
        @Deprecated
        public Context getContext() {
            return JavaParserFactory.getContext(wrappedNode, typeSolver);
        }

        public ResolvedType getUsage(Node node) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getName() {
            return wrappedNode.getName().getId();
        }

        @Override
        public Optional<ResolvedReferenceType> getSuperClass() {
            return Optional.of(record());
        }

        @Override
        public List<ResolvedReferenceType> getInterfaces() {
            List<ResolvedReferenceType> interfaces = new ArrayList<>();
            // TODO FIXME: Remove null check -- should be an empty list...
            if (wrappedNode.getImplementedTypes() != null) {
                for (ClassOrInterfaceType t : wrappedNode.getImplementedTypes()) {
                    interfaces.add(toReferenceType(t));
                }
            }
            return interfaces;
        }

        @Override
        public List<ResolvedConstructorDeclaration> getConstructors() {
            return Collections.emptyList();
        }

        @Override
        public boolean hasDirectlyAnnotation(String canonicalName) {
            return false;
        }

        /*
         * Returns a set of the declared annotation on this type
         */
        @Override
        public Set<ResolvedAnnotationDeclaration> getDeclaredAnnotations() {
            return javaParserTypeAdapter.getDeclaredAnnotations();
        }

        @Override
        public boolean isInterface() {
            return false;
        }

        @Override
        public String getPackageName() {
            return javaParserTypeAdapter.getPackageName();
        }

        @Override
        public String getClassName() {
            return javaParserTypeAdapter.getClassName();
        }

        @Override
        public String getQualifiedName() {
            return javaParserTypeAdapter.getQualifiedName();
        }

        @Override
        public boolean isAssignableBy(ResolvedReferenceTypeDeclaration other) {
            return javaParserTypeAdapter.isAssignableBy(other);
        }

        @Override
        public boolean isAssignableBy(ResolvedType type) {
            return javaParserTypeAdapter.isAssignableBy(type);
        }

        @Override
        public boolean canBeAssignedTo(ResolvedReferenceTypeDeclaration other) {
            // TODO consider generic types
            if (this.getQualifiedName().equals(other.getQualifiedName())) {
                return true;
            }

            Optional<ResolvedReferenceType> optionalSuperClass = getSuperClass();
            if (optionalSuperClass.isPresent()) {
                Optional<ResolvedReferenceTypeDeclaration> optionalSuperclassTypeDeclaration = optionalSuperClass.get().getTypeDeclaration();
                if (optionalSuperclassTypeDeclaration.isPresent()) {
                    ResolvedReferenceTypeDeclaration superclassTypeDeclaration = optionalSuperclassTypeDeclaration.get();
                    if (superclassTypeDeclaration != this && superclassTypeDeclaration.isClass()) {
                        if (superclassTypeDeclaration.asClass().canBeAssignedTo(other)) {
                            return true;
                        }
                    }
                }
            }

            // TODO FIXME: Remove null check -- should be an empty list...
            if (this.wrappedNode.getImplementedTypes() != null) {
                for (ClassOrInterfaceType type : wrappedNode.getImplementedTypes()) {
                    ResolvedReferenceTypeDeclaration ancestor = (ResolvedReferenceTypeDeclaration) new SymbolSolver(typeSolver).solveType(type);
                    if (ancestor.canBeAssignedTo(other)) {
                        return true;
                    }
                }
            }

            return false;
        }

        @Override
        public boolean isTypeParameter() {
            return false;
        }

        /**
         * Resolution should move out of declarations, so that they are pure declarations and the resolution should
         * work for JavaParser, Reflection and Javassist classes in the same way and not be specific to the three
         * implementations.
         */
        @Deprecated
        public SymbolReference<ResolvedTypeDeclaration> solveType(String name) {
            if (this.wrappedNode.getName().getId().equals(name)) {
                return SymbolReference.solved(this);
            }
            SymbolReference<ResolvedTypeDeclaration> ref = javaParserTypeAdapter.solveType(name);
            if (ref.isSolved()) {
                return ref;
            }

            String prefix = wrappedNode.getName().asString() + ".";
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                return new RecordClassDeclaration(this.wrappedNode, typeSolver).solveType(name.substring(prefix.length()));
            }

            return getContext().getParent()
                    .orElseThrow(() -> new RuntimeException("Parent context unexpectedly empty."))
                    .solveType(name);
        }

        @Override
        public SymbolReference<ResolvedMethodDeclaration> solveMethod(String name, List<ResolvedType> argumentsTypes,
                                                                      boolean staticOnly) {
            return getContext().solveMethod(name, argumentsTypes, staticOnly);
        }

        @Override
        public SymbolReference<? extends ResolvedValueDeclaration> solveSymbol(String name, TypeSolver typeSolver) {
            return getContext().solveSymbol(name);
        }

        @Override
        public List<ResolvedReferenceType> getAncestors(boolean acceptIncompleteList) {
            List<ResolvedReferenceType> ancestors = new ArrayList<>();

            // We want to avoid infinite recursion in case of Object having Object as ancestor
            if (this.isJavaLangObject()) {
                return ancestors;
            }

            Optional<String> qualifiedName = wrappedNode.getFullyQualifiedName();
            if (!qualifiedName.isPresent()) {
                return ancestors;
            }

            try {
                // If a superclass is found, add it as an ancestor
                Optional<ResolvedReferenceType> superClass = getSuperClass();
                if (superClass.isPresent()) {
                    if (isAncestor(superClass.get(), qualifiedName.get())) {
                        ancestors.add(superClass.get());
                    }
                }
            } catch (UnsolvedSymbolException e) {
                // in case we could not resolve the super class, we may still be able to resolve (some of) the
                // implemented interfaces and so we continue gracefully with an (incomplete) list of ancestors

                if (!acceptIncompleteList) {
                    // Only throw if an incomplete ancestor list is unacceptable.
                    throw e;
                }
            }

            for (ClassOrInterfaceType implemented : wrappedNode.getImplementedTypes()) {
                try {
                    // If an implemented interface is found, add it as an ancestor
                    ResolvedReferenceType rrt = toReferenceType(implemented);
                    if (isAncestor(rrt, qualifiedName.get())) {
                        ancestors.add(rrt);
                    }
                } catch (UnsolvedSymbolException e) {
                    // in case we could not resolve some implemented interface, we may still be able to resolve the
                    // extended class or (some of) the other implemented interfaces and so we continue gracefully
                    // with an (incomplete) list of ancestors

                    if (!acceptIncompleteList) {
                        // Only throw if an incomplete ancestor list is unacceptable.
                        throw e;
                    }
                }
            }

            return ancestors;
        }

        private boolean isAncestor(ResolvedReferenceType candidateAncestor, String ownQualifiedName) {
            Optional<ResolvedReferenceTypeDeclaration> resolvedReferenceTypeDeclaration = candidateAncestor.getTypeDeclaration();
            if (resolvedReferenceTypeDeclaration.isPresent()) {
                ResolvedTypeDeclaration rtd = resolvedReferenceTypeDeclaration.get().asType();
                // do not consider an inner or nested class as an ancestor
                return !rtd.hasInternalType(ownQualifiedName);
            }
            return false;
        }

        @Override
        public Set<ResolvedMethodDeclaration> getDeclaredMethods() {
            Set<ResolvedMethodDeclaration> methods = new HashSet<>();
            for (BodyDeclaration<?> member : wrappedNode.getMembers()) {
                if (member instanceof MethodDeclaration) {
                    methods.add(new JavaParserMethodDeclaration((MethodDeclaration) member, typeSolver));
                }
            }
            return methods;
        }

        @Override
        public List<ResolvedTypeParameterDeclaration> getTypeParameters() {
            return this.wrappedNode.getTypeParameters().stream().map(
                    (tp) -> new JavaParserTypeParameter(tp, typeSolver)
            ).collect(Collectors.toList());
        }

        /**
         * Returns the JavaParser node associated with this JavaParserClassDeclaration.
         *
         * @return A visitable JavaParser node wrapped by this object.
         */
        public RecordDeclaration getWrappedNode() {
            return wrappedNode;
        }

        @Override
        public AccessSpecifier accessSpecifier() {
            return wrappedNode.getAccessSpecifier();
        }

        @Override
        public Optional<Node> toAst() {
            return Optional.of(wrappedNode);
        }

        ///
        /// Protected methods
        ///

        @Override
        protected ResolvedReferenceType object() {
            ResolvedReferenceTypeDeclaration solvedJavaLangObject = typeSolver.getSolvedJavaLangObject();
            return new ReferenceTypeImpl(solvedJavaLangObject);
        }

        private ResolvedReferenceType record() {
            ResolvedReferenceTypeDeclaration solvedJavaLangObject = typeSolver.solveType(RECORD_CLASS.getCanonicalName());
            return new ReferenceTypeImpl(solvedJavaLangObject);
        }

        @Override
        public Set<ResolvedReferenceTypeDeclaration> internalTypes() {
            return javaParserTypeAdapter.internalTypes();
        }

        @Override
        public Optional<ResolvedReferenceTypeDeclaration> containerType() {
            return javaParserTypeAdapter.containerType();
        }

        ///
        /// Private methods
        ///

        private ResolvedReferenceType toReferenceType(ClassOrInterfaceType classOrInterfaceType) {
            String className = classOrInterfaceType.getName().getId();
            if (classOrInterfaceType.getScope().isPresent()) {
                // look for the qualified name (for example class of type Rectangle2D.Double)
                className = classOrInterfaceType.getScope().get() + "." + className;
            }
            SymbolReference<ResolvedTypeDeclaration> ref = solveType(className);

            // If unable to solve by the class name alone, attempt to qualify it.
            if (!ref.isSolved()) {
                Optional<ClassOrInterfaceType> localScope = classOrInterfaceType.getScope();
                if (localScope.isPresent()) {
                    String localName = localScope.get().getName().getId() + "." + classOrInterfaceType.getName().getId();
                    ref = solveType(localName);
                }
            }

            // If still unable to resolve, throw an exception.
            if (!ref.isSolved()) {
                throw new UnsolvedSymbolException(classOrInterfaceType.getName().getId());
            }

            if (!classOrInterfaceType.getTypeArguments().isPresent()) {
                return new ReferenceTypeImpl(ref.getCorrespondingDeclaration().asReferenceType());
            }

            List<ResolvedType> superClassTypeParameters = classOrInterfaceType.getTypeArguments().get()
                    .stream()
                    .map(ta -> new LazyType(v -> JavaParserFacade.get(typeSolver).convert(ta, ta)))
                    .collect(Collectors.toList());

            return new ReferenceTypeImpl(ref.getCorrespondingDeclaration().asReferenceType(), superClassTypeParameters);
        }
    }

}

