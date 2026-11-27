// javac: NoProgramAtTheEnd.java:21: error: @Direct: the method ends here with no program
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class NoProgramAtTheEnd {
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
    Integer now = $(Count.state.get());
    $(Count.state.put(now + 1));
  }
}
