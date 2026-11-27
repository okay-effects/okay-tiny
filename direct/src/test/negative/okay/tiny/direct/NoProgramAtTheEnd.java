// javac: NoProgramAtTheEnd.java:15: error: @Direct: the method ends here with no program
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class NoProgramAtTheEnd {
  static final Key<Integer> COUNT = key("count");
  static final Key<String> LOG = key("log");

  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x() {
    Integer now = $(get(COUNT));
    $(put(COUNT, now + 1));
  }
}
