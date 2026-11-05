package dev.voxelbrew.engine.parameters;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Bounded arithmetic parser. No scripting engine, reflection or external evaluation. */
public final class Expressions {
    private Expressions() {}

    private static final Set<String> FUNCTIONS =
            Set.of(
                    "abs", "sqrt", "min", "max", "sin", "cos", "tan", "asin", "acos", "atan",
                    "floor", "ceil", "round");
    private static final Pattern TOKEN =
            Pattern.compile("(?<![A-Za-z_0-9.])[A-Za-z_][A-Za-z_0-9]*");

    public record Quantity(double value, int length, int angle) {
        public Quantity {
            if (!Double.isFinite(value) || Math.abs(length) > 8 || Math.abs(angle) > 8)
                throw new IllegalArgumentException(
                        "Expression is nonfinite or has unsupported dimensions");
        }

        boolean scalar() {
            return length == 0 && angle == 0;
        }

        boolean same(Quantity b) {
            return length == b.length && angle == b.angle;
        }

        Quantity times(Quantity b, boolean divide) {
            if (divide && b.value == 0) throw new IllegalArgumentException("Division by zero");
            return new Quantity(
                    divide ? value / b.value : value * b.value,
                    length + (divide ? -b.length : b.length),
                    angle + (divide ? -b.angle : b.angle));
        }

        Quantity negated() {
            return new Quantity(-value, length, angle);
        }

        Quantity implicit(Quantity dimensional, ParameterUnit unit) {
            double scale =
                    unit.length() == dimensional.length && unit.angle() == dimensional.angle
                            ? unit.scale()
                            : 1;
            return new Quantity(value * scale, dimensional.length, dimensional.angle);
        }

        Quantity add(Quantity b, ParameterUnit unit) {
            Quantity a = this;
            if (a.scalar() && !b.scalar()) a = a.implicit(b, unit);
            if (b.scalar() && !a.scalar()) b = b.implicit(a, unit);
            if (!a.same(b)) throw new IllegalArgumentException("Cannot add incompatible units");
            return new Quantity(a.value + b.value, a.length, a.angle);
        }
    }

    public static boolean reserved(String name) {
        return FUNCTIONS.contains(name)
                || name.equals("pi")
                || name.equals("e")
                || name.equals("NaN")
                || name.equals("Infinity")
                || Arrays.stream(ParameterUnit.values()).anyMatch(u -> u.toString().equals(name));
    }

    public static String rename(String expression, String oldName, String newName) {
        return TOKEN.matcher(expression)
                .replaceAll(m -> m.group().equals(oldName) ? newName : m.group());
    }

    public static Quantity evaluate(
            String expression, ParameterUnit unit, Function<String, Quantity> names) {
        var value = new Parser(expression, names, unit).parse();
        if (value.scalar())
            return new Quantity(value.value * unit.scale(), unit.length(), unit.angle());
        if (value.length != unit.length() || value.angle != unit.angle())
            throw new IllegalArgumentException(
                    "Expression has incompatible units; expected " + unit);
        return value;
    }

    public static Quantity evaluateRaw(String expression, Function<String, Quantity> names) {
        return new Parser(expression, names, ParameterUnit.MM).parse();
    }

    private static final class Parser {
        final String input;
        final Function<String, Quantity> names;
        final ParameterUnit unit;
        int index, tokens, depth;

        Parser(String input, Function<String, Quantity> names, ParameterUnit unit) {
            if (input == null || input.isBlank() || input.length() > 512)
                throw new IllegalArgumentException("Expression must contain 1–512 characters");
            this.input = input;
            this.names = names;
            this.unit = unit;
        }

