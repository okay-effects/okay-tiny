// javac: InABranchOfAChoice.java:13: error: @Direct: a $ in a branch of ?:
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InABranchOfAChoice {
  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x(boolean ask) {
    Integer now = ask ? $(get()) : 0;
    return pure(now);
  }
}
