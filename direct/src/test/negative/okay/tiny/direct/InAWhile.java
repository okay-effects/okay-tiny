// javac: InAWhile.java:16: error: @Direct: a $ in this statement is not rewritten
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InAWhile {
  static final Key<Integer> COUNT = key("count");
  static final Key<String> LOG = key("log");

  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x() {
    while (true) {
      Integer now = $(get(COUNT));
      if (now > 3) return pure(now);
    }
  }
}
