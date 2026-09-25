/*
 * Deterministic assertions over process output: required / forbidden substrings, required
 * regexes, numeric checks on RESULT and SAMPLE lines, and STEP bookkeeping (every STEP FAIL must
 * be a declared expected failure; a declared expected failure that passes is itself a failure).
 */

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Checks {

	/** Outcome of evaluating a scenario's assertions. */
	public static class Outcome {

		public final List<String> failures = new ArrayList<>();

		public final List<String> passes = new ArrayList<>();

		public final List<String> expectedFailures = new ArrayList<>();

	}

	private static final Pattern STEP = Pattern.compile("^STEP (\\S+) (PASS|FAIL)\\b.*");

	public static void evaluate(Scenario.Assertions a, Map<String, List<String>> output, Outcome o) {
		a.requiredOutput().forEach((proc, wanted) -> {
			List<String> lines = linesOf(output, proc, o);
			for (String w : wanted) {
				record(o, lines.stream().anyMatch(l -> l.contains(w)), proc + " prints \"" + w + "\"");
			}
		});
		a.forbiddenOutput().forEach((proc, banned) -> {
			List<String> lines = linesOf(output, proc, o);
			for (String b : banned) {
				record(o, lines.stream().noneMatch(l -> l.contains(b)), proc + " never prints \"" + b + "\"");
			}
		});
		a.requiredPatterns().forEach((proc, patterns) -> {
			List<String> lines = linesOf(output, proc, o);
			for (String p : patterns) {
				Pattern re = Pattern.compile(p);
				record(o, lines.stream().anyMatch(l -> re.matcher(l).find()), proc + " matches /" + p + "/");
			}
		});
		for (String check : a.checks()) {
			evaluateCheck(check, output, o);
		}
		checkSteps(a.expectedFailures(), output, o);
	}

	private static void checkSteps(List<Scenario.ExpectedFailure> expected, Map<String, List<String>> output,
			Outcome o) {
		for (Map.Entry<String, List<String>> e : output.entrySet()) {
			for (String line : e.getValue()) {
				Matcher m = STEP.matcher(line);
				if (m.matches() && m.group(2).equals("FAIL")) {
					boolean declared = expected.stream()
						.anyMatch(x -> x.process().equals(e.getKey()) && x.step().equals(m.group(1)));
					if (!declared) {
						o.failures.add(e.getKey() + " unexpected " + abbreviate(line));
					}
				}
			}
		}
		for (Scenario.ExpectedFailure x : expected) {
			List<String> lines = output.getOrDefault(x.process(), List.of());
			String failLine = lines.stream().filter(l -> l.startsWith("STEP " + x.step() + " FAIL")).findFirst().orElse(null);
			boolean passed = lines.stream().anyMatch(l -> l.startsWith("STEP " + x.step() + " PASS"));
			if (passed) {
				o.failures.add(x.process() + " STEP " + x.step() + " was expected to FAIL (" + x.reason()
						+ ") but PASSED: the peer changed; update the expectation (see " + x.see() + ")");
			}
			else if (failLine == null) {
				o.failures.add(x.process() + " STEP " + x.step() + " (expected to FAIL) did not run");
			}
			else if (x.contains() != null && !failLine.contains(x.contains())) {
				o.failures.add(x.process() + " STEP " + x.step() + " failed, but not with \"" + x.contains()
						+ "\" as expected: " + abbreviate(failLine));
			}
			else {
				o.expectedFailures.add(x.process() + " STEP " + x.step() + " FAIL (expected: " + x.reason()
						+ "; see " + x.see() + ")");
			}
		}
	}

	private static void evaluateCheck(String check, Map<String, List<String>> output, Outcome o) {
		String[] t = check.trim().split("\\s+");
		try {
			String proc = t[0];
			Map<String, String> values;
			int i;
			if (t[1].equals("RESULT")) {
				values = resultValues(linesOf(output, proc, o));
				i = 2;
			}
			else if (t[1].equals("SAMPLE")) {
				values = sampleValues(linesOf(output, proc, o), t[2]);
				i = 3;
			}
			else {
				throw new IllegalArgumentException("expected RESULT or SAMPLE");
			}
			if (t.length != i + 3) {
				throw new IllegalArgumentException("expected '<lhs> <op> <rhs>'");
			}
			if (values.isEmpty()) {
				o.failures.add(check + "  [no " + String.join(" ", java.util.Arrays.copyOfRange(t, 1, i))
						+ " line from " + proc + "]");
				return;
			}
			String lhs = term(t[i], values), op = t[i + 1], rhs = term(t[i + 2], values);
			boolean ok = compare(lhs, op, rhs);
			String shown = check + "  [" + lhs + " " + op + " " + rhs + "]";
			record(o, ok, shown);
		}
		catch (MissingKey e) {
			o.failures.add(check + "  [no key '" + e.getMessage() + "']");
		}
		catch (RuntimeException e) {
			o.failures.add("bad check '" + check + "': " + e.getMessage());
		}
	}

	static class MissingKey extends RuntimeException {

		MissingKey(String key) {
			super(key);
		}

	}

	private static String term(String t, Map<String, String> values) {
		int star = t.indexOf('*');
		if (star > 0) {
			double a = number(t.substring(0, star), values), b = number(t.substring(star + 1), values);
			return format(a * b);
		}
		if (isNumber(t)) {
			return t;
		}
		String v = values.get(t);
		if (v == null) {
			throw new MissingKey(t);
		}
		return v;
	}

	private static double number(String t, Map<String, String> values) {
		String v = isNumber(t) ? t : values.get(t);
		if (v == null) {
			throw new MissingKey(t);
		}
		return Double.parseDouble(v);
	}

	private static boolean compare(String l, String op, String r) {
		if (isNumber(l) && isNumber(r)) {
			int c = Double.compare(Double.parseDouble(l), Double.parseDouble(r));
			return switch (op) {
				case "==" -> c == 0;
				case "!=" -> c != 0;
				case "<=" -> c <= 0;
				case ">=" -> c >= 0;
				case "<" -> c < 0;
				case ">" -> c > 0;
				default -> throw new IllegalArgumentException("unknown operator " + op);
			};
		}
		return switch (op) {
			case "==" -> l.equals(r);
			case "!=" -> !l.equals(r);
			default -> throw new IllegalArgumentException(op + " needs numbers: " + l + ", " + r);
		};
	}

	/** Keys of every RESULT line the process printed, later lines overriding earlier ones. */
	public static Map<String, String> resultValues(List<String> lines) {
		Map<String, String> v = new LinkedHashMap<>();
		for (String l : lines) {
			if (l.startsWith("RESULT ")) {
				v.putAll(pairs(l.substring(7)));
			}
		}
		return v;
	}

	/** Keys of the last "SAMPLE <label> ..." line. */
	public static Map<String, String> sampleValues(List<String> lines, String label) {
		Map<String, String> v = new LinkedHashMap<>();
		for (String l : lines) {
			if (l.startsWith("SAMPLE " + label + " ")) {
				v = pairs(l.substring(8 + label.length()));
			}
		}
		return v;
	}

	private static Map<String, String> pairs(String s) {
		Map<String, String> v = new LinkedHashMap<>();
		for (String tok : s.trim().split("\\s+")) {
			int eq = tok.indexOf('=');
			if (eq > 0) {
				v.put(tok.substring(0, eq), tok.substring(eq + 1));
			}
		}
		return v;
	}

	private static List<String> linesOf(Map<String, List<String>> output, String proc, Outcome o) {
		List<String> lines = output.get(proc);
		if (lines == null) {
			o.failures.add("no process named '" + proc + "'");
			return List.of();
		}
		return lines;
	}

	private static void record(Outcome o, boolean ok, String what) {
		(ok ? o.passes : o.failures).add(what);
	}

	private static boolean isNumber(String s) {
		return s.matches("-?\\d+(\\.\\d+)?");
	}

	private static String format(double d) {
		return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
	}

	private static String abbreviate(String s) {
		return s.length() > 240 ? s.substring(0, 240) + "..." : s;
	}

}
