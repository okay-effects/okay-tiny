// javac: InAFinally.java:18: error: @Direct: a $ in a finally
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InAFinally {
  static final Key<Integer> COUNT = key("count");
  static final Key<String> LOG = key("log");

  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x() {
    try {
      $(put(COUNT, 1));
    } finally {
      $(put(COUNT, 0));
    }
    return pure(0);
  }
}
