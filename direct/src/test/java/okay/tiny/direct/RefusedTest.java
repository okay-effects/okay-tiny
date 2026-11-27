package okay.tiny.direct;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * What is refused in a @Direct method: each program of src/test/negative is compiled with the plugin, must not
 * compile, and must be refused at the line and for the reason its first line names ("// javac: .."). Some are
 * refused by the plugin, where a $ stands where it cannot be rewritten; some by javac, of what the plugin wrote.
 */
class RefusedTest {
  @TestFactory
  Stream<DynamicTest> refused() throws IOException {
    Path programs = Path.of(System.getProperty("negative"), "okay", "tiny", "direct");
    try (Stream<Path> files = Files.list(programs)) {
      List<Path> all = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
      assertFalse(all.isEmpty(), "no programs in " + programs);
      return all.stream().map(f -> DynamicTest.dynamicTest(f.getFileName().toString(), () -> refused(f)));
    }
  }

  static void refused(Path program) throws IOException {
    String first = Files.readAllLines(program).get(0);
    assertTrue(first.startsWith("// javac: "), "the first line of " + program + " names the refusal");
    String expected = first.substring("// javac: ".length());
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    StringWriter said = new StringWriter();
    Path out = Files.createTempDirectory("refused");
    String classes = System.getProperty("java.class.path");
    int result = javac.run(null, null, new PrintStream(new OutputStream() {
      @Override public void write(int b) { said.write(b); }
    }), "-Xplugin:OkayDirect", "-processorpath", System.getProperty("plugin") + System.getProperty("path.separator")
        + classes, "-cp", classes, "-d", out.toString(), program.toString());
    assertTrue(result != 0, program.getFileName() + " compiles, and must not");
    assertTrue(said.toString().contains(expected),
        program.getFileName() + " is refused, but not for \"" + expected + "\":\n" + said);
  }
}
