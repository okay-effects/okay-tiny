// javac: InATryWithACatch.java:16: error: @Direct: a $ in a try with a catch
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InATryWithACatch {
  interface Count { State<Integer, Count> state = State.of(Count.class); }
  interface Log { Emit<String, Log> emit = Emit.of(Log.class); }

  @Direct
  static <R extends Count> Comp<Integer, R> x() {
    try {
      Integer now = $(Count.state.get());
      return pure(now);
    } catch (RuntimeException failed) {
      return pure(0);
    }
  }
}
