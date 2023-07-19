package com.datapipeline.collector.util;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;

import javax.lang.model.element.Element;

public class JCTreeUtil {

    public static JCTree.JCExpression makeTypeReference(TreeMaker treeMaker, Names names, String typeName) {
        String[] parts = typeName.split("\\.");
        JCTree.JCExpression expr = treeMaker.Ident(names.fromString(parts[0]));
        for (int i = 1; i < parts.length; i++) {
            expr = treeMaker.Select(expr, names.fromString(parts[i]));
        }
        return expr;
    }

    public static JCTree.JCVariableDecl durationTime(TreeMaker treeMaker, Names names) {
        return treeMaker.VarDef(treeMaker.Modifiers(0),
                names.fromString("duration"),
                treeMaker.TypeIdent(TypeTag.LONG),
                treeMaker.Binary(JCTree.Tag.MINUS, treeMaker.Apply(
                        List.nil(),
                        treeMaker.Select(
                                treeMaker.Ident(names.fromString("System")),
                                names.fromString("currentTimeMillis")
                        ),
                        List.nil()
                ), treeMaker.Ident(names.fromString("now"))));
    }

    public static JCTree.JCVariableDecl beginTime(TreeMaker treeMaker, Names names) {
        return treeMaker.VarDef(treeMaker.Modifiers(0),
                names.fromString("now"),
                treeMaker.TypeIdent(TypeTag.LONG),
                treeMaker.Apply(
                        List.nil(),
                        treeMaker.Select(
                                treeMaker.Ident(names.fromString("System")),
                                names.fromString("currentTimeMillis")
                        ),
                        List.nil()
                ));
    }

    public static JCTree.JCNewArray newArray(TreeMaker treeMaker, Names names, String[] attributes) {
        JCTree.JCExpression elementType = treeMaker.Ident(names.fromString("String"));
        JCTree.JCExpression[] attribute = new JCTree.JCExpression[attributes.length];
        for (int i = 0; i < attributes.length; i++) {
            attribute[i] = treeMaker.Literal(attributes[i]);
        }
        List<JCTree.JCExpression> elements = List.from(attribute);
        return treeMaker.NewArray(
                elementType,
                List.nil(),
                elements
        );
    }

    public static void injectBeanMetricReporter(TreeMaker treeMaker, Names names, Element element, JavacTrees javacTrees) {
        JCTree.JCClassDecl classDecl = (JCTree.JCClassDecl) javacTrees.getTree(element.getEnclosingElement());
        JCTree.JCVariableDecl variableDecl = treeMaker.VarDef(treeMaker.Modifiers(Flags.PRIVATE), names.fromString("reporter"), JCTreeUtil.makeTypeReference(treeMaker, names, "com.datapipeline.collector.metric.MetricReporter"), null);
        variableDecl.mods.annotations = variableDecl.mods.annotations.append(treeMaker.Annotation(JCTreeUtil.makeTypeReference(treeMaker, names, "org.springframework.beans.factory.annotation.Autowired"), List.nil()));
        classDecl.defs = classDecl.getMembers().appendList(com.sun.tools.javac.util.List.of(variableDecl));
    }

    public static int returnIndex(List<JCTree.JCStatement> statements) {
        int returnIndex = -1;
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).getKind() == Tree.Kind.RETURN) {
                returnIndex = i;
                break;
            }
        }
        return returnIndex;
    }
}
