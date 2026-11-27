// javac: LeavingAForEach.java:22: error: @Direct: a return, break or continue in a for-each
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class LeavingAForEach {
  sealed interface Count {
    record Ask(State<Integer> op) implements Count, State.Of<Integer, Count> {}
    State.In<Integer, Count> state = State.in(Ask::new);
  }
  sealed interface Log {
    record Ask(Emit<String> op) implements Log, Emit.Of<String, Log> {}
    Emit.In<String, Log> emit = Emit.in(Ask::new);
  }

  @Direct
  static <R extends Count> Comp<Integer, R> x(List<Integer> items) {
    for (Integer item : items) {
      Integer now = $(Count.state.get());
      if (now > item) return pure(item);
    }
    return pure(0);
  }
}
