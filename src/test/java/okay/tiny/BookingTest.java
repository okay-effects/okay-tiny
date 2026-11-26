package okay.tiny;

import static okay.tiny.Booking.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The scenarios of okay-shop's booking, as okay-java's BookingTest has them, on this kernel. */
class BookingTest {
  @Test
  void aBookingTaken() {
    World w = new World();
    assertEquals(new Response(200, "booked SB1"), book(w, SHOP, form()));
    assertEquals(List.of(new Booked("SB1", "key2", "cut", 5000, "Ann", "ann@mail", w.now, Optional.empty(), 80)),
        w.bookings);
    assertEquals(List.of("ann@mail: booked SB1", "owner: booked SB1 by Ann"), w.letters);
    assertEquals(List.of("booked SB1"), w.notices);
    assertEquals(List.of("store", "captcha", "store"), w.log);    // no geocoder for a booking at the shop
  }

  /** Each refusal in its order, and nothing asked after it. */
  @Test
  void theRefusalsInTheirOrder() {
    World w = new World();
    assertEquals(new Response(400, "refused: name"), book(w, SHOP, form("name", " ")));
    assertEquals(new Response(400, "refused: contact"), book(w, SHOP, form("contact", "nobody")));
    assertEquals(new Response(400, "refused: consent"), book(w, SHOP, form("rodo_consent", "false")));
    assertEquals(new Response(400, "refused: service"), book(w, SHOP, form("service", "old")));
    assertEquals(List.of(), w.log);
    w.human = false;
    assertEquals(new Response(403, "refused: captcha"), book(w, SHOP, form()));
    assertEquals(List.of("store", "captcha"), w.log);
    assertEquals(List.of(), w.letters);
  }

  @Test
  void aSlotTakenAndTheLimit() {
    World w = new World();
    assertEquals(200, book(w, SHOP, form()).status());
    assertEquals(new Response(409, "refused: taken"), book(w, SHOP, form()));
    assertEquals(1, w.bookings.size());
    assertEquals(2, w.letters.size());
    for (int i = 0; i < 3; i++) book(w, SHOP, form("start", "" + (6000 + i)));
    assertEquals(new Response(429, "refused: limit"), book(w, SHOP, form("start", "9000")));
  }

  /** At the client's: inside by the list asks no geocoder; by the radius it does; an address not found is not a no. */
  @Test
  void theAreaByItsListAndByItsRadius() {
    World w = new World();
    w.places.put("Sopot", new Geo(54.45, 18.56));
    w.places.put("Warszawa", new Geo(52.2, 21.0));
    Map<String, String> home = form("mode", "client", "street", "Morska 1", "postcode", "81-001", "city", "Gdynia");
    assertEquals(new Response(200, "booked SB1"), book(w, SHOP, home));
    assertEquals(100L, w.bookings.get(0).price());
    assertEquals(List.of("store", "captcha", "store"), w.log);
    w.log.clear();
    home.put("city", "Sopot");
    home.put("start", "7000");
    assertEquals(200, book(w, SHOP, home).status());
    assertEquals(List.of("geocoder", "store", "captcha", "store"), w.log);
    home.put("city", "Warszawa");
    assertEquals(new Response(409, "refused: area"), book(w, SHOP, home));
    home.put("city", "Nowhere");
    assertEquals(new Response(409, "refused: area-unchecked"), book(w, SHOP, home));
    home.put("postcode", "1");
    assertEquals(new Response(400, "refused: address"), book(w, SHOP, home));
  }
}
