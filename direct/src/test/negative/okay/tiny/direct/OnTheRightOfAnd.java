// javac: OnTheRightOfAnd.java:22: error: @Direct: a $ on the right of
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class OnTheRightOfAnd {
  sealed interface Count {
    record Ask(State<Boolean> op) implements Count, State.Of<Boolean, Count> {}
    State.In<Boolean, Count> state = State.in(Ask::new);
  }
  sealed interface Log {
    record Ask(Emit<String> op) implements Log, Emit.Of<String, Log> {}
    Emit.In<String, Log> emit = Emit.in(Ask::new);
  }

  @Direct
  static <R extends Count> Comp<Boolean, R> x(boolean ask) {
    boolean both = ask && $(Count.state.get());
    return pure(both);
  }
}
