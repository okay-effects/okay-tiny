# In a Spring Boot service

okay-tiny needs nothing of Spring and Spring nothing of it. This page is what was tried, and it is all run:
`spring-sample/` is a Spring Boot 3.5 service on Java 21 with a database (H2, through `JdbcTemplate` and
through JPA), Spring's transactions and a controller; `PaymentServiceTest` and `JpaPaymentServiceTest` start the
container and check each claim below.

## The shape

| Part | Is | Knows Spring |
|---|---|---|
| `Payments` | the business logic: effects, and programs that ask them | no |
| `Handlers` | a `@Component` that holds `JdbcTemplate` and the transaction manager, and makes the handlers | yes |
| `PaymentService` | a `@Service` whose methods put the handlers and `run` | yes |
| `PaymentController` | a `@RestController` that calls the service | yes |
| `JpaHandlers`, `JpaPaymentService` | the accounts answered through JPA: an entity, a repository | yes |

Callers, the container and the controller see an ordinary service. The only new thing is inside its methods.

```java
@Service
public class PaymentService {
  private final Handlers handlers;
  private final AuditLog audit;

  @Transactional
  public Receipt pay(String from, String to, long amount) {
    return run(Fx.<Receipt>top().handler(handlers.refusing()).handler(handlers.clock()).handler(handlers.accounts())
        .handle(Payments.pay(from, to, amount)), audit::record);
  }
}
```

A handler is where a bean is used:

```java
public <B, R> Handler<Accounts, B, B, R> accounts() {
  return handling(Accounts.class, op -> switch (op) {
    case Accounts.Balance b ->
        answer(b, jdbc.queryForObject("select balance from accounts where name = ?", Long.class, b.account()));
    case Accounts.Move m -> {
      jdbc.update("update accounts set balance = balance - ? where name = ?", m.amount(), m.from());
      jdbc.update("update accounts set balance = balance + ? where name = ?", m.amount(), m.to());
      yield answer(m, null);
    }
  });
}
```

## What was checked

**One thread.** The machine runs a program on the thread that called `run`, from beginning to end. Everything
Spring binds to a thread — the transaction, the security context, the request — is where the handlers expect
it. This is the fact the rest stands on.

**`@Transactional` on the service method works as it always did.** The whole of `run` is inside the method's
transaction. A use case that writes and then refuses — the handler of `Refuse` throws — is rolled back by
Spring (`theMethodsTransactionRollsBackWhatAUseCaseWroteBeforeItFailed`: the money is moved, the refusal is
thrown, and the database has the balances it began with).

**An exception arrives as it was thrown.** A handler throws `Refused`; the service's caller, and the
controller's `@ExceptionHandler(Refused.class)`, get `Refused` — not a wrapper. It carries one suppressed
`Fx.Where` (the operation asked last, the handlers in force), which nothing has to look at
(`aRefusalIsTheServicesOwnException`, `theControllerSeesAnOrdinaryService`: 200 with a receipt, 409 with the
reason).

**A transaction around a part of a program.** Where one method needs two transactions, or a transaction and
no exception, the transaction is put around a part:

```java
public <A, R> Comp<A, R> transactional(Comp<A, R> part) {
  return defer(() -> {
    TransactionStatus status = transactions.getTransaction(new DefaultTransactionDefinition());
    boolean[] committed = {false};
    return ensuring(part.map(a -> { transactions.commit(status); committed[0] = true; return a; }),
        () -> { if (!committed[0]) transactions.rollback(status); });
  });
}
```

It is begun when the part is reached, committed when the part ends with a value, and rolled back when the part
is left or fails — `ensuring` is what makes the last two one case. `payBoth` makes two payments, each in its
own transaction, with a refusal as a value (`leave(Optional.empty())`) and no exception anywhere:

```java
Comp<Optional<Receipt>, R> one =
    Fx.<R, Optional<Receipt>>row().handler(handlers.optional())
        .handle(handlers.transactional(Payments.pay(from, to, first)));
```

`aTransactionAroundAPartIsCommittedOrRolledBackWithThatPart`: the first payment is in the database, the second
— which moved money and was then refused — is not, and the method returned `"paid, refused"`. With
annotations this takes two beans, or a self-injection, because a call to a method of the same object does not
pass the proxy.

**The audit is the second argument of `run`.** `audit::record` is given every operation of the use case, as
values (`everyOperationOfAUseCaseIsInTheAuditLog`: `Balance[ann]`, `Move[ann, bob, 30]`, `Now[]`). No aspect,
and nothing that a private method or a lambda can walk past.

## JPA

The accounts have a second handler, `JpaHandlers.accounts()`, over an entity `Account` and a Spring Data
repository. `Payments` is not touched: the service puts one handler in place of the other.

```java
case Accounts.Move m -> {
  account(m.from()).add(-m.amount());       // accounts.findById(..): an entity of the context in force
  account(m.to()).add(m.amount());
  yield answer(m, null);                    // nothing is saved here
}
```

- **The persistence context is the transaction's, and so the use case's.** The handler changes two entities
  and returns; the commit of the service method's `@Transactional` writes them, after the program has ended
  (`entitiesAHandlerChangedAreWrittenWhenTheMethodsTransactionIsCommitted`). Every operation of the use case
  is answered in that one context, since the machine runs on the thread that called `run`.
- **`transactional(part)` gives a part a context of its own.** Of two payments the second changes its entities
  and is refused: its transaction is rolled back and they are not written; the first's are
  (`aTransactionAroundAPartIsThePersistenceContextOfThatPart`).
- **With no transaction nothing is written**, and nothing fails: the use case gives a receipt and the balances
  are as they were (`withNoTransactionChangedEntitiesAreNotWritten`). It is JPA's rule, the same for a service
  without effects — but here the transaction is one line of the chain or one annotation, and easy to lose
  when a use case is run from a new place.
- **A rest gone on with twice** runs both ways in the one context: entities the first way changed are what
  the second way reads. A handler like `Std.all()` over JPA needs `transactional` inside each way, or values
  in place of entities. Not run in the sample.

## What to know

- **Handlers are made for each call** here (`handlers.accounts()` in the service method). They are cheap — a
  few objects — and it keeps a handler with a state from being shared by two requests. A handler with no state
  may as well be made once and kept in a field.
- **`transactional` above is for a rest that is gone on with once.** Its `committed` flag is shared by every
  way of a rest gone on with twice; a part under a handler like `Std.all()` needs a transaction for each way,
  and this one is not that.
- **Asynchrony.** A use case that asks `Std.Async` inside a transaction is run under `blocking()`: the thread
  waits, and the transaction is still bound to it. `Std.start` gives a `CompletableFuture`, which a controller
  may return; after its first wait the program is on another thread, where nothing Spring bound is. Neither is
  run in the sample.
- **Reactive stacks.** A program runs on one thread and blocks where a handler blocks. On WebFlux a program
  that reaches a database belongs on a scheduler for blocking work, like any blocking call.
- **What was not tried:** lazy associations read after the transaction has ended, `@Async`, Spring Security's
  method annotations, more than one data source. Nothing known stands in their way — they are bound to the
  thread too — but nothing here runs them.
