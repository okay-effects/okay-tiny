package sample;

import static okay.tiny.Fx.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import okay.tiny.Fx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import sample.Payments.Receipt;

/** The same programs with the accounts answered through JPA: entities, a persistence context, dirty checking. */
@SpringBootTest
class JpaPaymentServiceTest {
  @Autowired JpaPaymentService payments;
  @Autowired Handlers handlers;
  @Autowired JpaHandlers jpa;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("update accounts set balance = 100 where name = 'ann'");
    jdbc.update("update accounts set balance = 0 where name = 'bob'");
  }

  long balance(String account) {
    return jdbc.queryForObject("select balance from accounts where name = ?", Long.class, account);
  }

  /** The handler changes two entities and saves nothing: the commit of the method's transaction writes them. */
  @Test
  void entitiesAHandlerChangedAreWrittenWhenTheMethodsTransactionIsCommitted() {
    Receipt receipt = payments.pay("ann", "bob", 30);
    assertEquals(30, receipt.amount());
    assertEquals(70, balance("ann"));
    assertEquals(30, balance("bob"));
  }

  @Test
  void aRefusalLeavesTheEntitiesAsTheyWere() {
    assertThrows(Refused.class, () -> payments.pay("ann", "bob", 500));
    assertEquals(100, balance("ann"));
  }

  /**
   * Two parts, each in a transaction and a persistence context of its own: the second changes its entities and is
   * then refused — they are not written, and the first part's are.
   */
  @Test
  void aTransactionAroundAPartIsThePersistenceContextOfThatPart() {
    assertEquals("paid, refused", payments.payBoth("ann", "bob", 10, 50));
    assertEquals(90, balance("ann"));
    assertEquals(10, balance("bob"));
  }

  /**
   * With no transaction there is no context to write the entities from: the use case gives a receipt and nothing
   * is moved. This is JPA's own rule, the same for a service written without effects.
   */
  @Test
  void withNoTransactionChangedEntitiesAreNotWritten() {
    Receipt receipt = run(Fx.<Receipt>top().handler(handlers.refusing()).handler(handlers.clock())
        .handler(jpa.accounts()).handle(Payments.pay("ann", "bob", 30)));
    assertEquals(30, receipt.amount());
    assertEquals(100, balance("ann"));
    assertEquals(0, balance("bob"));
  }
}
