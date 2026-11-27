// javac: InAWhile.java:13: error: @Direct: a $ in this statement is not rewritten
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InAWhile {
  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x() {
    while (true) {
      Integer now = $(get());
      if (now > 3) return pure(now);
    }
  }
}
