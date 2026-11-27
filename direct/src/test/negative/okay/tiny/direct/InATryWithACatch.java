// javac: InATryWithACatch.java:13: error: @Direct: a $ in a try with a catch
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InATryWithACatch {
  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x() {
    try {
      Integer now = $(get());
      return pure(now);
    } catch (RuntimeException failed) {
      return pure(0);
    }
  }
}
