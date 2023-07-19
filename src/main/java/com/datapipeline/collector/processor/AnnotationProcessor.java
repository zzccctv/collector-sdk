package com.datapipeline.collector.processor;

import com.datapipeline.collector.annotation.*;
import com.datapipeline.collector.util.*;
import com.google.auto.service.AutoService;
import com.sun.source.tree.Tree;

import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;

import javax.annotation.processing.*;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import java.util.*;

@SupportedAnnotationTypes(
        {
                "com.datapipeline.collector.annotation.MCounter",
                "com.datapipeline.collector.annotation.MHistogram",
                "org.springframework.beans.factory.annotation.Autowired"
        }
)
//@AutoService(Processor.class)
public class AnnotationProcessor extends AbstractProcessor {
    private JavacTrees javacTrees;
    private TreeMaker treeMaker;
    private Names names;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.javacTrees = JavacTrees.instance(processingEnv);
        this.treeMaker = TreeMaker.instance(((JavacProcessingEnvironment) processingEnv).getContext());
        this.names = Names.instance(((JavacProcessingEnvironment) processingEnv).getContext());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Set<String> injectBean = new HashSet<>();
        Map<String, String> map = new HashMap<>();
        java.util.List<? extends TypeElement> sortAnnotations = new ArrayList(annotations);
        sortAnnotations.sort((Comparator<TypeElement>) (o1, o2) -> {
            int v = Character.compare(o1.getSimpleName().toString().charAt(0), o2.getSimpleName().toString().charAt(0));
            if (v == 0) {
                v = Character.compare(o2.getSimpleName().toString().charAt(1), o1.getSimpleName().toString().charAt(1));
            }
            return v;
        });
        for (TypeElement annotation : sortAnnotations) {
            Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(annotation);
            String varName = "reporter";
            for (Element element : elements) {
                try {
                    if (element.getKind() == ElementKind.FIELD) {
                        VariableElement variableElement = (VariableElement) element;
                        varName = variableElement.getSimpleName().toString();
                        String className = variableElement.getEnclosingElement().toString();
                        map.put(className, varName);
                        if (annotation.getSimpleName().toString().equals("Autowired")) {
                            Element classElement = variableElement.getEnclosingElement();
                            for (Element enclosedElement : classElement.getEnclosedElements()) {
                                if ("com.datapipeline.collector.metric.MetricReporter".equals(enclosedElement.asType().toString())) {
                                    injectBean.add(className);
                                }
                            }
                        }
                    } else if (element.getKind() == ElementKind.METHOD) {
                        ExecutableElement methodElement = (ExecutableElement) element;
                        String className = methodElement.getEnclosingElement().toString();
                        map.putIfAbsent(className, varName);
                        Statement statement = new Statement();
                        if (annotation.getSimpleName().toString().equals("MCounter")) {
                            JCTree.JCMethodDecl methodDecl = javacTrees.getTree(methodElement);
                            List<JCTree.JCStatement> bodyStatements = methodDecl.body.getStatements();
                            MCounter mCounter = methodElement.getAnnotation(MCounter.class);
                            if (mCounter.tag().length() != 0 && mCounter.metric().length() != 0) {
                                statement.setType("MCounter");
                                statement.setVarName(map.get(className));
                                statement.setComponent(mCounter.tag());
                                statement.setKey(mCounter.metric());
                                statement.setUnit(mCounter.unit());
                                statement.setDescription(mCounter.description());
                                statement.setAttributes(mCounter.attribute());
                                JCTree.JCExpressionStatement counterStatement = recordCounter(statement);
                                TypeKind typeKind = methodElement.getReturnType().getKind();
                                List<JCTree.JCStatement> newStatements = bodyStatements.append(counterStatement);
                                if (typeKind != TypeKind.VOID) {
                                    newStatements = processReturn(bodyStatements, treeMaker, statement);
                                }
                                methodDecl.body = treeMaker.Block(0, newStatements);
                            }
                        } else if (annotation.getSimpleName().toString().equals("MHistogram")) {
                            JCTree.JCMethodDecl methodDecl = javacTrees.getTree(methodElement);
                            List<JCTree.JCStatement> bodyStatements = methodDecl.body.getStatements();
                            MHistogram mHistogram = methodElement.getAnnotation(MHistogram.class);
                            if (mHistogram.tag().length() != 0 && mHistogram.metric().length() != 0) {
                                statement.setType("MHistogram");
                                statement.setVarName(map.get(className));
                                statement.setComponent(mHistogram.tag());
                                statement.setKey(mHistogram.metric());
                                statement.setUnit(mHistogram.unit());
                                statement.setDescription(mHistogram.description());
                                statement.setAttributes(mHistogram.attribute());
                                JCTree.JCExpressionStatement histogram = recordHistogram(statement);
                                TypeKind typeKind = methodElement.getReturnType().getKind();
                                List<JCTree.JCStatement> newStatements = bodyStatements.prepend(JCTreeUtil.beginTime(treeMaker, names)).append(JCTreeUtil.durationTime(treeMaker, names)).append(histogram);
                                if (typeKind != TypeKind.VOID) {
                                    newStatements = processReturn(bodyStatements, treeMaker, statement).prepend(JCTreeUtil.beginTime(treeMaker, names));
                                }
                                methodDecl.body = treeMaker.Block(0, newStatements);
                            }
                        }
                        if (!injectBean.contains(className)) {
                            JCTreeUtil.injectBeanMetricReporter(treeMaker, names, element, javacTrees);
                            injectBean.add(className);
                        }

                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
        return true;
    }


    private JCTree.JCExpressionStatement recordHistogram(Statement statement) {
        JCTree.JCMethodInvocation invocation = treeMaker.Apply(
                com.sun.tools.javac.util.List.nil(),
                JCTreeUtil.makeTypeReference(treeMaker, names, statement.getVarName().concat(".recordHistogram")),
                com.sun.tools.javac.util.List.of(
                        treeMaker.Literal(statement.getComponent()), treeMaker.Literal(statement.getKey()), treeMaker.Ident(names.fromString("duration")), treeMaker.Literal(statement.getUnit()), treeMaker.Literal(statement.getDescription()), JCTreeUtil.newArray(treeMaker, names, statement.getAttributes())
                )
        );
        return treeMaker.Exec(invocation);
    }

    private JCTree.JCExpressionStatement recordCounter(Statement statement) {
        JCTree.JCMethodInvocation invocation = treeMaker.Apply(
                com.sun.tools.javac.util.List.nil(),
                JCTreeUtil.makeTypeReference(treeMaker, names, statement.getVarName().concat(".recordCounter")),
                com.sun.tools.javac.util.List.of(
                        treeMaker.Literal(statement.getComponent()), treeMaker.Literal(statement.getKey()), treeMaker.Literal(statement.getUnit()), treeMaker.Literal(statement.getDescription()), JCTreeUtil.newArray(treeMaker, names, statement.getAttributes())
                )
        );
        return treeMaker.Exec(invocation);
    }

    private List<JCTree.JCStatement> processReturn(List<JCTree.JCStatement> statements, TreeMaker treeMaker, Statement newStatement) {
        ListBuffer<JCTree.JCStatement> newStatements = new ListBuffer<>();
        for (JCTree.JCStatement statement : statements) {
            // 处理 return 语句
            if (statement.getKind() == Tree.Kind.RETURN) {
                // 在 return 语句前插入一行代码
                if ("MCounter".equals(newStatement.getType())) {
                    newStatements.append(recordCounter(newStatement));
                } else if ("MHistogram".equals(newStatement.getType())) {
                    newStatements.append(JCTreeUtil.durationTime(treeMaker, names)).append(recordHistogram(newStatement));
                }
            }
            // 递归处理块语句，如 if、try-catch、for、while、switch 等
            if (statement instanceof JCTree.JCBlock) {
                JCTree.JCBlock block = (JCTree.JCBlock) statement;
                newStatements.append(treeMaker.Block(0, processReturn(block.getStatements(), treeMaker, newStatement)));
            } else if (statement instanceof JCTree.JCIf) {
                JCTree.JCIf ifStatement = (JCTree.JCIf) statement;
                JCTree.JCStatement thenStatement = ifStatement.getThenStatement();
                JCTree.JCStatement elseStatement = ifStatement.getElseStatement();
                if (thenStatement != null) {
                    thenStatement = treeMaker.Block(0, processReturn(List.of(thenStatement), treeMaker, newStatement));
                }
                if (elseStatement != null) {
                    elseStatement = treeMaker.Block(0, processReturn(List.of(elseStatement), treeMaker, newStatement));
                }
                newStatements.append(treeMaker.If(ifStatement.getCondition(), thenStatement, elseStatement));
            } else if (statement instanceof JCTree.JCTry) {
                JCTree.JCTry tryStatement = (JCTree.JCTry) statement;
                List<JCTree.JCCatch> catches = tryStatement.getCatches();
                JCTree.JCBlock finallyBlock = tryStatement.getFinallyBlock();
                ListBuffer<JCTree.JCCatch> newCatches = new ListBuffer<>();
                for (JCTree.JCCatch catchClause : catches) {
                    JCTree.JCBlock catchBlock = treeMaker.Block(0, processReturn(catchClause.getBlock().getStatements(), treeMaker, newStatement));
                    newCatches.append(treeMaker.Catch(catchClause.getParameter(), catchBlock));
                }
                if (finallyBlock != null) {
                    finallyBlock = treeMaker.Block(0, processReturn(finallyBlock.getStatements(), treeMaker, newStatement));
                }
                newStatements.append(treeMaker.Try(
                        treeMaker.Block(0, processReturn(tryStatement.getBlock().getStatements(), treeMaker, newStatement)),
                        newCatches.toList(),
                        finallyBlock
                ));
            } else if (statement instanceof JCTree.JCForLoop) {
                JCTree.JCForLoop forLoop = (JCTree.JCForLoop) statement;
                newStatements.append(treeMaker.ForLoop(forLoop.getInitializer(), forLoop.getCondition(), forLoop.getUpdate(), treeMaker.Block(0, processReturn(List.of(forLoop.getStatement()), treeMaker, newStatement))));
            } else if (statement instanceof JCTree.JCWhileLoop) {
                JCTree.JCWhileLoop whileLoop = (JCTree.JCWhileLoop) statement;
                newStatements.append(treeMaker.WhileLoop(whileLoop.getCondition(), treeMaker.Block(0, processReturn(List.of(whileLoop.getStatement()), treeMaker, newStatement))));
            } else if (statement instanceof JCTree.JCDoWhileLoop) {
                JCTree.JCDoWhileLoop doWhileLoop = (JCTree.JCDoWhileLoop) statement;
                newStatements.append(treeMaker.DoLoop(treeMaker.Block(0, processReturn(List.of(doWhileLoop.getStatement()), treeMaker, newStatement)), doWhileLoop.getCondition()));
            } else if (statement instanceof JCTree.JCSwitch) {
                JCTree.JCSwitch switchStatement = (JCTree.JCSwitch) statement;
                ListBuffer<JCTree.JCCase> newCases = new ListBuffer<>();
                for (JCTree.JCCase caseClause : switchStatement.getCases()) {
                    JCTree.JCStatement caseBlock = treeMaker.Block(0, processReturn(caseClause.getStatements(), treeMaker, newStatement));
                    newCases.append(treeMaker.Case(caseClause.getExpression(), List.of(caseBlock)));
                }
                newStatements.append(treeMaker.Switch(switchStatement.getExpression(), newCases.toList()));
            } else {
                newStatements.append(statement);
            }
        }
        return newStatements.toList();
    }
}
