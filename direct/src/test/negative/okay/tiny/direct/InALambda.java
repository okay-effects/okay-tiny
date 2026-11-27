// javac: InALambda.java:16: error: @Direct: a $ in a lambda
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InALambda {
  interface Count { State<Integer, Count> state = State.of(Count.class); }
  interface Log { Emit<String, Log> emit = Emit.of(Log.class); }

  @Direct
  static <R extends Count> Comp<Integer, R> x(List<Integer> items) {
    int sum = items.stream().mapToInt(item -> item + $(Count.state.get())).sum();
    return pure(sum);
  }
}
