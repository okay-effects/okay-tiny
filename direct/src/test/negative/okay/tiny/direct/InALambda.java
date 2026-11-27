// javac: InALambda.java:13: error: @Direct: a $ in a lambda
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InALambda {
  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x(List<Integer> items) {
    int sum = items.stream().mapToInt(item -> item + $(get())).sum();
    return pure(sum);
  }
}
