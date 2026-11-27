// javac: OnTheRightOfAnd.java:16: error: @Direct: a $ on the right of
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class OnTheRightOfAnd {
  interface Count { State<Boolean, Count> state = State.of(Count.class); }
  interface Log { Emit<String, Log> emit = Emit.of(Log.class); }

  @Direct
  static <R extends Count> Comp<Boolean, R> x(boolean ask) {
    boolean both = ask && $(Count.state.get());
    return pure(both);
  }
}
