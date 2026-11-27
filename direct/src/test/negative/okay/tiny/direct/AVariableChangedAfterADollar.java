// javac: AVariableChangedAfterADollar.java:24: error: local variables referenced from a lambda expression must be final
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class AVariableChangedAfterADollar {
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
    int sum = 0;
    Integer now = $(Count.state.get());
    sum += now;
    Integer then = $(Count.state.get());
    return pure(sum + then);
  }
}
