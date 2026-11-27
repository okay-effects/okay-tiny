// javac: InAWhile.java:22: error: @Direct: a $ in this statement is not rewritten
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InAWhile {
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
    while (true) {
      Integer now = $(Count.state.get());
      if (now > 3) return pure(now);
    }
  }
}
