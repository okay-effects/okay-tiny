// javac: LeavingAForEach.java:16: error: @Direct: a return, break or continue in a for-each
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class LeavingAForEach {
  interface Count { State<Integer, Count> state = State.of(Count.class); }
  interface Log { Emit<String, Log> emit = Emit.of(Log.class); }

  @Direct
  static <R extends Count> Comp<Integer, R> x(List<Integer> items) {
    for (Integer item : items) {
      Integer now = $(Count.state.get());
      if (now > item) return pure(item);
    }
    return pure(0);
  }
}
