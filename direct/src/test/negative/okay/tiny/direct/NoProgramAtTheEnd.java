// javac: NoProgramAtTheEnd.java:15: error: @Direct: the method ends here with no program
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class NoProgramAtTheEnd {
  interface Count { State<Integer, Count> state = State.of(Count.class); }
  interface Log { Emit<String, Log> emit = Emit.of(Log.class); }

  @Direct
  static <R extends Count> Comp<Integer, R> x() {
    Integer now = $(Count.state.get());
    $(Count.state.put(now + 1));
  }
}
