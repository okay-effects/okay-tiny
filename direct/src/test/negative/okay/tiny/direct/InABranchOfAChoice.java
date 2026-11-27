// javac: InABranchOfAChoice.java:16: error: @Direct: a $ in a branch of ?:
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InABranchOfAChoice {
  static final Key<Integer> COUNT = key("count");
  static final Key<String> LOG = key("log");

  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x(boolean ask) {
    Integer now = ask ? $(get(COUNT)) : 0;
    return pure(now);
  }
}
