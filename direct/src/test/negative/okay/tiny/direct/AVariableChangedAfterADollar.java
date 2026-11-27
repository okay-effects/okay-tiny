// javac: AVariableChangedAfterADollar.java:18: error: local variables referenced from a lambda expression must be final
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class AVariableChangedAfterADollar {
  static final Key<Integer> COUNT = key("count");
  static final Key<String> LOG = key("log");

  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x() {
    int sum = 0;
    Integer now = $(get(COUNT));
    sum += now;
    Integer then = $(get(COUNT));
    return pure(sum + then);
  }
}
