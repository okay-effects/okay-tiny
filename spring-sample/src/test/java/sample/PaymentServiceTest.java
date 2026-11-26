package sample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import okay.tiny.Fx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import sample.Payments.Accounts;
import sample.Payments.Clock;
import sample.Payments.Receipt;

/** okay-tiny in a Spring Boot service: beans, a real database (H2), Spring's transactions, a controller. */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentServiceTest {
  @Autowired PaymentService payments;
  @Autowired JdbcTemplate jdbc;
  @Autowired AuditLog audit;
  @Autowired MockMvc web;

  @BeforeEach
  void reset() {
    jdbc.update("update accounts set balance = 100 where name = 'ann'");
    jdbc.update("update accounts set balance = 0 where name = 'bob'");
    audit.clear();
  }

  long balance(String account) {
    return jdbc.queryForObject("select balance from accounts where name = ?", Long.class, account);
  }

  @Test
  void aPaymentIsMadeInTheDatabase() {
    Receipt receipt = payments.pay("ann", "bob", 30);
    assertEquals(new Receipt("ann", "bob", 30, receipt.at()), receipt);
    assertEquals(70, balance("ann"));
    assertEquals(30, balance("bob"));
  }

  /** The handler's exception arrives as it was thrown, with where the program was added to it. */
  @Test
  void aRefusalIsTheServicesOwnException() {
    Refused refused = assertThrows(Refused.class, () -> payments.pay("ann", "bob", 500));
    assertEquals("insufficient funds", refused.getMessage());
    assertEquals(Fx.Where.class, refused.getSuppressed()[0].getClass());
    assertEquals(100, balance("ann"));
  }

  /** The use case writes and then refuses: @Transactional on the service method rolls the write back. */
  @Test
  void theMethodsTransactionRollsBackWhatAUseCaseWroteBeforeItFailed() {
    assertThrows(Refused.class, () -> payments.payToClosed("ann", "bob", 30));
    assertEquals(100, balance("ann"));
    assertEquals(0, balance("bob"));
  }

  /** Two parts, each in a transaction of its own, no exception: the refused one is rolled back, the other stays. */
  @Test
  void aTransactionAroundAPartIsCommittedOrRolledBackWithThatPart() {
    assertEquals("paid, refused", payments.payBoth("ann", "bob", 10, 50));
    assertEquals(90, balance("ann"));
    assertEquals(10, balance("bob"));
  }

  @Test
  void everyOperationOfAUseCaseIsInTheAuditLog() {
    payments.pay("ann", "bob", 30);
    assertEquals(List.of(new Accounts.Balance("ann"), new Accounts.Move("ann", "bob", 30), new Clock.Now()),
        audit.asked());
  }

  @Test
  void theControllerSeesAnOrdinaryService() throws Exception {
    web.perform(post("/pay").param("from", "ann").param("to", "bob").param("amount", "30"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(30));
    web.perform(post("/pay").param("from", "ann").param("to", "bob").param("amount", "500"))
        .andExpect(status().isConflict()).andExpect(content().string("insufficient funds"));
    assertEquals(70, balance("ann"));
  }
}
