package okay.tiny;

import static okay.tiny.Fx.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Every example of docs/ports-and-adapters.md: a service with ports, and the same service asking operations. */
class PortsTest {
  // ---- before: ports, an application service, adapters
  interface Accounts {
    long balance(String account);
    void move(String from, String to, long amount);
  }
  interface Clock {
    long now();
  }

  record Receipt(String from, String to, long amount, long at) {}

  static final class Refused extends RuntimeException {
    private static final long serialVersionUID = 1;
    Refused(String why) { super(why); }
  }

  static final class Payments {
    private final Accounts accounts;
    private final Clock clock;

    Payments(Accounts accounts, Clock clock) { this.accounts = accounts; this.clock = clock; }

    Receipt pay(String from, String to, long amount) {
      if (amount <= 0) throw new Refused("amount");
      if (accounts.balance(from) < amount) throw new Refused("insufficient funds");
      accounts.move(from, to, amount);
      return new Receipt(from, to, amount, clock.now());
    }
  }

  static final class MapAccounts implements Accounts {
    final Map<String, Long> balances = new HashMap<>(Map.of("ann", 100L, "bob", 0L));
    @Override public long balance(String account) { return balances.get(account); }
    @Override public void move(String from, String to, long amount) {
      balances.merge(from, -amount, Long::sum);
      balances.merge(to, amount, Long::sum);
    }
  }

  // ---- step 1: the ports as effects
  sealed interface Bank {
    record Balance(String account) implements Bank, Op<Long, Bank> {}
    record Move(String from, String to, long amount) implements Bank, Op<Void, Bank> {}
  }
  sealed interface Time {
    record Now() implements Time, Op<Long, Time> {}
  }
  sealed interface Refuse {
    record Because(String why) implements Refuse, Op<Void, Refuse> {}
  }

  // ---- step 2: the adapters you have, behind handlers
  static <B, R> Handler<Bank, B, B, R> bank(Accounts accounts) {
    return handling(Bank.class, op -> switch (op) {
      case Bank.Balance b -> answer(b, accounts.balance(b.account()));
      case Bank.Move m -> { accounts.move(m.from(), m.to(), m.amount()); yield answer(m, null); }
    });
  }

  static <B, R> Handler<Time, B, B, R> time(Clock clock) {
    return handling(Time.class, op -> switch (op) { case Time.Now now -> answer(now, clock.now()); });
  }

  static <B, R> Handler<Refuse, B, B, R> throwing() {
    return handling(Refuse.class, op -> { switch (op) { case Refuse.Because b -> throw new Refused(b.why()); } });
  }

  // ---- step 3: the use case asks
  static <R extends Refuse> Comp<Void, R> require(boolean holds, String why) {
    return holds ? pure(null) : perform(new Refuse.Because(why));
  }

  static <R extends Bank & Time & Refuse> Comp<Receipt, R> pay(String from, String to, long amount) {
    return require(amount > 0, "amount")
        .flatMap(ok -> perform(new Bank.Balance(from)))
        .flatMap(balance -> require(balance >= amount, "insufficient funds"))
        .flatMap(ok -> perform(new Bank.Move(from, to, amount)))
        .flatMap(moved -> perform(new Time.Now()))
        .map(at -> new Receipt(from, to, amount, at));
  }

  // ---- step 4: the service keeps its face: the same constructor, the same method
  static final class PaymentsWithEffects {
    private final Accounts accounts;
    private final Clock clock;

    PaymentsWithEffects(Accounts accounts, Clock clock) { this.accounts = accounts; this.clock = clock; }

    Receipt pay(String from, String to, long amount) {
      return run(Fx.<Receipt>top().handler(bank(accounts)).handler(time(clock)).handler(throwing())
          .handle(PortsTest.pay(from, to, amount)));
    }
  }

  @Test
  void theServiceBehavesAsBefore() {
    MapAccounts before = new MapAccounts(), after = new MapAccounts();
    Payments old = new Payments(before, () -> 1700);
    PaymentsWithEffects now = new PaymentsWithEffects(after, () -> 1700);

    assertEquals(old.pay("ann", "bob", 30), now.pay("ann", "bob", 30));
    assertEquals(before.balances, after.balances);
    assertEquals(assertThrows(Refused.class, () -> old.pay("ann", "bob", 500)).getMessage(),
        assertThrows(Refused.class, () -> now.pay("ann", "bob", 500)).getMessage());
    assertEquals(assertThrows(Refused.class, () -> old.pay("ann", "bob", 0)).getMessage(),
        assertThrows(Refused.class, () -> now.pay("ann", "bob", 0)).getMessage());
    assertEquals(before.balances, after.balances);
  }

  // ---- what the use case can now be given, with no change to it

  /** A refusal as a value: no exception. */
  static <A, R> Handler<Refuse, A, Optional<A>, R> optional() {
    return folding(Refuse.class, null, (s, a) -> Optional.of(a), op -> leave(Optional.empty()));
  }

  /** A dry run: balances are read, moves are written down and not made. The moves are the handler's state. */
  record Planned<A>(A value, List<Bank.Move> moves) {}

  static <A, R extends Bank> Handler<Bank, A, Planned<A>, R> dry() {
    return folding(Bank.class, List.<Bank.Move>of(), (moves, a) -> new Planned<>(a, moves), op -> switch (op) {
      case Bank.Balance b -> on(b, (moves, k) -> perform(b).flatMap(balance -> k.apply(balance, moves)));
      case Bank.Move m -> on(m, (moves, k) -> {
        var more = new ArrayList<>(moves);
        more.add(m);
        return k.apply(null, List.copyOf(more));
      });
    });
  }

  /** It reads through the bank outside it, so it stands inside a program whose row keeps Bank. */
  static <R extends Bank & Time & Refuse> Comp<Planned<Receipt>, R> planned(String from, String to, long amount) {
    return Fx.<R, Receipt>row().handler(dry()).handle(pay(from, to, amount));
  }

  @Test
  void whatTheUseCaseCanNowBeGiven() {
    MapAccounts accounts = new MapAccounts();
    var world = Fx.<Receipt>top();

    // every operation, as data
    var audit = new ArrayList<Op<?, ?>>();
    run(world.handler(bank(accounts)).handler(time(() -> 1700)).handler(throwing()).handle(pay("ann", "bob", 30)),
        audit::add);
    assertEquals(List.of(new Bank.Balance("ann"), new Bank.Move("ann", "bob", 30), new Time.Now()), audit);

    // a refusal as a value
    Optional<Receipt> none = run(world.handler(optional()).handler(bank(accounts)).handler(time(() -> 1700))
        .handle(pay("ann", "bob", 500)));
    assertEquals(Optional.empty(), none);

    // a dry run: the real balances are read, nothing is moved
    Map<String, Long> untouched = Map.copyOf(accounts.balances);
    Planned<Receipt> plan = run(Fx.<Planned<Receipt>>top()
        .handler(bank(accounts)).handler(time(() -> 1700)).handler(throwing())
        .handle(planned("ann", "bob", 30)));
    assertEquals(List.of(new Bank.Move("ann", "bob", 30)), plan.moves());
    assertEquals(untouched, accounts.balances);
  }
}
