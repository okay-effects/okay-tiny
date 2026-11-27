package okay.tiny.direct;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBinary;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCBreak;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCConditional;
import com.sun.tools.javac.tree.JCTree.JCContinue;
import com.sun.tools.javac.tree.JCTree.JCEnhancedForLoop;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCIf;
import com.sun.tools.javac.tree.JCTree.JCLambda;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCPrimitiveTypeTree;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCSwitchExpression;
import com.sun.tools.javac.tree.JCTree.JCThrow;
import com.sun.tools.javac.tree.JCTree.JCTry;
import com.sun.tools.javac.tree.JCTree.JCTypeApply;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import java.util.ArrayList;
import javax.tools.Diagnostic;

/**
 * -Xplugin:OkayDirect. After a file is parsed and before its types are checked, the body of every method marked
 * {@link Direct} is rewritten: what follows a {@code $(program)} becomes the function given to that program's
 * flatMap. The whole body is put off (defer), so nothing of it is run when the program is made.
 *
 * <p>What it rewrites: {@code $} in a declaration, a statement, a return, the condition of an if; an if whose
 * branches have {@code $}; a for-each whose body has; a block; try-finally. Anywhere else a {@code $} is an error
 * said at its place — it is never left to fail when the program runs.
 */
public final class Plugin implements com.sun.source.util.Plugin {
  @Override public String getName() { return "OkayDirect"; }

  @Override public void init(JavacTask task, String... args) {
    Context context = ((BasicJavacTask) task).getContext();
    task.addTaskListener(new TaskListener() {
      @Override public void finished(TaskEvent event) {
        if (event.getKind() != TaskEvent.Kind.PARSE) return;
        JCCompilationUnit unit = (JCCompilationUnit) event.getCompilationUnit();
        Rewrite rewrite = new Rewrite(TreeMaker.instance(context), Names.instance(context), Trees.instance(task), unit);
        new TreeScanner() {
          @Override public void visitMethodDef(JCMethodDecl method) {
            JCAnnotation direct = method.body == null ? null : direct(method);
            if (direct != null) rewrite.method(method, direct);
            super.visitMethodDef(method);                 // and the methods of classes inside it
          }
        }.scan(unit);
      }
    });
  }

  private static JCAnnotation direct(JCMethodDecl method) {
    for (JCAnnotation annotation : method.mods.annotations) {
      String type = annotation.annotationType.toString();
      if (type.equals("Direct") || type.endsWith(".Direct")) return annotation;
    }
    return null;
  }

  /** A program asked with $, and the name its value has in what follows. */
  private record Bound(JCVariableDecl as, JCExpression program, int word, int paren) {}

  private static final class Rewrite {
    private final TreeMaker make;
    private final Names names;
    private final Trees trees;
    private final JCCompilationUnit unit;
    private int fresh;
    private JCMethodDecl method;               // the one being rewritten
    private Name row;                          // the variable that has its row for a type; none: the row is not said

    Rewrite(TreeMaker make, Names names, Trees trees, JCCompilationUnit unit) {
      this.make = make; this.names = names; this.trees = trees; this.unit = unit;
    }

    void method(JCMethodDecl method, JCAnnotation direct) {
      this.method = method;
      JCTypeApply gives = method.restype instanceof JCTypeApply said && said.arguments.size() == 2 ? said : null;
      row = gives == null ? null : names.fromString("$row");
      List<JCStatement> body = block(method.body.stats, false);
      if (row != null) {
        // The method's row, as the type of a variable every bind is given: so a bind is told its row and does not
        // infer it — and a program whose effect the row has not is refused at its own $, not at the method.
        make.at(method.body.pos);
        JCExpression rowOf = make.TypeApply(name("okay.tiny.direct.Do.Row"),
            List.of(new TreeCopier<Void>(make).copy(gives.arguments.get(1))));
        body = body.prepend(make.VarDef(make.Modifiers(Flags.FINAL), row, rowOf, make.Literal(TypeTag.BOT, null)));
      }
      // The whole body is put off. The types of that are said, as the method says them, where it says both: then
      // what javac refuses inside — an effect the row has not — it refuses at the $ that asked it, not here.
      List<JCExpression> types = method.restype instanceof JCTypeApply said && said.arguments.size() == 2
          ? new TreeCopier<Void>(make).copy(said.arguments) : List.nil();
      make.at(direct.pos);
      JCExpression whole = make.Apply(types, name("okay.tiny.Fx.defer"),
          List.of(lambda(direct.annotationType.pos, List.nil(), body)));
      method.body.stats = List.of(make.at(method.body.pos).Return(whole));
    }

