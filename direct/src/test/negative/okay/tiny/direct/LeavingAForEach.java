// javac: LeavingAForEach.java:13: error: @Direct: a return, break or continue in a for-each
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class LeavingAForEach {
  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x(List<Integer> items) {
    for (Integer item : items) {
      Integer now = $(get());
      if (now > item) return pure(item);
    }
    return pure(0);
  }
}
