// javac: InAFinally.java:18: error: @Direct: a $ in a finally
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InAFinally {
  interface Count { State<Integer, Count> state = State.of(Count.class); }
  interface Log { Emit<String, Log> emit = Emit.of(Log.class); }

  @Direct
  static <R extends Count> Comp<Integer, R> x() {
    try {
      $(Count.state.put(1));
    } finally {
      $(Count.state.put(0));
    }
    return pure(0);
  }
}
