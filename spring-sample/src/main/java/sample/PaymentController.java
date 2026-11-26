package sample;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sample.Payments.Receipt;

@RestController
public class PaymentController {
  private final PaymentService payments;

  public PaymentController(PaymentService payments) { this.payments = payments; }

  @PostMapping("/pay")
  public Receipt pay(@RequestParam String from, @RequestParam String to, @RequestParam long amount) {
    return payments.pay(from, to, amount);
  }

  /** The exception a handler threw arrives as it is. */
  @ExceptionHandler(Refused.class)
  public ResponseEntity<String> refused(Refused refused) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(refused.getMessage());
  }
}
