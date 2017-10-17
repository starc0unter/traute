package tech.harmonysoft.oss.traute.javac;

import com.sun.source.tree.*;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.tools.JavaCompiler;
import java.util.*;
import java.util.function.Consumer;

import static java.util.Arrays.asList;

/**
 * Inspects {@code AST} built by {@link JavaCompiler}, finds places where to apply {@code null}-checks
 * and notifies given instrumentators about them.
 */
public class AstVisitor extends TreeScanner<Void, Void> {

    private static final Set<String> PRIMITIVE_TYPES = new HashSet<>(asList(
            "byte", "short", "char", "int", "long", "float", "double"
    ));

    private Stack<StatementTree> parents = new Stack<>();

    @NotNull private final CompilationUnitProcessingContext    context;
    @NotNull private final Consumer<ParameterToInstrumentInfo> parameterInstrumenter;
    @NotNull private final Consumer<ReturnToInstrumentInfo>    returnInstrumenter;

    private JCTree.JCExpression methodReturnType;
    private String              methodNotNullAnnotation;
    private int                 tmpVariableCounter;

    public AstVisitor(@NotNull CompilationUnitProcessingContext context,
                      @NotNull Consumer<ParameterToInstrumentInfo> parameterInstrumenter,
                      @NotNull Consumer<ReturnToInstrumentInfo> returnInstrumenter)
    {
        this.context = context;
        this.parameterInstrumenter = parameterInstrumenter;
        this.returnInstrumenter = returnInstrumenter;
    }

    @Override
    public Void visitImport(ImportTree node, Void v) {
        if (!node.isStatic()) {
            context.addImport(node.getQualifiedIdentifier().toString());
        }
        return v;
    }

    @Override
    public Void visitMethod(MethodTree method, Void v) {
        boolean instrumentReturnType = mayBeInstrumentReturnType(method);
        BlockTree bodyBlock = method.getBody();
        if (!(bodyBlock instanceof JCTree.JCBlock)) {
            context.getProblemReporter().reportDetails(String.format(
                    "get a %s instance in the method AST but got %s",
                    JCTree.JCBlock.class.getName(), bodyBlock.getClass().getName()
            ));
            return v;
        }
        JCTree.JCBlock jcBlock = (JCTree.JCBlock) bodyBlock;
        SortedSet<ParameterToInstrumentInfo> variablesToCheck = new TreeSet<>(
                // There is a possible case that more than one method parameter is marked by a NotNull annotation.
                // We want to add null-checks in reverse order then, i.e. for the last parameter marked
                // by a NotNull, then for the previous before the last etc
                (o1, o2) -> o2.getMethodParameterIndex() - o1.getMethodParameterIndex()
        );
        int parameterIndex = 0;
        int parametersNumber = method.getParameters().size();
        for (VariableTree variable : method.getParameters()) {
            if (variable == null) {
                continue;
            }
            Tree type = variable.getType();
            if (type != null && PRIMITIVE_TYPES.contains(type.toString())) {
                continue;
            }
            Optional<String> annotation = findNotNullAnnotation(variable.getModifiers());
            if (annotation.isPresent()) {
                variablesToCheck.add(new ParameterToInstrumentInfo(context,
                                                                   annotation.get(),
                                                                   variable,
                                                                   jcBlock,
                                                                   parameterIndex,
                                                                   parametersNumber));
            }
            parameterIndex++;
        }

        for (ParameterToInstrumentInfo info : variablesToCheck) {
            mayBeSetPosition(info.getMethodParameter(), context.getAstFactory());
            parameterInstrumenter.accept(info);
        }
        if (instrumentReturnType) {
            try {
                return super.visitMethod(method, v);
            } finally {
                methodReturnType = null;
                methodNotNullAnnotation = null;
                tmpVariableCounter = 1;
                parents.clear();
            }
        } else {
            return v;
        }
    }

    private boolean mayBeInstrumentReturnType(@NotNull MethodTree method) {
        Tree returnType = method.getReturnType();
        if (PRIMITIVE_TYPES.contains(returnType.toString()) || (!(returnType instanceof JCTree.JCExpression))) {
            return false;
        }

        Optional<String> notNullAnnotation = findNotNullAnnotation(method.getModifiers());
        if (notNullAnnotation.isPresent()) {
            methodNotNullAnnotation = notNullAnnotation.get();
            methodReturnType = (JCTree.JCExpression) returnType;
            return true;
        }
        return false;
    }

