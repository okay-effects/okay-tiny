// javac: InALambda.java:22: error: @Direct: a $ in a lambda
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InALambda {
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
    int sum = items.stream().mapToInt(item -> item + $(Count.state.get())).sum();
    return pure(sum);
  }
}
