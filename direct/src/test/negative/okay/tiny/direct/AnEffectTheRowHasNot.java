// javac: inference variable R#1 has incompatible bounds
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class AnEffectTheRowHasNot {
  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x() {
    Integer now = $(get());
    $(tell("now " + now));
    return pure(now);
  }
}