        Quantity parse() {
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

        Quantity sum() {
            var q = product();
            while (true) {
                if (take('+')) q = q.add(product(), unit);
                else if (take('-')) q = q.add(product().negated(), unit);
                else return q;
            }
        }

        Quantity product() {
            var q = unary();
            while (true) {
                if (take('*')) q = q.times(unary(), false);
                else if (take('/')) q = q.times(unary(), true);
                else {
                    skip();
                    int begin = index;
                    String word = word();
                    boolean unit =
                            Arrays.stream(ParameterUnit.values())
                                    .anyMatch(u -> u.toString().equals(word));
                    index = begin;
                    if (unit) q = q.times(unary(), false);
                    else return q;
                }
            }
        }

        Quantity unary() {
            if (++depth > 32) throw error("Expression is too deeply nested");
            try {
                if (take('+')) return unary();
                if (take('-')) return unary().negated();
                var q = atom();
                if (take('^')) {
                    var exponent = unary();
                    if (!exponent.scalar()) throw error("Exponent must be unitless");
                    double p = exponent.value;
                    if (!q.scalar() && (p != Math.rint(p) || Math.abs(p) > 8))
                        throw error("Dimensional powers must be integers between -8 and 8");
                    q =
                            new Quantity(
                                    Math.pow(q.value, p),
                                    (int) (q.length * p),
                                    (int) (q.angle * p));
                }
                return q;
            } finally {
                depth--;
            }
        }

        String word() {
            skip();
            int start = index;
            if (index < input.length()
                    && (Character.isLetter(input.charAt(index)) || input.charAt(index) == '_')) {
                index++;
                while (index < input.length()
                        && (Character.isLetterOrDigit(input.charAt(index))
                                || input.charAt(index) == '_')) index++;
            }
            return input.substring(start, index);
        }

        Quantity atom() {
            if (++tokens > 256) throw error("Expression is too complex");
            if (take('(')) {
                var q = sum();
                if (!take(')')) throw error("Expected closing parenthesis");
                return q;
            }
            skip();
            if (index >= input.length()) throw error("Expected a value");
            char first = input.charAt(index);
            if (Character.isDigit(first) || first == '.') {
                int begin = index++;
                while (index < input.length()
                        && (Character.isDigit(input.charAt(index)) || input.charAt(index) == '.'))
                    index++;
                if (index < input.length()
                        && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                    index++;
                    if (index < input.length()
                            && (input.charAt(index) == '+' || input.charAt(index) == '-')) index++;
                    while (index < input.length() && Character.isDigit(input.charAt(index)))
                        index++;
                }
                try {
                    return new Quantity(Double.parseDouble(input.substring(begin, index)), 0, 0);
                } catch (NumberFormatException failure) {
                    throw error("Invalid number");
                }
            }
            String name = word();
            if (name.isEmpty()) throw error("Expected a number or parameter name");
            if (name.equals("NaN") || name.equals("Infinity"))
                throw error("Expression must be finite");
            if (take('(')) {
                var arguments = new ArrayList<Quantity>();
                arguments.add(sum());
                while (take(',')) arguments.add(sum());
                if (!take(')')) throw error("Expected closing parenthesis");
                return function(name, arguments);
            }
            if (name.equals("pi")) return new Quantity(Math.PI, 0, 0);
            if (name.equals("e")) return new Quantity(Math.E, 0, 0);
            for (var u : ParameterUnit.values())
                if (u.toString().equals(name))
                    return new Quantity(u.scale(), u.length(), u.angle());
            var q = names.apply(name);
            if (q == null) throw error("Unknown parameter '" + name + "'");
            return q;
        }

        Quantity function(String name, List<Quantity> args) {
            if (!FUNCTIONS.contains(name)) throw error("Unknown function '" + name + "'");
            if (args.size() != (name.equals("min") || name.equals("max") ? 2 : 1))
                throw error("Wrong argument count for " + name);
            var q = args.getFirst();
            return switch (name) {
                case "min", "max" -> {
                    var b = args.get(1);
                    if (q.scalar() && !b.scalar()) q = q.implicit(b, unit);
                    if (b.scalar() && !q.scalar()) b = b.implicit(q, unit);
                    if (!q.same(b)) throw error("Function arguments have incompatible units");
                    yield new Quantity(
                            name.equals("min")
                                    ? Math.min(q.value, b.value)
                                    : Math.max(q.value, b.value),
                            q.length,
                            q.angle);
                }
                case "abs", "floor", "ceil", "round" ->
                        new Quantity(
                                switch (name) {
                                    case "abs" -> Math.abs(q.value);
                                    case "floor" -> Math.floor(q.value);
                                    case "ceil" -> Math.ceil(q.value);
                                    default -> Math.rint(q.value);
                                },
                                q.length,
                                q.angle);
                case "sqrt" -> {
                    if (q.length % 2 != 0 || q.angle % 2 != 0)
                        throw error("Square root requires even unit powers");
                    yield new Quantity(Math.sqrt(q.value), q.length / 2, q.angle / 2);
                }
                case "sin", "cos", "tan" -> {
                    if (!(q.scalar() || q.length == 0 && q.angle == 1))
                        throw error("Trigonometry needs an angle");
                    double radians = Math.toRadians(q.value);
                    yield new Quantity(
                            name.equals("sin")
                                    ? Math.sin(radians)
                                    : name.equals("cos") ? Math.cos(radians) : Math.tan(radians),
                            0,
                            0);
                }
                default -> {
                    if (!q.scalar()) throw error("Inverse trigonometry needs a unitless number");
                    yield new Quantity(
                            Math.toDegrees(
                                    name.equals("asin")
                                            ? Math.asin(q.value)
                                            : name.equals("acos")
                                                    ? Math.acos(q.value)
                                                    : Math.atan(q.value)),
                            0,
                            1);
                }
            };
        }
    }
}
