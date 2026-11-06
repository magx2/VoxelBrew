package dev.voxelbrew.engine.parameters;

import static dev.voxelbrew.engine.units.Measurements.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.measure.Quantity;
import javax.measure.Unit;

/** Bounded arithmetic parser. No scripting engine, reflection or external evaluation. */
public final class Expressions {
    private Expressions() {}

    private static final Set<String> FUNCTIONS =
            Set.of("abs", "sqrt", "min", "max", "sin", "cos", "tan", "asin", "acos", "atan", "floor", "ceil", "round");
    private static final Pattern TOKEN = Pattern.compile("\"(?:[^\"]|\"\")*\"|(?<![A-Za-z_0-9.])[A-Za-z][A-Za-z_.]*");

    public static boolean isFormula(String text) {
        return text != null && text.stripLeading().startsWith("=");
    }

    /** Preserve a stored literal when displaying it in an expression-enabled text editor. */
    public static String literalText(String text) {
        return isFormula(text) ? "=\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    public static boolean isNumber(String text) {
        return text != null && text.strip().matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    }

    private static Quantity<?> numeric(Object value) {
        if (value instanceof Quantity<?> quantity) return quantity;
        throw new IllegalArgumentException("Expected a number, but the expression produced text");
    }

    /** Stable, locale-independent display; dimensional results use model units (mm/degrees). */
    public static String format(Object value) {
        if (value instanceof String text) return text;
        double number = number(numeric(value));
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Expression must be finite");
        return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
    }

    /** Text is literal unless it starts with '='. Use doubled quotes inside string literals. */
    public static String evaluateText(String text, Function<String, Quantity<?>> names) {
        Objects.requireNonNull(text);
        return isFormula(text) ? format(evaluateAny(text, names)) : text;
    }

    public static Object evaluateAny(String expression, Function<String, Quantity<?>> names) {
        return new Parser(expression, names, ParameterUnit.MM).parse();
    }

    private static boolean scalar(Quantity<?> q) {
        return sameKind(q.getUnit(), ONE);
    }

    private static double number(Quantity<?> q) {
        return q.getValue().doubleValue();
    }

    private static Quantity<?> implicit(Quantity<?> q, Quantity<?> dimensional, ParameterUnit unit) {
        Unit<?> target = sameKind(unit.unit(), dimensional.getUnit()) ? unit.unit() : canonical(dimensional.getUnit());
        return normalized(quantity(value(q, ONE), target));
    }

    private static <Q extends Quantity<Q>> Quantity<Q> sum(Quantity<Q> a, Quantity<?> b) {
        return a.add(to(b, a.getUnit()));
    }

    private static Quantity<?> add(Quantity<?> a, Quantity<?> b, ParameterUnit unit) {
        if (scalar(a) && !scalar(b)) a = implicit(a, b, unit);
        if (scalar(b) && !scalar(a)) b = implicit(b, a, unit);
        return normalized(sum(a, b));
    }

    private static Quantity<?> times(Quantity<?> a, Quantity<?> b, boolean divide) {
        if (divide && number(b) == 0) throw new IllegalArgumentException("Division by zero");
        return normalized(divide ? a.divide(b) : a.multiply(b));
    }

    public static boolean reserved(String name) {
        return FUNCTIONS.contains(name)
                || name.equals("pi")
                || name.equals("e")
                || name.equals("NaN")
                || name.equals("Infinity")
                || Arrays.stream(ParameterUnit.values())
                        .anyMatch(u -> u.toString().equals(name));
    }

    public static String rename(String expression, String oldName, String newName) {
        return TOKEN.matcher(expression)
                .replaceAll(
                        m -> java.util.regex.Matcher.quoteReplacement(m.group().equals(oldName) ? newName : m.group()));
    }

    public static Quantity<?> evaluate(String expression, ParameterUnit unit, Function<String, Quantity<?>> names) {
        var value = numeric(new Parser(expression, names, unit).parse());
        if (scalar(value)) value = unit.quantity(value(value, ONE));
        return to(value, unit.canonicalUnit());
    }

