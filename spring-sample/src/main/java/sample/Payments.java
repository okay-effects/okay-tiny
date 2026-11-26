package sample;

import static okay.tiny.Fx.*;

import okay.tiny.Fx.Comp;
import okay.tiny.Fx.Op;

/** The business logic: programs over declared effects. Nothing of Spring is known here. */
public final class Payments {
  private Payments() {}

  public sealed interface Accounts {
    record Balance(String account) implements Accounts, Op<Long, Accounts> {}
    record Move(String from, String to, long amount) implements Accounts, Op<Void, Accounts> {}
  }
  public sealed interface Clock {
    record Now() implements Clock, Op<Long, Clock> {}
  }
  public sealed interface Refuse {
    record Because(String why) implements Refuse, Op<Void, Refuse> {}
  }

  public record Receipt(String from, String to, long amount, long at) {}

  static <R extends Refuse> Comp<Void, R> require(boolean holds, String why) {
    return holds ? pure(null) : perform(new Refuse.Because(why));
  }

  public static <R extends Accounts & Clock & Refuse> Comp<Receipt, R> pay(String from, String to, long amount) {
    return require(amount > 0, "amount")
        .flatMap(ok -> perform(new Accounts.Balance(from)))
        .flatMap(balance -> require(balance >= amount, "insufficient funds"))
        .flatMap(ok -> perform(new Accounts.Move(from, to, amount)))
        .flatMap(moved -> perform(new Clock.Now()))
        .map(at -> new Receipt(from, to, amount, at));
  }

  /** A use case that fails after it has written: the money is moved, and then the receiver is found to be closed. */
  public static <R extends Accounts & Clock & Refuse> Comp<Receipt, R> payToClosed(String from, String to,
                                                                                  long amount) {
    return perform(new Accounts.Move(from, to, amount))
        .flatMap(moved -> require(false, "the account " + to + " is closed"))
        .flatMap(never -> perform(new Clock.Now()))
        .map(at -> new Receipt(from, to, amount, at));
  }
}
