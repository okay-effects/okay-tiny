// javac: OnTheRightOfAnd.java:22: error: @Direct: a $ on the right of
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class OnTheRightOfAnd {
  sealed interface Count {
    record Ask<X>(State<Boolean, X> op) implements Count, State.Of<Boolean, X, Count> {}
    State.In<Boolean, Count> state = State.in(Ask::new);
  }
  sealed interface Log {
    record Ask<X>(Emit<String, X> op) implements Log, Emit.Of<String, X, Log> {}
    Emit.In<String, Log> emit = Emit.in(Ask::new);
  }

  @Direct
  static <R extends Count> Comp<Boolean, R> x(boolean ask) {
    boolean both = ask && $(Count.state.get());
    return pure(both);
  }
}
