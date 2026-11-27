// javac: InATryWithACatch.java:22: error: @Direct: a $ in a try with a catch
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InATryWithACatch {
  sealed interface Count {
    record Ask<X>(State<Integer, X> op) implements Count, State.Of<Integer, X, Count> {}
    State.In<Integer, Count> state = State.in(Ask::new);
  }
  sealed interface Log {
    record Ask<X>(Emit<String, X> op) implements Log, Emit.Of<String, X, Log> {}
    Emit.In<String, Log> emit = Emit.in(Ask::new);
  }

  @Direct
  static <R extends Count> Comp<Integer, R> x() {
    try {
      Integer now = $(Count.state.get());
      return pure(now);
    } catch (RuntimeException failed) {
      return pure(0);
    }
  }
}