    // Every tree made here that javac may meet as an argument — a call, a lambda with its types said or with no
    // parameter — is given a place in the source that no other such tree has: javac remembers arguments by place.
    // The places are those of what is taken out or is never an argument: the $ and its bracket, the word of the
    // statement (if, for, try), a block's braces, the annotation.

    // ---- statements: what follows a $ goes into the function of its flatMap

    /** The statements as the body of a function that gives a program. unit: one that ends with no return gives null. */
    private List<JCStatement> block(List<JCStatement> statements, boolean unit) {
      ListBuffer<JCStatement> out = new ListBuffer<>();
      for (List<JCStatement> at = statements; at.nonEmpty(); at = at.tail) {
        JCStatement statement = at.head;
        List<JCStatement> rest = at.tail;
        if (!asks(statement)) {
          out.add(statement);
          continue;
        }
        make.at(statement.pos);
        List<JCStatement> rewritten = statement(statement, rest, unit);
        if (rewritten == null) {                          // refused, and said: go on, to find the others
          out.add(statement);
          continue;
        }
        out.addAll(rewritten);
        return out.toList();
      }
      if (unit && completes(statements)) out.add(make.Return(pureNull()));
      if (!unit && (statements.isEmpty() || ends(statements.last()))) {
        refuse(statements.isEmpty() ? method : statements.last(),
            "the method ends here with no program: return one, as in return pure(value)");
        out.add(make.Return(pureNull()));              // so that javac says nothing more of it
      }
      return out.toList();
    }

