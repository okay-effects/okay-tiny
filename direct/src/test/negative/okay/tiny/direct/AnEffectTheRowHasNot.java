// javac: inference variable R#1 has incompatible bounds
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class AnEffectTheRowHasNot {
  interface Count { State<Integer, Count> state = State.of(Count.class); }
  interface Log { Emit<String, Log> emit = Emit.of(Log.class); }

  @Direct
  static <R extends Count> Comp<Integer, R> x() {
    Integer now = $(Count.state.get());
    $(Log.emit.tell("now " + now));
    return pure(now);
  }
}
