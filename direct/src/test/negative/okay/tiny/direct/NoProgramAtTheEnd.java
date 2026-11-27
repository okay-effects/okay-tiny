// javac: NoProgramAtTheEnd.java:21: error: @Direct: the method ends here with no program
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class NoProgramAtTheEnd {
  sealed interface Count {
    record Ask(State<Integer> op) implements Count, State.Of<Integer, Count> {}
    State.In<Integer, Count> state = State.in(Ask::new);
  }
  sealed interface Log {
    record Ask(Emit<String> op) implements Log, Emit.Of<String, Log> {}
    Emit.In<String, Log> emit = Emit.in(Ask::new);
  }

  @Direct
  static <R extends Count> Comp<Integer, R> x() {
    Integer now = $(Count.state.get());
    $(Count.state.put(now + 1));
  }
}