    /** A statement that has a $, with what follows it; or null where it is refused. */
    private List<JCStatement> statement(JCStatement statement, List<JCStatement> rest, boolean unit) {
      if (statement instanceof JCVariableDecl declared) {
        Hoist hoist = new Hoist();
        JCExpression init = hoist.translate(declared.init);
        if (hoist.refused) return null;
        Bound last = hoist.found.get(hoist.found.size() - 1);
        ListBuffer<JCStatement> then = new ListBuffer<>();
        if (init instanceof JCIdent value && value.name == last.as.name) {     // T x = $(program): x is its value
          hoist.found.set(hoist.found.size() - 1,
              new Bound(parameter(declared, then), last.program, last.word, last.paren));
        } else {
          declared.init = init;
          then.add(declared);
        }
        then.addAll(block(rest, unit));
        return chain(hoist, then.toList());
      }
      if (statement instanceof JCExpressionStatement executed) {
        Hoist hoist = new Hoist();
        JCExpression expression = hoist.translate(executed.expr);
        if (hoist.refused) return null;
        ListBuffer<JCStatement> then = new ListBuffer<>();
        if (!(expression instanceof JCIdent)) {                                 // not $(program); alone
          executed.expr = expression;
          then.add(executed);
        }
        then.addAll(block(rest, unit));
        return chain(hoist, then.toList());
      }
      if (statement instanceof JCReturn returned) {
        Hoist hoist = new Hoist();
        returned.expr = hoist.translate(returned.expr);
        if (hoist.refused) return null;
        return chain(hoist, List.of(returned));
      }
      if (statement instanceof JCIf branch) {
        Hoist hoist = new Hoist();
        branch.cond = hoist.translate(branch.cond);
        if (hoist.refused) return null;
        List<JCStatement> yes = statements(branch.thenpart), no = statements(branch.elsepart);
        if (!asks(branch.thenpart) && (branch.elsepart == null || !asks(branch.elsepart))) {
          return chain(hoist, block(rest.prepend(branch), unit));              // only its condition asked
        }
        if (returns(branch.thenpart) || (branch.elsepart != null && returns(branch.elsepart))) {
          // a branch may end the method: what follows the if is a part of each branch
          List<JCStatement> again = new TreeCopier<Void>(make).copy(rest);
          branch.thenpart = make.at(branch.pos).Block(0, block(completes(yes) ? yes.appendList(again) : yes, unit));
          branch.elsepart = make.at(branch.pos).Block(0, block(completes(no) ? no.appendList(rest) : no, unit));
          return chain(hoist, List.of(branch));
        }
        branch.thenpart = make.at(branch.pos).Block(0, block(yes, true));
        branch.elsepart = make.at(branch.pos).Block(0, block(no, true));
        JCExpression either = call(branch.pos + 1, "okay.tiny.Fx.defer",
            lambda(branch.cond.pos, List.nil(), List.of(branch)));
        return chain(hoist, List.of(then(branch.pos, either, rest, unit)));
      }
      if (statement instanceof JCEnhancedForLoop loop) {
        if (leaves(loop.body)) return refuse(loop, "a return, break or continue in a for-each that has a $");
        Hoist hoist = new Hoist();
        loop.expr = hoist.translate(loop.expr);
        if (hoist.refused) return null;
        ListBuffer<JCStatement> body = new ListBuffer<>();
        JCVariableDecl item = parameter(loop.var, body);
        body.addAll(block(statements(loop.body), true));
        JCExpression each = call(loop.pos + 1, "okay.tiny.Std.forEach", loop.expr,
            lambda(loop.pos + 2, List.of(item), body.toList()));
        return chain(hoist, List.of(then(loop.pos, each, rest, unit)));
      }
      if (statement instanceof JCBlock inner) return block(inner.stats.appendList(rest), unit);
      if (statement instanceof JCTry attempt) {
        if (attempt.catchers.nonEmpty() || attempt.resources.nonEmpty() || attempt.finalizer == null) {
          return refuse(attempt, "a $ in a try with a catch or with resources: only try-finally is rewritten");
        }
        if (asks(attempt.finalizer)) return refuse(attempt.finalizer, "a $ in a finally: what closes asks nothing");
        if (returns(attempt.body)) return refuse(attempt, "a return in a try-finally that has a $");
        JCBlock tried = attempt.body;
        JCExpression part = call(tried.pos, "okay.tiny.Fx.defer",
            lambda(tried.endpos, List.nil(), block(tried.stats, true)));
        JCExpression closed = call(attempt.pos + 1, "okay.tiny.Fx.ensuring", part,
            make.at(attempt.pos + 2).Lambda(List.nil(), attempt.finalizer));
        return List.of(then(attempt.pos, closed, rest, unit));
      }
      return refuse(statement, "a $ in this statement is not rewritten: use a for-each, an if, or a method of its own");
    }

    /** return bind(first, ignored -> { what follows }); */
    private JCStatement then(int pos, JCExpression first, List<JCStatement> rest, boolean unit) {
      return make.at(pos).Return(bind(pos, pos, first, implicit(fresh()), block(rest, unit)));
    }

    /** The programs asked, each bound around those after it, and around the statements. */
    private List<JCStatement> chain(Hoist hoist, List<JCStatement> statements) {
      for (int i = hoist.found.size() - 1; i >= 0; i--) {
        Bound bound = hoist.found.get(i);
        statements = List.of(make.at(bound.word).Return(
            bind(bound.word, bound.paren, bound.program, bound.as, statements)));
      }
      return statements;
    }

