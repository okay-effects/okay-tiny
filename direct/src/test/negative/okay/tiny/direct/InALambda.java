// javac: InALambda.java:16: error: @Direct: a $ in a lambda
package okay.tiny.direct;

import static okay.tiny.Fx.*;
import static okay.tiny.Std.*;
import static okay.tiny.direct.Do.$;

import java.util.List;

class InALambda {
  static final Key<Integer> COUNT = key("count");
  static final Key<String> LOG = key("log");

  @Direct
  static <R extends State<Integer>> Comp<Integer, R> x(List<Integer> items) {
    int sum = items.stream().mapToInt(item -> item + $(get(COUNT))).sum();
    return pure(sum);
  }
}
