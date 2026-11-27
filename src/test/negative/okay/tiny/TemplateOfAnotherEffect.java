// javac: TemplateOfAnotherEffect.java:17: error: incompatible types
package okay.tiny;

import static okay.tiny.Fx.*;

class TemplateOfAnotherEffect {
  /** A template of one operation, as Std's are. */
  static final class Once<F> implements Template<F> {
    record Ask<F>(Once<F> of) implements Op<Integer, F> {}
  }
  interface Count { Once<Count> once = new Once<>(); }
  interface Other { Once<Other> once = new Once<>(); }

  /** A slip: a handler said to be of Other, made of the template said for Count — whose operations would find it. */
  static <B, R> Handler<Other, B, B, R> other() {
    Handler<Other, B, B, R> handler =
        by(Count.once, null, (none, b) -> b, (Once.Ask<Count> ask) -> leave(null));
    return handler;
  } }