    private JCExpression bind(int pos, int lambdaPos, JCExpression first, JCVariableDecl as, List<JCStatement> then) {
      JCExpression next = lambda(lambdaPos, List.of(as), then);
      return row == null ? call(pos, "okay.tiny.direct.Do.bind", first, next)
          : call(pos, "okay.tiny.direct.Do.bindIn", make.at(pos).Ident(row), first, next);
    }

    /**
     * The variable as the parameter of the function that takes a program's value. One declared with var is a
     * parameter with no type; with a class, one of that class — which tells javac the program's type; with a
     * primitive, one of its box, and the variable itself is declared first in the function.
     */
    private JCVariableDecl parameter(JCVariableDecl declared, ListBuffer<JCStatement> first) {
      if (declared.vartype == null || declared.declaredUsingVar()) return implicit(declared.name);
      if (declared.vartype instanceof JCPrimitiveTypeTree primitive) {
        Name boxed = fresh();
        declared.init = make.Ident(boxed);
        first.add(declared);
        return make.VarDef(make.Modifiers(Flags.PARAMETER), boxed, name("java.lang." + box(primitive.typetag)), null);
      }
      return make.VarDef(make.Modifiers(Flags.PARAMETER), declared.name, declared.vartype, null);
    }

    private JCVariableDecl implicit(Name name) {
      return make.VarDef(make.Modifiers(Flags.PARAMETER), name, null, null);
    }

    private static String box(TypeTag primitive) {
      return switch (primitive) {
        case INT -> "Integer";
        case CHAR -> "Character";
        case BOOLEAN -> "Boolean";
        case LONG -> "Long";
        case DOUBLE -> "Double";
        case FLOAT -> "Float";
        case SHORT -> "Short";
        case BYTE -> "Byte";
        default -> throw new IllegalArgumentException(primitive.toString());
      };
    }

    // ---- expressions: every $ of a statement is run first, in the order written, and then the statement

    private final class Hoist extends TreeTranslator {
      final ArrayList<Bound> found = new ArrayList<>();
      boolean refused;

      @Override public void visitApply(JCMethodInvocation call) {
        if (!isAsk(call)) {
          super.visitApply(call);
          return;
        }
        if (call.args.size() != 1) {
          refused = refuse(call, "$ takes one program") == null;
          result = call;
          return;
        }
        JCExpression program = translate(call.args.head);                      // a $ inside it is run before it
        Name value = fresh();
        found.add(new Bound(implicit(value), program, call.meth.pos, call.pos));
        result = make.at(call.pos).Ident(value);
      }

      @Override public void visitLambda(JCLambda lambda) {
        inside(lambda, "a lambda: it is not a step of this method");
      }

      @Override public void visitClassDef(JCClassDecl inner) { inside(inner, "a class inside the method"); }

      @Override public void visitSwitchExpression(JCSwitchExpression chosen) { inside(chosen, "a switch expression"); }

      @Override public void visitConditional(JCConditional chosen) {
        chosen.cond = translate(chosen.cond);
        if (asks(chosen.truepart) || asks(chosen.falsepart)) {
          refused = refuse(chosen, "a $ in a branch of ?: would be run whatever the condition; use an if") == null;
        }
        result = chosen;
      }

      @Override public void visitBinary(JCBinary binary) {
        if (binary.hasTag(JCTree.Tag.AND) || binary.hasTag(JCTree.Tag.OR)) {
          binary.lhs = translate(binary.lhs);
          if (asks(binary.rhs)) {
            refused = refuse(binary,
                "a $ on the right of && or || would be run whatever the left is; use an if") == null;
          }
          result = binary;
        } else {
          super.visitBinary(binary);
        }
      }

      private void inside(JCTree tree, String where) {
        if (asks(tree)) refused = refuse(tree, "a $ in " + where) == null;
        result = tree;
      }
    }

    // ---- what a tree has

    private static boolean isAsk(JCMethodInvocation call) {
      return call.meth instanceof JCIdent name ? name.name.contentEquals("$")
          : call.meth instanceof JCFieldAccess select && select.name.contentEquals("$");
    }

