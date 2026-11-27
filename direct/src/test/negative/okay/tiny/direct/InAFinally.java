// javac: InAFinally.java:15: error: @Direct: a $ in a finally
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InAFinally {
  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x() {
    try {
      $(put(1));
    } finally {
      $(put(0));
    }
    return pure(0);
  }
}
