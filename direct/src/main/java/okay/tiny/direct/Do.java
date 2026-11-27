package okay.tiny.direct;

import java.util.function.Function;
import okay.tiny.Fx.Comp;

/** What a {@link Direct} method is written with, and what it is rewritten into. */
public final class Do {
  private Do() {}

  /**
   * In a {@link Direct} method: the program is run here, and this is its value. It is never called: the plugin
   * takes it out. Called, the method was compiled without the plugin.
   */
  public static <A, R> A $(Comp<A, R> program) {
    throw new IllegalStateException(
        "$ was called: its method was compiled without -Xplugin:OkayDirect, or is not @Direct");
  }

  /**
   * first.flatMap(next), as a function: both programs are arguments, so javac infers the types of the first from
   * what the second takes, where a chain that begins with it has to be told them. A row may be less than the whole's.
   */
  @SuppressWarnings("unchecked")
  public static <A, B, R> Comp<B, R> bind(Comp<A, ? super R> first,
                                         Function<? super A, ? extends Comp<B, ? super R>> next) {
    return ((Comp<A, R>) first).flatMap(a -> (Comp<B, R>) next.apply(a));
  }
}
