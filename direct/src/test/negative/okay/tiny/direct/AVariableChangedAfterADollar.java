// javac: AVariableChangedAfterADollar.java:15: error: local variables referenced from a lambda expression must be final
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class AVariableChangedAfterADollar {
  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x() {
    int sum = 0;
    Integer now = $(get());
    sum += now;
    Integer then = $(get());
    return pure(sum + then);
  }
}
