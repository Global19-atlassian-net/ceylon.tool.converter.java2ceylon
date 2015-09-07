package ceylon.tool.converter.java2ceylon;

import ceylon.tool.converter.java2ceylon.Java8Parser.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaToCeylonConverter extends Java8BaseVisitor<Void> {

    private boolean transformGetters;
    private boolean useVariable;
    private Writer writer;
    private Pattern GETTER_PATTERN = Pattern.compile("(get|is)([A-Z]\\w*)");
    private static final List<String> RESERVED_KEYWORDS = Arrays.asList(
            "assembly", "abstracts", "alias", "assert", "assign", "break", "case", "catch", "class",
            "continue", "dynamic", "else", "exists", "extends", "finally", "for", "function", "given", "if", "import",
            "in", "interface", "is", "module", "nonempty", "object", "of", "out", "outer", "package", "return",
            "satisfies", "super", "switch", "then", "this", "throw", "try", "value", "void", "while"
    );

    public JavaToCeylonConverter(Writer out, boolean transformGetters, boolean useVariable) {
        writer = out;
        this.transformGetters = transformGetters;
        this.useVariable = useVariable;
    }

    private void write(String str) {
        try {
            writer.write(str);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean hasModifier(List<? extends ParserRuleContext> modifiers, String modifier) {
        for (ParserRuleContext m : modifiers) {
            if (m.getText().equals(modifier)) {
                return true;
            }
        }

        return false;
    }

    private void addImport(Map<String, List<String>> importsByPackage, String pack, String type) {
        List<String> imports;

        if (importsByPackage.containsKey(pack)) {
            imports = importsByPackage.get(pack);
        } else {
            imports = new ArrayList<>();
            importsByPackage.put(pack, imports);
        }

        // import on demand wins over single imports
        if (type.equals("...") && !imports.isEmpty()) {
            imports.clear();
        }

        if (imports.size() == 1 && imports.get(0).equals("...")) {
            return; // No need to add a single import if there's already a wildcard
        }

        imports.add(type);
    }

    @Override
    public Void visitCompilationUnit(CompilationUnitContext ctx) {
        Map<String, List<String>> importsByPackage = new LinkedHashMap<>();

        for (ImportDeclarationContext decl : ctx.importDeclaration()) {
            if (decl.singleTypeImportDeclaration() != null) {
                TypeNameContext typeName = decl.singleTypeImportDeclaration().typeName();
                addImport(importsByPackage, typeName.packageOrTypeName().getText(), typeName.Identifier().getText());
            }
            if (decl.typeImportOnDemandDeclaration() != null) {
                String pkgName = decl.typeImportOnDemandDeclaration().packageOrTypeName().getText();
                addImport(importsByPackage, pkgName, "...");
            }
        }

        for (Map.Entry<String, List<String>> entry : importsByPackage.entrySet()) {
            write("import ");
            write(entry.getKey());
            write(" {\n");

            for (int i = 0; i < entry.getValue().size(); i++) {
                if (i > 0) {
                    write(",\n");
                }
                write(entry.getValue().get(i));
            }
            write("\n}\n");
        }

        return super.visitCompilationUnit(ctx);
    }

    @Override
    public Void visitNormalClassDeclaration(NormalClassDeclarationContext ctx) {
        if (hasModifier(ctx.classModifier(), "public")) {
            write("shared ");
        }
        if (hasModifier(ctx.classModifier(), "abstract")) {
            write("abstract ");
        }
        write("class ");
        write(ctx.Identifier().getText()); // TODO uppercase first letter
        if (ctx.typeParameters() != null) {
            visitTypeParameters(ctx.typeParameters());
        }
        boolean hasExplicitConstructor = false;
        for (ClassBodyDeclarationContext decl : ctx.classBody().classBodyDeclaration()) {
            if (decl.constructorDeclaration() != null) {
                hasExplicitConstructor = true;
                break;
            }
        }
        if (!hasExplicitConstructor) {
            write("()");
        }
        if (ctx.superclass() != null) {
            visitSuperclass(ctx.superclass());
        }
        if (ctx.superinterfaces() != null) {
            visitSuperinterfaces(ctx.superinterfaces());
        }
        visitClassBody(ctx.classBody());

        return null;
    }

    @Override
    public Void visitTypeParameters(TypeParametersContext ctx) {
        write("<");
        visitTypeParameterList(ctx.typeParameterList());
        write(">");
        return null;
    }

    @Override
    public Void visitTypeParameterList(TypeParameterListContext ctx) {
        boolean isFirst = true;

        for (TypeParameterContext param : ctx.typeParameter()) {
            if (!isFirst) {
                write(", ");
            }
            visitTypeParameter(param);
            isFirst = false;
        }
        return null;
    }

    @Override
    public Void visitTypeParameter(TypeParameterContext ctx) {
        write(ctx.Identifier().getText());
        if (ctx.typeBound() != null) {
            visitTypeBound(ctx.typeBound());
        }
        return null;
    }

    @Override
    public Void visitWildcard(WildcardContext ctx) {
        WildcardBoundsContext bounds = ctx.wildcardBounds();
        if (bounds != null) {
            if (bounds.getChild(0).getText().equals("extends")) {
                write("out ");
            } else {
                write("in ");
            }
            visitReferenceType((ReferenceTypeContext) bounds.getChild(1));
        }
        return null;
    }

    @Override
    public Void visitTypeBound(TypeBoundContext ctx) {
        return super.visitTypeBound(ctx);
    }

    @Override
    public Void visitSuperclass(SuperclassContext ctx) {
        write(" extends ");
        super.visitSuperclass(ctx);
        write("()");
        return null;
    }

    @Override
    public Void visitSuperinterfaces(SuperinterfacesContext ctx) {
        write(" satisfies ");
        return super.visitSuperinterfaces(ctx);
    }

    @Override
    public Void visitClassType(ClassTypeContext ctx) {
        if (ctx.getChild(0) instanceof ClassOrInterfaceTypeContext) {
            visitClassOrInterfaceType((ClassOrInterfaceTypeContext) ctx.getChild(0));
            write(".");
        }
        // TODO? annotations*
        write(ctx.Identifier().getText());
        if (ctx.typeArguments() != null) {
            visitTypeArguments(ctx.typeArguments());
        }
        return null;
    }

    @Override
    public Void visitInterfaceTypeList(InterfaceTypeListContext ctx) {
        boolean isFirst = true;
        for (InterfaceTypeContext type : ctx.interfaceType()) {
            if (!isFirst) {
                write(" & ");
            }
            visitInterfaceType(type);
            isFirst = false;
        }
        return null;
    }

    @Override
    public Void visitClassBody(ClassBodyContext ctx) {
        write(" {\n\n");
        Void result = super.visitClassBody(ctx);
        write("}\n");

        return result;
    }

    @Override
    public Void visitMethodDeclaration(MethodDeclarationContext ctx) {
        if (hasModifier(ctx.methodModifier(), "public")) {
            write("shared ");
        }
        if (hasModifier(ctx.methodModifier(), "@Override")) {
            write("actual ");
        }
        if (hasModifier(ctx.methodModifier(), "abstract")) {
            write("formal ");
        }

        visitMethodHeader(ctx.methodHeader());
        visitMethodBody(ctx.methodBody());
        write("\n");

        return null;
    }

    @Override
    public Void visitMethodHeader(MethodHeaderContext ctx) {
        if (ctx.result().getText().equals("void")) {
            write("void ");
        } else {
            visitUnannType(ctx.result().unannType());
            write(" ");
        }
        visitMethodDeclarator(ctx.methodDeclarator());
        return null;
    }

    @Override
    public Void visitMethodDeclarator(MethodDeclaratorContext ctx) {
        write(escapeIdentifier(ctx.Identifier().getText(), true));

        write("(");
        if (ctx.formalParameterList() != null) {
            visitFormalParameterList(ctx.formalParameterList());
        }
        write(")");
        return null;
    }

    @Override
    public Void visitMethodBody(MethodBodyContext ctx) {
        if (ctx.block() == null) {
            write(";\n");
        } else {
            write(" ");
            return super.visitMethodBody(ctx);
        }
        return null;
    }

    @Override
    public Void visitFormalParameterList(FormalParameterListContext ctx) {
        if (ctx.formalParameters() != null) {
            for (FormalParameterContext param : ctx.formalParameters().formalParameter()) {
                visitFormalParameter(param);
                write(", ");
            }
        }
        if (ctx.lastFormalParameter().formalParameter() != null) {
            visitFormalParameter(ctx.lastFormalParameter().formalParameter());
        }
        return null;
    }

    @Override
    public Void visitFormalParameter(FormalParameterContext param) {
        if (useVariable) {
            write("variable ");
        }
        visitUnannType(param.unannType());
        write(" ");
        write(escapeIdentifier(param.variableDeclaratorId().getText(), true));
        return null;
    }

    @Override
    public Void visitUnannPrimitiveType(UnannPrimitiveTypeContext ctx) {
        String type = ctx.getText();

        switch (type) {
            case "int":
            case "long":
            case "short":
                write("Integer");
                break;
            case "float":
            case "double":
                write("Float");
                break;
            case "boolean":
                write("Boolean");
                break;
            case "byte":
                write("Byte");
                break;
            case "char":
                write("Character");
            default:
                write(type);
        }

        return null;
    }

    @Override
    public Void visitUnannTypeVariable(UnannTypeVariableContext ctx) {
        write(ctx.Identifier().getText());
        return null;
    }

    @Override
    public Void visitUnannClassType_lfno_unannClassOrInterfaceType(UnannClassType_lfno_unannClassOrInterfaceTypeContext ctx) {
        write(ctx.Identifier().getText());
        if (ctx.typeArguments() != null) {
            visitTypeArguments(ctx.typeArguments());
        }
        return null;
    }

    @Override
    public Void visitUnannClassType_lf_unannClassOrInterfaceType(UnannClassType_lf_unannClassOrInterfaceTypeContext ctx) {
        write(".");
        write(ctx.Identifier().getText());
        if (ctx.typeArguments() != null) {
            visitTypeArguments(ctx.typeArguments());
        }
        return null;
    }

    @Override
    public Void visitTypeArguments(TypeArgumentsContext ctx) {
        write("<");
        visitTypeArgumentList(ctx.typeArgumentList());
        write(">");
        return null;
    }

    @Override
    public Void visitTypeArgumentList(TypeArgumentListContext ctx) {
        boolean isFirst = true;

        for (TypeArgumentContext param : ctx.typeArgument()) {
            if (!isFirst) {
                write(", ");
            }
            visitTypeArgument(param);
            isFirst = false;
        }
        return null;
    }

    @Override
    public Void visitTypeVariable(TypeVariableContext ctx) {
        write(ctx.Identifier().getText());
        return null;
    }

    @Override
    public Void visitClassType_lfno_classOrInterfaceType(ClassType_lfno_classOrInterfaceTypeContext ctx) {
        write(ctx.Identifier().getText());
        if (ctx.typeArguments() != null) {
            visitTypeArguments(ctx.typeArguments());
        }
        return null;
    }

    @Override
    public Void visitClassType_lf_classOrInterfaceType(ClassType_lf_classOrInterfaceTypeContext ctx) {
        write(".");
        write(ctx.Identifier().getText());
        if (ctx.typeArguments() != null) {
            visitTypeArguments(ctx.typeArguments());
        }
        return null;
    }

    @Override
    public Void visitNormalInterfaceDeclaration(NormalInterfaceDeclarationContext ctx) {
        if (hasModifier(ctx.interfaceModifier(), "public")) {
            write("shared ");
        }

        write("interface ");
        write(ctx.Identifier().getText()); // TODO uppercase first letter
        if (ctx.typeParameters() != null) {
            visitTypeParameters(ctx.typeParameters());
        }
        if (ctx.extendsInterfaces() != null) {
            visitExtendsInterfaces(ctx.extendsInterfaces());
        }
        visitInterfaceBody(ctx.interfaceBody());

        return null;
    }

    @Override
    public Void visitInterfaceMethodDeclaration(InterfaceMethodDeclarationContext ctx) {
        if (hasModifier(ctx.interfaceMethodModifier(), "public")) {
            write("shared ");
        }
        if (hasModifier(ctx.interfaceMethodModifier(), "default")) {
            write("default ");
        } else {
            write("formal ");
        }

        visitMethodHeader(ctx.methodHeader());
        visitMethodBody(ctx.methodBody());
        return null;
    }

    @Override
    public Void visitInterfaceBody(InterfaceBodyContext ctx) {
        write(" {\n");
        for (InterfaceMemberDeclarationContext decl : ctx.interfaceMemberDeclaration()) {
            visitInterfaceMemberDeclaration(decl);
        }
        write("}\n");
        return null;
    }

    @Override
    public Void visitExtendsInterfaces(ExtendsInterfacesContext ctx) {
        write(" satisfies ");
        return super.visitExtendsInterfaces(ctx);
    }

    @Override
    public Void visitConstructorDeclaration(ConstructorDeclarationContext ctx) {
        if (hasModifier(ctx.constructorModifier(), "public")) {
            write("shared ");
        }
        visitConstructorDeclarator(ctx.constructorDeclarator());
        visitConstructorBody(ctx.constructorBody());
        return null;
    }

    @Override
    public Void visitConstructorDeclarator(ConstructorDeclaratorContext ctx) {
        write("new ");
        // TODO? name constructor
        write("(");
        if (ctx.formalParameterList() != null) {
            visitFormalParameterList(ctx.formalParameterList());
        }
        write(")");
        return null;
    }

    @Override
    public Void visitConstructorBody(ConstructorBodyContext ctx) {
        write(" {\n");
        super.visitConstructorBody(ctx);
        write("}\n\n");
        return null;
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationContext ctx) {
        String name = null;

        if (ctx.methodName() != null) {
            write(escapeIdentifier(ctx.methodName().getText(), true));
        } else if (ctx.typeName() != null) {
            String text = ctx.typeName().getText();
            if (text.equals("System.out") && ctx.Identifier().getText().equals("println")) {
                name = "System.out.println";
            } else {
                write(text);
                write(".");
            }
        } else if (ctx.expressionName() != null) {
            name = ctx.expressionName().getText() + "." + ctx.Identifier().getText();
        } else if (ctx.primary() != null) {
            visitPrimary(ctx.primary());
            write(".");
        } else if (ctx.typeName() != null) {
            visitTypeName(ctx.typeName());
            write(".super.");
        } else {
            write("super.");
        }

        if (name != null) {
            switch (name) {
                case "System.out.println":
                    name = "print";
                    break;
            }

            write(escapeIdentifier(name, true));
        } else {
            if (ctx.typeArguments() != null) {
                visitTypeArguments(ctx.typeArguments());
            }
            if (ctx.Identifier() != null) {
                Matcher matcher = GETTER_PATTERN.matcher(ctx.Identifier().getText());
                if (transformGetters && matcher.matches() && ctx.argumentList() == null) {
                    String property = matcher.group(2);
                    if (property.length() > 1) {
                        property = Character.toLowerCase(property.charAt(0)) + property.substring(1);
                    } else {
                        property = property.toLowerCase();
                    }
                    write(escapeIdentifier(property, true));
                    return null;
                } else {
                    write(escapeIdentifier(ctx.Identifier().getText(), true));
                }
            }
        }

        write("(");
        if (ctx.argumentList() != null) {
            visitArgumentList(ctx.argumentList());
        }
        write(")");

        return null;
    }

    @Override
    public Void visitMethodInvocation_lfno_primary(MethodInvocation_lfno_primaryContext ctx) {
        String prefix = "";
        String methodName = "";

        if (ctx.Identifier() != null) {
            methodName = ctx.Identifier().getText();
        }

        if (ctx.methodName() != null) {
            methodName = ctx.methodName().getText();
        } else if (ctx.typeName() != null) {
            prefix = ctx.typeName().getText() + ".";
            if (ctx.getChild(2).getText().equals("super")) {
                prefix += "super.";
            }
        } else if (ctx.expressionName() != null) {
            prefix = ctx.expressionName().getText();
        } else {
            prefix = "super.";
        }

        if ((prefix + methodName).equals("System.out.println")) {
            prefix = "";
            methodName = "print";
        }

        write(prefix);
        Matcher matcher = GETTER_PATTERN.matcher(methodName);
        if (transformGetters && matcher.matches() && ctx.argumentList() == null) {
            String property = matcher.group(2);
            if (property.length() > 1) {
                property = Character.toLowerCase(property.charAt(0)) + property.substring(1);
            } else {
                property = property.toLowerCase();
            }
            write(escapeIdentifier(property, true));
        } else {
            write(escapeIdentifier(methodName, true));
            write("(");
            if (ctx.argumentList() != null) {
                visitArgumentList(ctx.argumentList());
            }
            write(")");
        }

        return null;
    }

    @Override
    public Void visitMethodInvocation_lf_primary(MethodInvocation_lf_primaryContext ctx) {
        write(".");
        if (ctx.typeArguments() != null) {
            visitTypeArguments(ctx.typeArguments());
        }
        Matcher matcher = GETTER_PATTERN.matcher(ctx.Identifier().getText());
        if (transformGetters && matcher.matches() && ctx.argumentList() == null) {
            String property = matcher.group(2);
            if (property.length() > 1) {
                property = Character.toLowerCase(property.charAt(0)) + property.substring(1);
            } else {
                property = property.toLowerCase();
            }
            write(escapeIdentifier(property, true));
        } else {
            write(escapeIdentifier(ctx.Identifier().getText(), true));
            write("(");
            if (ctx.argumentList() != null) {
                visitArgumentList(ctx.argumentList());
            }
            write(")");
        }
        return null;
    }

    @Override
    public Void visitLiteral(LiteralContext ctx) {
        write(ctx.getText());
        return super.visitLiteral(ctx);
    }

    @Override
    public Void visitReturnStatement(ReturnStatementContext ctx) {
        write("return ");
        if (ctx.expression() != null) {
            visitExpression(ctx.expression());
        }
        write(";\n");
        return null;
    }

    @Override
    public Void visitExpressionStatement(ExpressionStatementContext ctx) {
        super.visitExpressionStatement(ctx);
        write(";\n");
        return null;
    }

    @Override
    public Void visitAssignment(AssignmentContext ctx) {
        ArrayAccessContext array = ctx.leftHandSide().arrayAccess();

        // Bypass array assignment to replace it with a.set(b, c)
        if (array != null) {
            if (array.expressionName() != null) {
                visitExpressionName(array.expressionName());
            } else {
                visitPrimaryNoNewArray_lfno_arrayAccess(array.primaryNoNewArray_lfno_arrayAccess());
            }
            write(".set(");
            visitExpression(array.expression().get(0));
            write(", ");
            visitExpression(ctx.expression());
            write(")");
        } else {
            super.visitAssignment(ctx);
        }
        return null;
    }

    @Override
    public Void visitArrayAccess_lfno_primary(ArrayAccess_lfno_primaryContext ctx) {
        // Bypass array access to replace it with a.get(b)
        if (ctx.expressionName() != null) {
            visitExpressionName(ctx.expressionName());
        } else {
            visitPrimaryNoNewArray_lfno_primary_lfno_arrayAccess_lfno_primary(
                    ctx.primaryNoNewArray_lfno_primary_lfno_arrayAccess_lfno_primary());
        }
        write(".get(");
        visitExpression(ctx.expression().get(0));
        write(")");
        return null;
    }

    @Override
    public Void visitAssignmentOperator(AssignmentOperatorContext ctx) {
        write(" ");
        write(ctx.getText());
        write(" ");
        return null;
    }

    @Override
    public Void visitUnannArrayType(UnannArrayTypeContext ctx) {
        if (ctx.unannPrimitiveType() != null) {
            String ceylonType;
            String type = ctx.unannPrimitiveType().getText();
            switch (type) {
                case "int":
                    ceylonType = "IntArray";
                    break;
                case "short":
                    ceylonType = "ShortArray";
                    break;
                case "boolean":
                    ceylonType = "BooleanArray";
                    break;
                case "byte":
                    ceylonType = "ByteArray";
                    break;
                case "long":
                    ceylonType = "LongArray";
                    break;
                case "float":
                    ceylonType = "FloatArray";
                    break;
                case "double":
                    ceylonType = "DoubleArray";
                    break;
                case "char":
                    ceylonType = "CharArray";
                    break;
                default:
                    ceylonType = "ObjectArray<" + type + ">";
                    break;
            }
            write(ceylonType);
        } else if (ctx.unannTypeVariable() != null) {
            write("ObjectArray<" + ctx.unannTypeVariable().Identifier().getText() + ">");
        } else {
            write("ObjectArray<");
            visitUnannClassOrInterfaceType(ctx.unannClassOrInterfaceType());
            write(">");
        }
        return null;
    }

    @Override
    public Void visitArrayCreationExpression(ArrayCreationExpressionContext ctx) {
        if (ctx.primitiveType() != null) {
            String ceylonType;
            String type = ctx.primitiveType().getText();
            switch (type) {
                case "int":
                    ceylonType = "IntArray";
                    break;
                case "short":
                    ceylonType = "ShortArray";
                    break;
                case "boolean":
                    ceylonType = "BooleanArray";
                    break;
                case "byte":
                    ceylonType = "ByteArray";
                    break;
                case "long":
                    ceylonType = "LongArray";
                    break;
                case "float":
                    ceylonType = "FloatArray";
                    break;
                case "double":
                    ceylonType = "DoubleArray";
                    break;
                case "char":
                    ceylonType = "CharArray";
                    break;
                default:
                    ceylonType = "ObjectArray<" + type + ">";
                    break;
            }
            write(ceylonType);
        } else {
            write("ObjectArray<");
            visitClassOrInterfaceType(ctx.classOrInterfaceType());
            write(">");
        }
        write("(");
        if (ctx.dimExprs() != null) {
            visitDimExprs(ctx.dimExprs());
        } else {
            visitDims(ctx.dims());
        }
        write(")");

        return null;
    }

    @Override
    public Void visitLocalVariableDeclarationStatement(LocalVariableDeclarationStatementContext ctx) {
        for (VariableDeclaratorContext var : ctx.localVariableDeclaration().variableDeclaratorList().variableDeclarator()) {
            boolean shouldUseAssert = var.variableInitializer() != null && isCastOutsideOfInstanceof(ctx.localVariableDeclaration(), var);

            if (shouldUseAssert) {
                write("assert(is ");
            } else if (useVariable) {
                write("variable ");
            }
            visitUnannType(ctx.localVariableDeclaration().unannType());
            write(" ");

            write(escapeIdentifier(var.variableDeclaratorId().getText(), true));
            
            if (var.variableInitializer() != null) {
                write(" = ");
                visitVariableInitializer(var.variableInitializer());
            }
            if (shouldUseAssert) {
                write(")");
            }
            write(";\n");
        }

        return null;
    }

    @Override
    public Void visitLocalVariableDeclaration(LocalVariableDeclarationContext ctx) {
        for (VariableDeclaratorContext var : ctx.variableDeclaratorList().variableDeclarator()) {
            if (useVariable) {
                write("variable ");
            }
            visitUnannType(ctx.unannType());
            write(" ");

            write(escapeIdentifier(var.variableDeclaratorId().getText(), true));

            
            if (var.variableInitializer() != null) {
                write(" = ");
                visitVariableInitializer(var.variableInitializer());
            }
            write(";\n");
        }

        return null;
    }

    @Override
    public Void visitClassInstanceCreationExpression_lfno_primary(ClassInstanceCreationExpression_lfno_primaryContext ctx) {
        boolean isObjectSatisfying = false;
        if (ctx.classBody() != null) {
            if (ctx.argumentList() == null) {
                isObjectSatisfying = true;
                write("object satisfies ");
            } else {
                write("object extends ");
            }
        }
        write(ctx.Identifier().get(0).getText());
        // TODO other identifiers
        if (ctx.typeArgumentsOrDiamond() != null) {
            visitTypeArgumentsOrDiamond(ctx.typeArgumentsOrDiamond());
        }
        if (!isObjectSatisfying) {
            write("(");
        }
        if (ctx.argumentList() != null) {
            visitArgumentList(ctx.argumentList());
        }
        if (!isObjectSatisfying) {
            write(")");
        }

        if (ctx.classBody() != null) {
            visitClassBody(ctx.classBody());
        }
        return null;
    }

    @Override
    public Void visitConditionalExpression(ConditionalExpressionContext ctx) {
        if (isTernaryOperator(ctx)) {
            // ternary operator
            write("if (");
            visitConditionalOrExpression(ctx.conditionalOrExpression());
            write(") then ");
            visitExpression(ctx.expression());
            write(" else ");
            visitConditionalExpression(ctx.conditionalExpression());
        } else {
            super.visitConditionalExpression(ctx);
        }

        return null;
    }

    @Override
    public Void visitExpressionName(ExpressionNameContext ctx) {
        write(escapeIdentifier(ctx.getText(), false));
        return null;
    }

    @Override
    public Void visitArgumentList(ArgumentListContext ctx) {
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (!(ctx.getChild(i) instanceof ExpressionContext)) {
                continue;
            }
            if (i > 0) {
                write(", ");
            }
            visitExpression((ExpressionContext) ctx.getChild(i));
        }
        return null;
    }

    @Override
    public Void visitIfThenStatement(IfThenStatementContext ctx) {
        write("if (");
        visitExpression(ctx.expression());
        write(") ");

        if (!isBlock(ctx.statement())) {
            write("{\n");
        }
        visitStatement(ctx.statement());
        if (!isBlock(ctx.statement())) {
            write("}\n");
        }
        return null;
    }

    @Override
    public Void visitIfThenElseStatement(IfThenElseStatementContext ctx) {
        write("if (");
        visitExpression(ctx.expression());
        write(") ");

        if (!isBlock(ctx.statementNoShortIf())) {
            write("{\n");
        }
        visitStatementNoShortIf(ctx.statementNoShortIf());
        if (!isBlock(ctx.statementNoShortIf())) {
            write("}\n");
        }

        write("else ");
        if (!isBlock(ctx.statement()) && !isIf(ctx.statement())) {
            write("{\n");
        }
        visitStatement(ctx.statement());
        if (!isBlock(ctx.statement()) && !isIf(ctx.statement())) {
            write("}\n");
        }
        return null;
    }

    @Override
    public Void visitBasicForStatement(BasicForStatementContext ctx) {
        // TODO can we detect for(i = 0; i < n; i++) and transform it to for (i in 0..n) ??
        if (ctx.forInit() != null) {
            visitForInit(ctx.forInit());
        }
        write("while(");
        if (ctx.expression() != null) {
            visitExpression(ctx.expression());
        } else {
            write("true");
        }
        write(") ");
        if (!isBlock(ctx.statement())) {
            write("{\n");
        }
        visitStatement(ctx.statement());
        if (ctx.forUpdate() != null) {
            visitForUpdate(ctx.forUpdate());
            write(";\n");
        }
        write("}\n");
        return null;
    }

    @Override
    public Void visitEnhancedForStatement(EnhancedForStatementContext ctx) {
        write("for (");
        visitUnannType(ctx.unannType());
        write(" ");
        write(ctx.variableDeclaratorId().getText());
        write(" in ");
        visitExpression(ctx.expression());
        write(") ");

        if (!isBlock(ctx.statement())) {
            write("{\n");
        }
        visitStatement(ctx.statement());
        if (!isBlock(ctx.statement())) {
            write("}\n");
        }
        return null;
    }

    @Override
    public Void visitSwitchStatement(SwitchStatementContext ctx) {
        write("switch (");
        visitExpression(ctx.expression());
        write(")\n");
        return visitSwitchBlock(ctx.switchBlock());
    }

    @Override
    public Void visitSwitchBlock(SwitchBlockContext ctx) {
        boolean hasElse = false;
        for (SwitchBlockStatementGroupContext group : ctx.switchBlockStatementGroup()) {
            // TODO transform `case a: case b:` to `case (a|b)`
            SwitchLabelContext label = group.switchLabels().switchLabel(0);
            if (label.getChild(0).getText().equals("case")) {
                write("case (");
                if (label.constantExpression() != null) {
                    visitConstantExpression(label.constantExpression());
                } else {
                    visitEnumConstantName(label.enumConstantName());
                }
                write(") {\n");
            } else {
                hasElse = true;
                write("else {\n");
            }
            visitBlockStatements(group.blockStatements());
            write("}\n");
        }

        if (!hasElse) {
            write("else {}\n");
        }
        // TODO is it really necessary to loop over switchLabel*, since those are "empty" cases?
        return null;
    }

    @Override
    public Void visitBlock(BlockContext ctx) {
        write("{\n");
        super.visitBlock(ctx);
        if (!isBlockInDoWhile(ctx)) {
            write("}\n");
        }
        return null;
    }

    @Override
    public Void visitDoStatement(DoStatementContext ctx) {
        write("while (true) ");
        if (!isBlock(ctx.statement())) {
            write("{\n");
        }
        visitStatement(ctx.statement());
        write("if (");
        visitExpression(ctx.expression());
        write(") {break;}\n");
        write("}\n");
        return null;
    }

    @Override
    public Void visitPrimaryNoNewArray(PrimaryNoNewArrayContext ctx) {
        switch (ctx.getChild(0).getText()) {
            case "this":
                write("this");
                break;
            case "(":
                write("(");
                visitExpression(ctx.expression());
                write(")");
                break;
            default:
                super.visitPrimaryNoNewArray(ctx);
        }
        return null;
    }

    @Override
    public Void visitPrimaryNoNewArray_lfno_primary(PrimaryNoNewArray_lfno_primaryContext ctx) {
        switch (ctx.getChild(0).getText()) {
            case "this":
                write("this");
                break;
            case "(":
                write("(");
                visitExpression(ctx.expression());
                write(")");
                break;
            default:
                super.visitPrimaryNoNewArray_lfno_primary(ctx);
        }
        return null;
    }

    @Override
    public Void visitConditionalOrExpression(ConditionalOrExpressionContext ctx) {
        if (ctx.conditionalOrExpression() != null) {
            visitConditionalOrExpression(ctx.conditionalOrExpression());
            write(" || ");
        }
        return visitConditionalAndExpression(ctx.conditionalAndExpression());
    }

    @Override
    public Void visitConditionalAndExpression(ConditionalAndExpressionContext ctx) {
        if (ctx.conditionalAndExpression() != null) {
            visitConditionalAndExpression(ctx.conditionalAndExpression());

            if (isInIfCondition(ctx)) {
                write(", ");
            } else {
                write(" && ");
            }
        }
        return visitInclusiveOrExpression(ctx.inclusiveOrExpression());
    }

    @Override
    public Void visitInclusiveOrExpression(InclusiveOrExpressionContext ctx) {
        if (ctx.inclusiveOrExpression() != null) {
            visitInclusiveOrExpression(ctx.inclusiveOrExpression());
            write(" | ");
        }
        return visitExclusiveOrExpression(ctx.exclusiveOrExpression());
    }

    @Override
    public Void visitExclusiveOrExpression(ExclusiveOrExpressionContext ctx) {
        if (ctx.exclusiveOrExpression() != null) {
            visitExclusiveOrExpression(ctx.exclusiveOrExpression());
            write(" ^ ");
        }
        return visitAndExpression(ctx.andExpression());
    }

    @Override
    public Void visitAndExpression(AndExpressionContext ctx) {
        if (ctx.andExpression() != null) {
            visitAndExpression(ctx.andExpression());
            write(" & ");
        }
        return visitEqualityExpression(ctx.equalityExpression());
    }

    @Override
    public Void visitEqualityExpression(EqualityExpressionContext ctx) {
        if (ctx.equalityExpression() != null) {
            String operator = ctx.getChild(1).getText();

            if (ctx.relationalExpression().getText().equals("null")) {
                if (operator.equals("==")) {
                    write("!");
                }
                write("exists ");
                // We need to assign things like `exists a.b.c`
                if (!ctx.equalityExpression().getText().matches("\\w+")) {
                    write(ctx.equalityExpression().getText().substring(0, 1).toLowerCase());
                    write(" = ");
                }
                visitEqualityExpression(ctx.equalityExpression());
                return null;
            } else {
                visitEqualityExpression(ctx.equalityExpression());
                write(" " + operator + " ");
            }
        }

        return visitRelationalExpression(ctx.relationalExpression());
    }

    @Override
    public Void visitRelationalExpression(RelationalExpressionContext ctx) {
        if (ctx.relationalExpression() != null) {
            if (ctx.getChild(1).getText().equals("instanceof") && isInIfCondition(ctx)) {
                write("is ");
                visitReferenceType(ctx.referenceType());
                write(" ");
                visitRelationalExpression(ctx.relationalExpression());
                return null;
            }

            visitRelationalExpression(ctx.relationalExpression());
            write(" ");
            write(ctx.getChild(1).getText().replace("instanceof", "is"));
            write(" ");
        }

        if (ctx.shiftExpression() != null) {
            visitShiftExpression(ctx.shiftExpression());
        } else {
            visitReferenceType(ctx.referenceType());
        }
        return null;
    }

    @Override
    public Void visitShiftExpression(ShiftExpressionContext ctx) {
        if (ctx.shiftExpression() != null) {
            visitShiftExpression(ctx.shiftExpression());
            write(" << ");
        }
        return visitAdditiveExpression(ctx.additiveExpression());
    }

    @Override
    public Void visitAdditiveExpression(AdditiveExpressionContext ctx) {
        if (ctx.additiveExpression() != null) {
            visitAdditiveExpression(ctx.additiveExpression());
            write(" " + ctx.getChild(1).getText() + " ");
        }
        return visitMultiplicativeExpression(ctx.multiplicativeExpression());
    }

    @Override
    public Void visitMultiplicativeExpression(MultiplicativeExpressionContext ctx) {
        if (ctx.multiplicativeExpression() != null) {
            visitMultiplicativeExpression(ctx.multiplicativeExpression());
            write(" " + ctx.getChild(1).getText() + " ");
        }
        return visitUnaryExpression(ctx.unaryExpression());
    }

    @Override
    public Void visitUnaryExpression(UnaryExpressionContext ctx) {
        String op = ctx.getChild(0).getText();
        if (op.equals("+") || op.equals("-")) {
            write(op);
            return visitUnaryExpression(ctx.unaryExpression());
        } else {
            return super.visitUnaryExpression(ctx);
        }
    }

    @Override
    public Void visitPreIncrementExpression(PreIncrementExpressionContext ctx) {
        write("++");
        return super.visitPreIncrementExpression(ctx);
    }

    @Override
    public Void visitPreDecrementExpression(PreDecrementExpressionContext ctx) {
        write("--");
        return super.visitPreDecrementExpression(ctx);
    }

    @Override
    public Void visitPostIncrementExpression(PostIncrementExpressionContext ctx) {
        super.visitPostIncrementExpression(ctx);
        write("++");
        return null;
    }

    @Override
    public Void visitPostIncrementExpression_lf_postfixExpression(PostIncrementExpression_lf_postfixExpressionContext ctx) {
        super.visitPostIncrementExpression_lf_postfixExpression(ctx);
        write("++");
        return null;
    }

    @Override
    public Void visitPostDecrementExpression(PostDecrementExpressionContext ctx) {
        super.visitPostDecrementExpression(ctx);
        write("--");
        return null;
    }

    @Override
    public Void visitPostDecrementExpression_lf_postfixExpression(PostDecrementExpression_lf_postfixExpressionContext ctx) {
        super.visitPostDecrementExpression_lf_postfixExpression(ctx);
        write("--");
        return null;
    }

    @Override
    public Void visitUnaryExpressionNotPlusMinus(UnaryExpressionNotPlusMinusContext ctx) {
        if (ctx.getChild(0).getText().equals("!")) {
            write("!");
        }
        return super.visitUnaryExpressionNotPlusMinus(ctx);
    }

    @Override
    public Void visitCastExpression(CastExpressionContext ctx) {
        if (ctx.unaryExpression() != null) {
            visitUnaryExpression(ctx.unaryExpression());
        }
        if (ctx.unaryExpressionNotPlusMinus() != null) {
            visitUnaryExpressionNotPlusMinus(ctx.unaryExpressionNotPlusMinus());
        }
        if (ctx.lambdaExpression() != null) {
            visitLambdaExpression(ctx.lambdaExpression());
        }
        return null;
    }

    @Override
    public Void visitEnumDeclaration(EnumDeclarationContext ctx) {
        if (hasModifier(ctx.classModifier(), "public")) {
            write("shared ");
        }
        write("class ");
        write(ctx.Identifier().getText());
        if (ctx.superinterfaces() != null) {
            visitSuperinterfaces(ctx.superinterfaces());
        }
        return visitEnumBody(ctx.enumBody());
    }

    @Override
    public Void visitEnumBody(EnumBodyContext ctx) {
        write(" {\n");
        write("shared actual String string;\n");
        if (ctx.enumBodyDeclarations() != null) {
            for (ClassBodyDeclarationContext classBody : ctx.enumBodyDeclarations().classBodyDeclaration()) {
                if (classBody.constructorDeclaration() != null) {
                    // Special case, we need to add an extra "String string" parameter
                    visitEnumConstructorDeclaration(classBody.constructorDeclaration());
                } else {
                    visitClassBodyDeclaration(classBody);
                }
            }
        }
        if (ctx.enumConstantList() != null) {
            visitEnumConstantList(ctx.enumConstantList());
        }
        write("}\n");
        return null;
    }

    private void visitEnumConstructorDeclaration(ConstructorDeclarationContext ctx) {
        write("abstract new _");
        write(ctx.constructorDeclarator().simpleTypeName().getText());
        write("(String string, ");
        if (ctx.constructorDeclarator().formalParameterList() != null) {
            visitFormalParameterList(ctx.constructorDeclarator().formalParameterList());
        }
        write(")");

        write(" {\n");
        write("this.string = string;\n");
        super.visitConstructorBody(ctx.constructorBody());
        write("}\n\n");
    }

    @Override
    public Void visitEnumConstant(EnumConstantContext ctx) {
        write("shared new _");
        write(ctx.Identifier().getText());
        if (ctx.argumentList() == null) {
            write(" { string = \"");
            write(ctx.Identifier().getText());
            write("\"; }\n");
        } else {
            write(" extends _");
            write(((EnumDeclarationContext) ctx.getParent().getParent().getParent()).Identifier().getText());
            write("(\"");
            write(ctx.Identifier().getText());
            write("\", ");
            visitArgumentList(ctx.argumentList());
            write(") { }\n");
        }
        return null;
    }

    @Override
    public Void visitFieldDeclaration(FieldDeclarationContext ctx) {
        for (VariableDeclaratorContext var : ctx.variableDeclaratorList().variableDeclarator()) {
            if (hasModifier(ctx.fieldModifier(), "public")) {
                write("shared ");
            }
            if (useVariable) {
                write("variable ");
            }
            visitUnannType(ctx.unannType());
            write(" ");

            write(escapeIdentifier(var.variableDeclaratorId().getText(), true));
            
            if (var.variableInitializer() != null) {
                write(" = ");
                visitVariableInitializer(var.variableInitializer());
            }
            write(";\n");
        }

        return null;
    }

    @Override
    public Void visitFieldAccess(FieldAccessContext ctx) {
        visitPrimary(ctx.primary());
        write(".");
        write(escapeIdentifier(ctx.Identifier().getText(), true));
        return null;
    }

    @Override
    public Void visitTryStatement(TryStatementContext ctx) {
        if (ctx.tryWithResourcesStatement() == null) {
            write("try ");
        }
        return super.visitTryStatement(ctx);
    }

    @Override
    public Void visitCatchClause(CatchClauseContext ctx) {
        write("catch (");
        visitCatchFormalParameter(ctx.catchFormalParameter());
        write(") ");
        return visitBlock(ctx.block());
    }

    @Override
    public Void visitTryWithResourcesStatement(TryWithResourcesStatementContext ctx) {
        write("try ");
        return super.visitTryWithResourcesStatement(ctx);
    }

    @Override
    public Void visitResourceSpecification(ResourceSpecificationContext ctx) {
        write("(");
        visitResourceList(ctx.resourceList());
        write(") ");
        return null;
    }

    @Override
    public Void visitUnannClassType(UnannClassTypeContext ctx) {
        if (ctx.unannClassOrInterfaceType() != null) {
            visitUnannClassOrInterfaceType(ctx.unannClassOrInterfaceType());
            write(".");
            write(ctx.Identifier().getText());
        } else {
            write(ctx.Identifier().getText());
        }
        if (ctx.typeArguments() != null) {
            visitTypeArguments(ctx.typeArguments());
        }
        return null;
    }

    @Override
    public Void visitResourceList(ResourceListContext ctx) {
        int i = 0;
        for (ResourceContext resource : ctx.resource()) {
            if (i > 0) {
                write("; ");
            }
            visitResource(resource);
            i++;
        }
        return null;
    }

    @Override
    public Void visitResource(ResourceContext ctx) {
        visitUnannType(ctx.unannType());
        write(" ");
        visitVariableDeclaratorId(ctx.variableDeclaratorId());
        write(" = ");
        return visitExpression(ctx.expression());
    }

    @Override
    public Void visitCatchType(CatchTypeContext ctx) {
        super.visitCatchType(ctx);
        write(" ");
        return null;
    }

    @Override
    public Void visitVariableDeclaratorId(VariableDeclaratorIdContext ctx) {
        write(escapeIdentifier(ctx.Identifier().getText(), true));
        return null;
    }

    @Override
    public Void visitFinally_(Finally_Context ctx) {
        write("finally ");
        return super.visitFinally_(ctx);
    }

    @Override
    public Void visitWhileStatement(WhileStatementContext ctx) {
        write("while (");
        visitExpression(ctx.expression());
        write(") ");
        if (ctx.statement().statementWithoutTrailingSubstatement() != null
                && ctx.statement().statementWithoutTrailingSubstatement().block() != null) {
            visitStatement(ctx.statement());
        } else {
            write("{\n");
            visitStatement(ctx.statement());
            write("}\n");
        }
        return null;
    }

    @Override
    public Void visitWhileStatementNoShortIf(WhileStatementNoShortIfContext ctx) {
        write("while (");
        visitExpression(ctx.expression());
        write(") ");
        if (ctx.statementNoShortIf().statementWithoutTrailingSubstatement() != null
                && ctx.statementNoShortIf().statementWithoutTrailingSubstatement().block() != null) {
            visitStatementNoShortIf(ctx.statementNoShortIf());
        } else {
            write("{\n");
            visitStatementNoShortIf(ctx.statementNoShortIf());
            write("}\n");
        }
        return null;
    }

    @Override
    public Void visitBreakStatement(BreakStatementContext ctx) {
        if (parent(ctx, 6) instanceof SwitchBlockContext) {
            return null;
        }
        write("break;\n");
        return null;
    }

    @Override
    public Void visitContinueStatement(ContinueStatementContext ctx) {
        write("continue;\n");
        return null;
    }

    private boolean isBlock(StatementContext ctx) {
        return ctx.statementWithoutTrailingSubstatement() != null
                && ctx.statementWithoutTrailingSubstatement().block() != null;
    }

    private boolean isIf(StatementContext ctx) {
        return ctx.ifThenStatement() != null || ctx.ifThenElseStatement() != null;
    }

    private boolean isBlock(StatementNoShortIfContext ctx) {
        return ctx.statementWithoutTrailingSubstatement() != null
                && ctx.statementWithoutTrailingSubstatement().block() != null;
    }

    private boolean isBlockInDoWhile(BlockContext block) {
        return block.getParent() instanceof StatementWithoutTrailingSubstatementContext
                && block.getParent().getParent() instanceof StatementContext
                && (block.getParent().getParent().getParent() instanceof DoStatementContext
                || block.getParent().getParent().getParent() instanceof BasicForStatementContext);
    }

    private boolean isInIfCondition(ConditionalAndExpressionContext ctx) {
        while (ctx.getParent() instanceof ConditionalAndExpressionContext) {
            ctx = (ConditionalAndExpressionContext) ctx.getParent();
        }
        return parent(ctx, 1) instanceof ConditionalOrExpressionContext
                && parent(ctx, 2) instanceof ConditionalExpressionContext
                && isInIfCondition((ConditionalExpressionContext) parent(ctx, 2));

    }

    private boolean isInIfCondition(ConditionalExpressionContext ctx) {
        if (parent(ctx, 1) instanceof AssignmentExpressionContext
                && parent(ctx, 2) instanceof ExpressionContext) {

            ParserRuleContext candidate = parent(ctx, 3);
            return candidate instanceof IfThenElseStatementContext
                    || candidate instanceof IfThenStatementContext
                    || candidate instanceof IfThenElseStatementNoShortIfContext;
        }
        return false;
    }

    private boolean isInIfCondition(RelationalExpressionContext ctx) {
        if (ctx.getParent() instanceof EqualityExpressionContext
                && parent(ctx, 2) instanceof AndExpressionContext
                && parent(ctx, 3) instanceof ExclusiveOrExpressionContext
                && parent(ctx, 4) instanceof InclusiveOrExpressionContext
                && parent(ctx, 5) instanceof ConditionalAndExpressionContext
                && parent(ctx, 6) instanceof ConditionalOrExpressionContext
                && parent(ctx, 7) instanceof ConditionalExpressionContext) {

            ConditionalExpressionContext condExpr = (ConditionalExpressionContext) parent(ctx, 7);

            if (isTernaryOperator(condExpr)) {
                return true;
            } else {
                return isInIfCondition(condExpr);
            }
        }
        return false;
    }

    private boolean isTernaryOperator(ConditionalExpressionContext ctx) {
        return ctx.getChildCount() > 1;
    }

    private ParserRuleContext parent(ParserRuleContext ctx, int level) {
        for (int i = 0; i < level; i++) {
            if (ctx != null) {
                ctx = ctx.getParent();
            }
        }

        return ctx;
    }

    private boolean isCastOutsideOfInstanceof(LocalVariableDeclarationContext ctx, VariableDeclaratorContext var) {
        // checks if this involves a cast
        if (getInnerChild(var.variableInitializer(),
                ExpressionContext.class,
                AssignmentExpressionContext.class,
                ConditionalExpressionContext.class,
                ConditionalOrExpressionContext.class,
                ConditionalAndExpressionContext.class,
                InclusiveOrExpressionContext.class,
                ExclusiveOrExpressionContext.class,
                AndExpressionContext.class,
                EqualityExpressionContext.class,
                RelationalExpressionContext.class,
                ShiftExpressionContext.class,
                AdditiveExpressionContext.class,
                MultiplicativeExpressionContext.class,
                UnaryExpressionContext.class,
                UnaryExpressionNotPlusMinusContext.class,
                CastExpressionContext.class) == null) {
            return false;
        }
        // checks if the variable declaration is located inside an if
        if (hasParents(ctx,
                LocalVariableDeclarationStatementContext.class,
                BlockStatementContext.class,
                BlockStatementsContext.class,
                BlockContext.class,
                StatementWithoutTrailingSubstatementContext.class)) {

            StatementWithoutTrailingSubstatementContext st = (StatementWithoutTrailingSubstatementContext) parent(ctx, 5);

            if (hasParents(st, StatementNoShortIfContext.class, IfThenElseStatementContext.class)) {
                // checks if the condition involves an instanceof
                return !isInstanceofCondition(((IfThenElseStatementContext) st.getParent().getParent()).expression(), var.variableDeclaratorId().getText());
            } else if (hasParents(st, StatementContext.class, IfThenStatementContext.class)) {
                // checks if the condition involves an instanceof
                return !isInstanceofCondition(((IfThenStatementContext) st.getParent().getParent()).expression(), ""/* TODO extract casted identifier */);
            }
        }
        return true;
    }

    private boolean isInstanceofCondition(ExpressionContext expr, String identifier) {
        RelationalExpressionContext child = (RelationalExpressionContext) getInnerChild(expr,
                AssignmentExpressionContext.class,
                ConditionalExpressionContext.class,
                ConditionalOrExpressionContext.class,
                ConditionalAndExpressionContext.class,
                InclusiveOrExpressionContext.class,
                ExclusiveOrExpressionContext.class,
                AndExpressionContext.class,
                EqualityExpressionContext.class,
                RelationalExpressionContext.class);

        if (child == null) {
            return false;
        }

        if (child.getChildCount() > 1 && child.getChild(1).getText().equals("instanceof")) {
            return true; // TODO compare identifiers
        }

        return false;
    }

    private boolean hasParents(ParserRuleContext ctx, Class<?>... parents) {
        if (parents != null) {
            ParserRuleContext parent = ctx;

            for (Class<?> p : parents) {
                parent = parent.getParent();
                if (!p.isAssignableFrom(parent.getClass())) {
                    return false;
                }
            }
        }

        return true;
    }

    private ParseTree getInnerChild(ParserRuleContext ctx, Class<?>... children) {
        if (children != null) {
            ParseTree rule = ctx;

            for (Class<?> p : children) {
                boolean foundChild = false;
                for (int i = 0; i < rule.getChildCount(); i++) {
                    ParseTree child = rule.getChild(i);

                    if (p.isAssignableFrom(child.getClass())) {
                        rule = child;
                        foundChild = true;
                        break;
                    }
                }

                if (!foundChild) {
                    return null;
                }
            }

            return rule;
        }

        return null;
    }

    private String escapeIdentifier(String identifier, boolean shouldBeLowercase) {

        if (RESERVED_KEYWORDS.contains(identifier)) {
            return "\\i" + identifier;
        } else if (shouldBeLowercase && !Character.isLowerCase(identifier.charAt(0))) {
            return "\\i" + identifier;
        }

        return identifier;
    }
}