// javac: AVariableChangedAfterADollar.java:18: error: local variables referenced from a lambda expression must be final
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class AVariableChangedAfterADollar {
  interface Count { State<Integer, Count> state = State.of(Count.class); }
  interface Log { Emit<String, Log> emit = Emit.of(Log.class); }

  @Direct
  static <R extends Count> Comp<Integer, R> x() {
    int sum = 0;
    Integer now = $(Count.state.get());
    sum += now;
    Integer then = $(Count.state.get());
    return pure(sum + then);
  }
}
