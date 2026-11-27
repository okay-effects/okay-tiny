// javac: InAWhile.java:16: error: @Direct: a $ in this statement is not rewritten
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InAWhile {
  interface Count { State<Integer, Count> state = State.of(Count.class); }
  interface Log { Emit<String, Log> emit = Emit.of(Log.class); }

  @Direct
  static <R extends Count> Comp<Integer, R> x() {
    while (true) {
      Integer now = $(Count.state.get());
      if (now > 3) return pure(now);
    }
  }
}
