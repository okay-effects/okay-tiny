// javac: inference variable R#1 has incompatible bounds
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class AnEffectTheRowHasNot {
  static final Key<Integer> COUNT = key("count");
  static final Key<String> LOG = key("log");

  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x() {
    Integer now = $(get(COUNT));
    $(tell(LOG, "now " + now));
    return pure(now);
  }
}
