// javac: NoProgramAtTheEnd.java:12: error: @Direct: the method ends here with no program
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class NoProgramAtTheEnd {
  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x() {
    Integer now = $(get());
    $(put(now + 1));
  }
}
