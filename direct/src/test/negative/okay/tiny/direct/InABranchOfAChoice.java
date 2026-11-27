// javac: InABranchOfAChoice.java:16: error: @Direct: a $ in a branch of ?:
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InABranchOfAChoice {
  interface Count { State<Integer, Count> state = State.of(Count.class); }
  interface Log { Emit<String, Log> emit = Emit.of(Log.class); }

  @Direct
  static <R extends Count> Comp<Integer, R> x(boolean ask) {
    Integer now = ask ? $(Count.state.get()) : 0;
    return pure(now);
  }
}