    @NotNull
    private String getTmpVariableName() {
        return "tmpTrauteVar" + ++tmpVariableCounter;
    }

    private void mayBeSetPosition(@NotNull Tree astNode, @NotNull TreeMaker astFactory) {
        if (astNode instanceof JCTree) {
            // Mark our AST factory with the given AST node's offset in order to see corresponding
            // line in the stack trace when an NPE is thrown.
            astFactory.at(((JCTree) astNode).pos);
        }
    }

    /**
     * Checks if given {@code AST} element's modifiers contain any of the
     * {@link CompilationUnitProcessingContext#getNotNullAnnotations() target} {@code @NotNull} annotation.
     *
     * @param modifiers {@code AST} element's modifiers to check
     * @return          target annotation's name in case the one is found
     */
    @NotNull
    private Optional<String> findNotNullAnnotation(@Nullable ModifiersTree modifiers) {
        if (modifiers == null) {
            return Optional.empty();
        }
        java.util.List<? extends AnnotationTree> annotations = modifiers.getAnnotations();
        if (annotations == null) {
            return Optional.empty();
        }
        Set<String> annotationsInSource = new HashSet<>();
        for (AnnotationTree annotation : annotations) {
            Tree type = annotation.getAnnotationType();
            if (type != null) {
                annotationsInSource.add(type.toString());
            }
        }
        return findMatch(annotationsInSource);
    }

    /**
     * <p>
     *     Checks if any of the given 'annotations to check' matches any of the
     *     {@link CompilationUnitProcessingContext#getNotNullAnnotations() target annotations}
     *     considering {@link CompilationUnitProcessingContext#getImports() available imports}.
     * </p>
     * <p>
     *     Example:
     *     <ul>
     *       <li>annotations to check: [ {@code NotNull} ]</li>
     *       <li>imports: [ {@code org.jetbrains.annotations.NotNull} ]</li>
     *       <li>target annotations: [ {@code org.jetbrains.annotations.NotNull} ]</li>
     *     </ul>
     *     We expect to find a match for the {@code org.jetbrains.annotations.NotNull} then.
     * </p>
     *
     * @param annotationsToCheck    annotations to match against the given 'target annotations'
     * @return                      a matched annotation (if any)
     */
    @NotNull
    private Optional<String> findMatch(@NotNull Collection<String> annotationsToCheck) {
        for (String annotationInSource : annotationsToCheck) {
            Set<String> notNullAnnotations = context.getNotNullAnnotations();
            if (notNullAnnotations.contains(annotationInSource)) {
                // Qualified annotation, like 'void test(@javax.annotation.Nonnul String s) {}'
                return Optional.of(annotationInSource);
            }
            for (String anImport : context.getImports()) {
                // Support an import like 'import org.jetbrains.annotations.*;'
                if (anImport.endsWith(".*")) {
                    String candidate = anImport.substring(0, anImport.length() - 1) + annotationInSource;
                    if (notNullAnnotations.contains(candidate)) {
                        return Optional.of(candidate);
                    }
                    continue;
                }
                if (!notNullAnnotations.contains(anImport)) {
                    continue;
                }
                if (anImport.endsWith(annotationInSource)) {
                    return Optional.of(anImport);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Void visitBlock(BlockTree node, Void aVoid) {
        parents.push(node);
        try {
            return super.visitBlock(node, aVoid);
        } finally {
            parents.pop();
        }
    }

    @Override
    public Void visitIf(IfTree node, Void aVoid) {
        parents.push(node);
        try {
            return super.visitIf(node, aVoid);
        } finally {
            parents.pop();
        }
    }

    @Override
    public Void visitReturn(ReturnTree node, Void aVoid) {
        if (methodNotNullAnnotation != null && methodReturnType != null && !parents.isEmpty()) {
            mayBeSetPosition(node, context.getAstFactory());
            returnInstrumenter.accept(new ReturnToInstrumentInfo(context,
                                                                 methodNotNullAnnotation,
                                                                 node,
                                                                 methodReturnType,
                                                                 getTmpVariableName(),
                                                                 parents.peek()));
        }
        return super.visitReturn(node, aVoid);
    }
}
