# From ports and adapters

A service written with interfaces and injection already has the design this library needs: it has decided what
its ports are. This page takes such a service to effects in four steps, changes none of its callers, and then
shows what the use case can be given that it could not before. `PortsTest` compiles and runs all of it.

| Ports and adapters | Here |
|---|---|
| a port: an interface the application owns | an effect: a sealed interface of operations |
| an adapter: a class that implements it | a handler |
| an application service | a program |
| the composition root, or the container | the one place where handlers are put |

The difference is one thing. A call to a port happens and is gone. An operation is a value, and between the
question and the answer stands a handler that also holds the rest of the use case.

## Before

```java
interface Accounts {
  long balance(String account);
  void move(String from, String to, long amount);
}
interface Clock {
  long now();
}

final class Payments {
  private final Accounts accounts;
  private final Clock clock;

  Receipt pay(String from, String to, long amount) {
    if (amount <= 0) throw new Refused("amount");
    if (accounts.balance(from) < amount) throw new Refused("insufficient funds");
    accounts.move(from, to, amount);
    return new Receipt(from, to, amount, clock.now());
  }
}
```

## Step 1. The ports as effects

Each method of a port becomes a record: its parameters are the fields, its return type is the answer.

```java
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
```

The third was not a port. A refusal was a `throw`, which is a reach for the outside that no interface named.
Now it is asked like the others, and what it does is decided where the handlers are put.

## Step 2. The adapters you have, behind handlers

Nothing of an adapter is rewritten. A handler calls it.

```java
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
```

## Step 3. The use case asks

```java
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
```

The same five lines of logic, written as a chain. Its type is now the list of what it reaches:
`Bank & Time & Refuse`, and nothing else can be reached from it.

## Step 4. The service keeps its face

The class that callers and the container know stays: the same constructor, the same method. Inside, it puts the
handlers and runs.

```java
final class PaymentsWithEffects {
  private final Accounts accounts;
  private final Clock clock;

  Receipt pay(String from, String to, long amount) {
    return run(Fx.<Receipt>top().handler(bank(accounts)).handler(time(clock)).handler(throwing())
        .handle(PortsTest.pay(from, to, amount)));
  }
}
```

`theServiceBehavesAsBefore` runs both classes against the same scenarios — a payment, too little money, a zero
amount — and compares receipts, exceptions and balances. A migration can stop here: one service moved, the rest
of the code base untouched.

## What the use case can now be given

`pay` is not changed in any of these.

**Every operation, as data.** The second argument of `run`:

```java
var audit = new ArrayList<Op<?, ?>>();
run(.., audit::add);
// [Balance[account=ann], Move[from=ann, to=bob, amount=30], Now[]]
```

With ports this is a decorator around each adapter, or a proxy.

**A refusal as a value.** Another handler of `Refuse`, and the service method can return an `Optional` with no
exception anywhere:

```java
static <A, R> Handler<Refuse, A, Optional<A>, R> optional() {
  return folding(Refuse.class, null, (s, a) -> Optional.of(a), op -> leave(Optional.empty()));
}
```

**A dry run.** A handler of `Bank` that reads real balances, through the bank outside it, and writes moves
down in place of making them. What would this payment do?

```java
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

static <R extends Bank & Time & Refuse> Comp<Planned<Receipt>, R> planned(String from, String to, long amount) {
  return Fx.<R, Receipt>row().handler(dry()).handle(pay(from, to, amount));
}
```

`planned(..)` gives the receipt and the list of moves, and the accounts are as they were. With ports this is a
second implementation of `Accounts` that wraps the first, a flag, and care that nothing reaches around it.

## What to watch

- **An operation that may be answered must make sense when it is.** `Refuse.Because` answers `Void`, so a handler
  *could* go on after it — and `pay` would then move money it had refused to move. Here every handler of
  `Refuse` leaves the part. If going on is wanted (collect every problem of a form), write the program so that
  it is harmless: check first, act after.
- **Transactions.** `@Transactional` on the service method still works: the whole of `run` is inside it, on
  one thread. A transaction around a *part* of a program is a handler whose clause goes on with the rest and
  commits after it, with `ensuring` for the rollback; this library has not been tried with a container's
  transaction manager, and `DESIGN.md` says so.
- **What does not move.** A port that only returns values, has no cross-cutting code around it, and is not a
  pain in tests gains nothing. Move the use cases where a policy about "what happens after the answer" is
  written into the use case itself.