    public static Quantity<?> evaluateRaw(String expression, Function<String, Quantity<?>> names) {
        return numeric(evaluateAny(expression, names));
    }

    private static final class Parser {
        final String input;
        final Function<String, Quantity<?>> names;
        final ParameterUnit unit;
        int index, tokens, depth;

        Parser(String input, Function<String, Quantity<?>> names, ParameterUnit unit) {
            if (input == null || input.isBlank() || input.length() > 512)
                throw new IllegalArgumentException("Expression must contain 1–512 characters");
            this.input = input;
            this.names = names;
            this.unit = unit;
            take('=');
        }

        Object parse() {
            var result = sum();
            skip();
            if (index != input.length()) throw error("Unexpected input");
            return result;
        }

        void skip() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++;
        }

        boolean take(char c) {
            skip();
            if (index < input.length() && input.charAt(index) == c) {
                index++;
                return true;
            }
            return false;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + (index + 1));
        }

        Object sum() {
            var q = product();
            while (true) {
                if (take('+')) {
                    var next = product();
                    if (q instanceof String || next instanceof String) {
                        q = format(q) + format(next);
                        if (((String) q).length() > 4096) throw error("Text result exceeds 4096 characters");
                    } else q = add(numeric(q), numeric(next), unit);
                } else if (take('-')) q = add(numeric(q), numeric(product()).negate(), unit);
                else return q;
            }
        }

        Object product() {
            var q = unary();
            while (true) {
                if (take('*')) q = times(numeric(q), numeric(unary()), false);
                else if (take('/')) q = times(numeric(q), numeric(unary()), true);
                else {
                    skip();
                    int begin = index;
                    String word = word();
                    var suffix = Arrays.stream(ParameterUnit.values())
                            .filter(u -> u.toString().equals(word))
                            .findFirst();
                    index = begin;
                    if (suffix.isPresent()) q = times(numeric(q), numeric(unary()), false);
                    else return q;
                }
            }
        }

        Object unary() {
            if (++depth > 32) throw error("Expression is too deeply nested");
            try {
                if (take('+')) return numeric(unary());
                if (take('-')) return numeric(unary()).negate();
                var q = atom();
                if (take('^')) {
                    var exponent = numeric(unary());
                    if (!scalar(exponent)) throw error("Exponent must be unitless");
                    double p = value(exponent, ONE);
                    var base = numeric(q);
                    if (!scalar(base) && (p != Math.rint(p) || Math.abs(p) > 8))
                        throw error("Dimensional powers must be integers between -8 and 8");
                    Unit<?> target = scalar(base) ? ONE : base.getUnit().pow((int) p);
                    q = normalized(quantity(Math.pow(number(base), p), target));
                }
                return q;
            } finally {
                depth--;
            }
        }

        private static boolean asciiLetter(char c) {
            return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
        }

        String word() {
            skip();
            int start = index;
            if (index < input.length() && asciiLetter(input.charAt(index))) {
                index++;
                while (index < input.length()
                        && (asciiLetter(input.charAt(index))
                                || input.charAt(index) == '_'
                                || input.charAt(index) == '.')) index++;
            }
            return input.substring(start, index);
        }

        Object atom() {
            if (++tokens > 256) throw error("Expression is too complex");
            if (take('(')) {
                var q = sum();
                if (!take(')')) throw error("Expected closing parenthesis");
                return q;
            }
            skip();
            if (index >= input.length()) throw error("Expected a value");
            if (take('"')) {
                var text = new StringBuilder();
                while (index < input.length()) {
                    char character = input.charAt(index++);
                    if (character != '"') text.append(character);
                    else if (index < input.length() && input.charAt(index) == '"') {
                        text.append('"');
                        index++;
                    } else return text.toString();
                }
                throw error("Expected closing quote");
            }
            if (take('$')) {
                String name = word();
                if (name.isEmpty()) throw error("Expected a parameter name after '$'");
                var q = names.apply(name);
                if (q == null) throw error("Unknown parameter '" + name + "'");
                return normalized(q);
            }
            char first = input.charAt(index);
            if (Character.isDigit(first) || first == '.') {
                int begin = index++;
                while (index < input.length() && (Character.isDigit(input.charAt(index)) || input.charAt(index) == '.'))
                    index++;
                if (index < input.length() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                    index++;
                    if (index < input.length() && (input.charAt(index) == '+' || input.charAt(index) == '-')) index++;
                    while (index < input.length() && Character.isDigit(input.charAt(index))) index++;
                }
                try {
                    return quantity(Double.parseDouble(input.substring(begin, index)), ONE);
                } catch (NumberFormatException failure) {
                    throw error("Invalid number");
                }
            }
            String name = word();
            if (name.isEmpty()) throw error("Expected a number or parameter name");
            if (name.equals("NaN") || name.equals("Infinity")) throw error("Expression must be finite");
            if (take('(')) {
                var arguments = new ArrayList<Quantity<?>>();
                arguments.add(numeric(sum()));
                while (take(',')) arguments.add(numeric(sum()));
                if (!take(')')) throw error("Expected closing parenthesis");
                return function(name, arguments);
            }
            if (name.equals("pi")) return quantity(Math.PI, ONE);
            if (name.equals("e")) return quantity(Math.E, ONE);
            for (var u : ParameterUnit.values()) if (u.toString().equals(name)) return normalized(u.quantity(1));
            var q = names.apply(name);
            if (q == null) throw error("Unknown parameter '" + name + "'");
            return normalized(q);
        }

        Quantity<?> function(String name, List<Quantity<?>> args) {
            if (!FUNCTIONS.contains(name)) throw error("Unknown function '" + name + "'");
            if (args.size() != (name.equals("min") || name.equals("max") ? 2 : 1))
                throw error("Wrong argument count for " + name);
            var q = args.getFirst();
            return switch (name) {
                case "min", "max" -> {
                    var b = args.get(1);
                    if (scalar(q) && !scalar(b)) q = implicit(q, b, unit);
                    if (scalar(b) && !scalar(q)) b = implicit(b, q, unit);
                    double other = value(b, q.getUnit());
                    yield quantity(
                            name.equals("min") ? Math.min(number(q), other) : Math.max(number(q), other), q.getUnit());
                }
                case "abs", "floor", "ceil", "round" ->
                    quantity(
                            switch (name) {
                                case "abs" -> Math.abs(number(q));
                                case "floor" -> Math.floor(number(q));
                                case "ceil" -> Math.ceil(number(q));
                                default -> Math.rint(number(q));
                            },
                            q.getUnit());
                case "sqrt" -> {
                    if (powers(q.getUnit()).values().stream().anyMatch(p -> p % 2 != 0))
                        throw error("Square root requires even unit powers");
                    yield normalized(quantity(Math.sqrt(number(q)), q.getUnit().root(2)));
                }
                case "sin", "cos", "tan" -> {
                    if (!(scalar(q) || sameKind(q.getUnit(), RADIAN))) throw error("Trigonometry needs an angle");
                    double radians = scalar(q) ? radians(value(q, ONE)) : value(q, RADIAN);
                    yield quantity(
                            name.equals("sin")
                                    ? Math.sin(radians)
                                    : name.equals("cos") ? Math.cos(radians) : Math.tan(radians),
                            ONE);
                }
                default -> {
                    if (!scalar(q)) throw error("Inverse trigonometry needs a unitless number");
                    yield normalized(quantity(
                            name.equals("asin")
                                    ? Math.asin(value(q, ONE))
                                    : name.equals("acos") ? Math.acos(value(q, ONE)) : Math.atan(value(q, ONE)),
                            RADIAN));
                }
            };
        }
    }
}