    /** Has a $ anywhere. */
    private static boolean asks(JCTree tree) {
      boolean[] found = {false};
      new TreeScanner() {
        @Override public void visitApply(JCMethodInvocation call) {
          if (isAsk(call)) found[0] = true;
          super.visitApply(call);
        }
      }.scan(tree);
      return found[0];
    }

    /** Has a return of the method itself. */
    private static boolean returns(JCTree tree) {
      boolean[] found = {false};
      new OfTheMethod() {
        @Override public void visitReturn(JCReturn returned) { found[0] = true; }
      }.scan(tree);
      return found[0];
    }

    /** Has a return, a break or a continue. */
    private static boolean leaves(JCTree tree) {
      boolean[] found = {false};
      new OfTheMethod() {
        @Override public void visitReturn(JCReturn returned) { found[0] = true; }
        @Override public void visitBreak(JCBreak left) { found[0] = true; }
        @Override public void visitContinue(JCContinue left) { found[0] = true; }
      }.scan(tree);
      return found[0];
    }

    /** What follows these statements can be reached: by what is written, as far as a return and a throw say. */
    private static boolean completes(List<JCStatement> statements) {
      return statements.isEmpty() || completes(statements.last());
    }

    private static boolean completes(JCStatement statement) {
      if (statement instanceof JCReturn || statement instanceof JCThrow) return false;
      if (statement instanceof JCBlock block) return completes(block.stats);
      if (statement instanceof JCIf branch) {
        return branch.elsepart == null || completes(branch.thenpart) || completes(branch.elsepart);
      }
      if (statement instanceof JCTry attempt) {
        boolean somewhere = completes(attempt.body);
        for (JCTree.JCCatch caught : attempt.catchers) somewhere |= completes(caught.body);
        return somewhere && (attempt.finalizer == null || completes(attempt.finalizer));
      }
      return true;
    }

    /** This statement, the last of the method, surely lets it go on past its end. */
    private static boolean ends(JCStatement statement) {
      if (statement instanceof JCVariableDecl || statement instanceof JCExpressionStatement
          || statement instanceof JCEnhancedForLoop) return true;
      if (statement instanceof JCBlock block) return block.stats.isEmpty() || ends(block.stats.last());
      if (statement instanceof JCIf branch) {
        return branch.elsepart == null || ends(branch.thenpart) || ends(branch.elsepart);
      }
      return false;
    }

    /** Not what is inside a lambda or a class: their returns are their own. */
    private static class OfTheMethod extends TreeScanner {
      @Override public void visitLambda(JCLambda lambda) {}
      @Override public void visitClassDef(JCClassDecl inner) {}
    }

    // ---- making trees

    private static List<JCStatement> statements(JCStatement statement) {
      return statement == null ? List.nil() : statement instanceof JCBlock block ? block.stats : List.of(statement);
    }

    private JCExpression lambda(int pos, List<JCVariableDecl> parameters, List<JCStatement> body) {
      return make.at(pos).Lambda(parameters, make.at(pos).Block(0, body));
    }

    private JCExpression pureNull() {
      return make.Apply(List.nil(), name("okay.tiny.Fx.pure"), List.of(make.Literal(TypeTag.BOT, null)));
    }

    private JCExpression call(int pos, String method, JCExpression... arguments) {
      make.at(pos);
      return make.Apply(List.nil(), name(method), List.from(arguments));
    }

    private JCExpression name(String dotted) {
      JCExpression name = null;
      for (String part : dotted.split("\\.")) {
        name = name == null ? make.Ident(names.fromString(part)) : make.Select(name, names.fromString(part));
      }
      return name;
    }

    private Name fresh() { return names.fromString("$" + ++fresh); }

    private List<JCStatement> refuse(JCTree at, String what) {
      trees.printMessage(Diagnostic.Kind.ERROR, "@Direct: " + what, at, unit);
      return null;
    }
  }
}
