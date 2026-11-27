// javac: OnTheRightOfAnd.java:16: error: @Direct: a $ on the right of
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class OnTheRightOfAnd {
  static final Key<Boolean> COUNT = key("count");
  static final Key<String> LOG = key("log");

  @Direct
  static <R extends State<Boolean>> Comp<Boolean, R> x(boolean ask) {
    boolean both = ask && $(get(COUNT));
    return pure(both);
  }
}
