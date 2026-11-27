package okay.tiny.direct;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A method written in direct style: its body is plain Java in which {@code $(program)} runs a program and gives its
 * value, and {@code return} gives the program the method ends with. The compiler plugin (-Xplugin:OkayDirect)
 * rewrites the body into the chain of flatMap it stands for, before javac checks its types: so the row is checked
 * as in a chain written by hand, and what runs is the same program, on the same machine.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Direct {}
