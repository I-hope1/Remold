package remold;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓库级守卫：{@code Instrumentation.redefineClasses} 的<b>每一个</b>生产调用点都必须有入口门禁。
 *
 * <p><b>为什么需要它</b>：JBR + {@code AllowEnhancedClassRedefinition} 收到畸形
 * {@code ClassDefinition} 会直接终止进程（没有异常可捕获）。门禁只要漏挂一个入口，"批量门面上有
 * 检查"就毫无意义——旁路入口（VCS 回退、动态单类、profiler）才是真机最先炸的地方。
 * 本测试用文本扫描锁住"不再出现裸调用点"：新增调用点、或把某个已登记的调用点改成不经过门禁的
 * 形态（参数文本变了），都会让白名单对不上而变红。</p>
 *
 * <p><b>白名单的三种判决</b>：</p>
 * <ul>
 *   <li>{@code GATED_HERE}：该方法体内自己调用了门禁
 *       （{@link RedefineEntryGate#gate}/{@link RedefineEntryGate#gateOne}）；</li>
 *   <li>{@code GATED_BY_CALLER}：唯一调用方在门禁之后才把定义传进来
 *       （{@code TwoPhaseCoordinator} 只由 {@code RemoldAgent.applyTwoPhase} /
 *       {@code applyRedefinitions} 调用，两者先过门禁）；</li>
 *   <li>{@code VALID_BY_CONSTRUCTION}：字节由本进程内的 ASM {@code ClassWriter} 现造，结构确定性有效，
 *       且所在模块（{@code plugin-arc} / {@code plugin-mixin}）不在 core 的写域里。逐条登记理由，
 *       新增同类调用点仍然必须显式加进来。</li>
 * </ul>
 */
class RedefineEntryGateCoverageTest {

	private enum Verdict { GATED_HERE, GATED_BY_CALLER, VALID_BY_CONSTRUCTION }

	/** 白名单条目：文件 + 所在方法 + 调用参数文本 + 出现次数。 */
	private record Expected(String file, String method, String args, int count, Verdict verdict, String reason) { }

	private record Site(String file, String method, String args) { }

	private static final Pattern CALL = Pattern.compile("\\.redefineClasses\\s*\\(");

	private static final Set<String> NOT_A_METHOD_NAME = Set.of(
	 "if", "for", "while", "switch", "catch", "synchronized", "try", "do", "else", "return", "new",
	 "assert", "throw", "record");

	/** 已过门禁 / 已逐个判定过的调用点。任何一条对不上（多出来、少掉、参数文本变了）都算失败。 */
	private static final List<Expected> ALLOWLIST = List.of(
	 new Expected("core/src/remold/TwoPhaseCoordinator.java", "redefineStageA",
	  "definitions.toArray(new ClassDefinition[0])", 1, Verdict.GATED_BY_CALLER,
	  "only reachable through RemoldAgent.applyTwoPhase, which gates the batch before delegating"),
	 new Expected("core/src/remold/TwoPhaseCoordinator.java", "redefineStageA", "def", 1,
	  Verdict.GATED_BY_CALLER,
	  "single-class fallback inside the same gated batch (stage A)"),
	 new Expected("core/src/remold/TwoPhaseCoordinator.java", "applyRedefinitions",
	  "definitions.toArray(new ClassDefinition[0])", 1, Verdict.GATED_BY_CALLER,
	  "only reachable through RemoldAgent.applyRedefinitions, which gates the batch before delegating"),
	 new Expected("core/src/remold/TwoPhaseCoordinator.java", "applyRedefinitions", "def", 1,
	  Verdict.GATED_BY_CALLER,
	  "single-class fallback inside the same gated batch (stage C / single phase)"),
	 new Expected("core/src/remold/DynamicRedefineExecutor.java", "redefineDynamicApplied",
	  "stageADefinition", 1, Verdict.GATED_HERE,
	  "stage A definition produced by RedefineEntryGate.gateOne before any redefine runs"),
	 new Expected("core/src/remold/DynamicRedefineExecutor.java", "redefineDynamicApplied",
	  "finalDefinition", 2, Verdict.GATED_HERE,
	  "stage C / single-phase definition produced by RedefineEntryGate.gateOne before any redefine runs"),
	 new Expected("core/src/remold/DynamicRedefineExecutor.java", "redefineDynamicApplied",
	  "rollbackDefinition", 1, Verdict.GATED_HERE,
	  "best-effort rollback bytes also pass the gate; a rejected baseline is reported, never sent"),
	 new Expected("core/src/remold/VcsAgentCoordinator.java", "revertTo",
	  "gated.get(scope.scope).toArray(new ClassDefinition[0])", 1, Verdict.GATED_HERE,
	  "the whole revert is gated in phase 1 and aborted when anything is refused (no half revert)"),
	 new Expected("core/src/remold/VcsAgentCoordinator.java", "restorePreRevertBytes",
	  "safeBack.toArray(new ClassDefinition[0])", 1, Verdict.GATED_HERE,
	  "best-effort restore of the pre-revert bytes also passes the gate"),
	 new Expected("core/src/remold/profiler/DynamicProfilerAPI.java", "loadWalker", "definition", 1,
	  Verdict.GATED_HERE,
	  "profiler redefine is gated through the public RedefineEntryGate.gateOne"),
	 new Expected("plugin-arc/src/remold/Injector.java", "redefineOneClass",
	  "new ClassDefinition(theClass, bytes)", 1, Verdict.VALID_BY_CONSTRUCTION,
	  "bytes are produced in-process by ASM ClassWriter from the live class; plugin-arc is outside the"
	   + " core write scope of this batch"),
	 new Expected("plugin-arc/src/remold/Injector.java", "batchProcess",
	  "tasks.toArray(new ClassDefinition[0])", 1, Verdict.VALID_BY_CONSTRUCTION,
	  "same ASM-built bytes for every task in the batch; plugin-arc is outside this batch's write scope"),
	 new Expected("plugin-mixin/src/remold/mixin/MixinInnerClassHotSwap.java",
	  "redefineSyntheticSafely", "new ClassDefinition(live, bytes)", 1,
	  Verdict.VALID_BY_CONSTRUCTION,
	  "bytes are the Mixin node's own ClassWriter output, already filtered by gates 1-4;"
	   + " plugin-mixin is outside this batch's write scope"));

	@Test
	void everyProductionRedefineCallSiteIsGated() throws Exception {
		Path root = repositoryRoot();
		Map<Site, Integer> found = scan(root);
		Map<Site, Expected> expected = new LinkedHashMap<>();
		for (Expected e : ALLOWLIST) {
			Site key = new Site(e.file(), e.method(), e.args());
			Expected previous = expected.put(key, e);
			assertTrue(previous == null, "duplicate allowlist entry: " + key);
		}

		// 前置基线：扫描器必须真的扫到了调用点。扫不到时"没有未登记调用点"是假阳性。
		assertTrue(found.size() >= 10,
		 "the scanner must actually see the production call sites (found " + found.size() + "): " + found.keySet());

		List<String> unexpected = new ArrayList<>();
		for (Map.Entry<Site, Integer> entry : found.entrySet()) {
			Expected allow = expected.get(entry.getKey());
			if (allow == null) {
				unexpected.add(entry.getKey() + " x" + entry.getValue()
				 + " — either gate it through RedefineEntryGate or add it to the allowlist with a verdict");
			} else if (allow.count() != entry.getValue()) {
				unexpected.add(entry.getKey() + " occurs " + entry.getValue() + " time(s), allowlist says "
				 + allow.count());
			}
		}
		assertTrue(unexpected.isEmpty(),
		 "unexpected redefineClasses call site(s) — every production call site must be adjudicated in the"
			 + " allowlist:\n  " + String.join("\n  ", unexpected) + "\nfound=" + found.keySet());

		List<String> stale = new ArrayList<>();
		for (Expected allow : ALLOWLIST) {
			if (!found.containsKey(new Site(allow.file(), allow.method(), allow.args()))) {
				stale.add(allow.file() + "#" + allow.method() + "(" + allow.args() + ")");
			}
		}
		assertTrue(stale.isEmpty(), "stale allowlist entries (the call site moved or lost its gate): " + stale);

		assertGatedHereReallyCallsTheGate(root);
		assertBatchFacadesGateBeforeDelegating(root);
	}

	/** {@code GATED_HERE} 的条目必须在同一个方法体里调用共享门禁，否则"已过门禁"就只是注释里的说法。 */
	private static void assertGatedHereReallyCallsTheGate(Path root) throws Exception {
		Map<String, Set<String>> gatedMethods = new TreeMap<>();
		for (Expected allow : ALLOWLIST) {
			if (allow.verdict() != Verdict.GATED_HERE) continue;
			gatedMethods.computeIfAbsent(allow.file(), ignored -> new LinkedHashSet<>()).add(allow.method());
		}
		assertEquals(3, gatedMethods.size(), "three production files must own their gate call: " + gatedMethods);
		for (Map.Entry<String, Set<String>> entry : gatedMethods.entrySet()) {
			String code = stripCommentsAndLiterals(Files.readString(root.resolve(entry.getKey()),
			 StandardCharsets.UTF_8));
			for (String method : entry.getValue()) {
				int start = methodBodyStart(code, method, entry.getKey());
				int end = matchingBrace(code, start);
				String body = code.substring(start, end);
				assertTrue(body.contains("RedefineEntryGate.gate(") || body.contains("RedefineEntryGate.gateOne("),
				 entry.getKey() + "#" + method + " is declared as gated but never calls RedefineEntryGate");
				// 每个 redefineClasses 调用点都要"吃到"门禁产物：只要求"方法体里出现过门禁"会漏掉
				// "stage A 的门禁被拆掉、但同方法里 stage C 的门禁还在"这种半裸形态。
				assertEveryRedefineArgumentIsGated(body, entry.getKey() + "#" + method);
			}
		}
	}

	/**
	 * 逐个 {@code redefineClasses} 实参核验它确实是门禁产物。
	 *
	 * <p>两种合法形态：① 实参本身带 {@code RedefineEntryGate}（就地门禁）；② 实参是某个标识符，
	 * 而同一方法体内有 {@code 标识符 = RedefineEntryGate.gate/gateOne(...)} 的赋值（门禁产物被复用，
	 * 例如 stage A 与 stage C 共用同一个 {@code finalDefinition}）。</p>
	 */
	private static void assertEveryRedefineArgumentIsGated(String body, String where) {
		Matcher m = Pattern.compile("redefineClasses\\s*\\(").matcher(body);
		while (m.find()) {
			int open = body.indexOf('(', m.start());
			int close = matchingParen(body, open);
			String argument = body.substring(open + 1, close).trim();
			if (argument.contains("RedefineEntryGate")) continue;
			Matcher id = Pattern.compile("^([A-Za-z_$][A-Za-z0-9_$]*)").matcher(argument);
			assertTrue(id.find(),
			 where + " passes a redefine argument that is neither a gate call nor a named gate result: " + argument);
			String name = id.group(1);
			// 门禁产物可以整体赋值（{@code X = gate(...)}），也可以先塞进容器
			// （{@code m.put(k, RedefineEntryGate.gate(...))}）再由 {@code X.get(k)} 取回；
			// 后者要求容器里至少有一处门禁写入，否则"取回来的东西"可能根本不是门禁产物。
			boolean producedByGate = Pattern.compile(
			 "\\b" + Pattern.quote(name) + "\\s*=\\s*RedefineEntryGate\\.gate(One)?\\("
			).matcher(body).find()
			 || Pattern.compile(
			 "\\.(put|add|addAll|putAll)\\s*\\([^;]*RedefineEntryGate\\.gate(One)?\\("
			).matcher(body).find();
			assertTrue(producedByGate,
			 where + " redefines with '" + argument + "' but '" + name
			  + "' is never assigned from RedefineEntryGate in that method: every redefine site needs its own gate");
		}
	}

	private static int methodBodyStart(String code, String method, String file) {
		Matcher m = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(").matcher(code);
		while (m.find()) {
			int open = code.indexOf('(', m.start());
			int close = matchingParen(code, open);
			// 声明头里可能有 `throws ...`：一路走到方法体的 `{`（遇到 `;` 说明是抽象/接口方法，跳过）。
			int brace = close + 1;
			while (brace < code.length() && code.charAt(brace) != '{' && code.charAt(brace) != ';') brace++;
			if (brace < code.length() && code.charAt(brace) == '{') return brace;
		}
		throw new AssertionError("method body not found: " + file + "#" + method);
	}

	private static int matchingParen(String code, int openParen) {
		int depth = 0;
		for (int i = openParen; i < code.length(); i++) {
			char c = code.charAt(i);
			if (c == '(') depth++;
			else if (c == ')' && --depth == 0) return i;
		}
		throw new AssertionError("unbalanced parentheses at " + openParen);
	}

	private static int matchingBrace(String code, int openBrace) {
		int depth = 0;
		for (int i = openBrace; i < code.length(); i++) {
			char c = code.charAt(i);
			if (c == '{') depth++;
			else if (c == '}' && --depth == 0) return i;
		}
		throw new AssertionError("unbalanced braces at " + openBrace);
	}

	/** 批次门面必须"先过门禁再委派"，否则 TwoPhaseCoordinator 的 GATED_BY_CALLER 判决不成立。 */
	private static void assertBatchFacadesGateBeforeDelegating(Path root) throws Exception {
		String agent = stripCommentsAndLiterals(
		 Files.readString(root.resolve("core/src/remold/RemoldAgent.java"), StandardCharsets.UTF_8))
		 .replaceAll("\\s+", " ");
		assertTrue(agent.contains("TwoPhaseCoordinator.applyTwoPhase(RedefineEntryGate.gate("),
		 "RemoldAgent.applyTwoPhase must gate the batch before delegating to TwoPhaseCoordinator");
		assertTrue(agent.contains("TwoPhaseCoordinator.applyRedefinitions(RedefineEntryGate.gate("),
		 "RemoldAgent.applyRedefinitions must gate the batch before delegating to TwoPhaseCoordinator");
	}

	// ------------------------------------------------------------------ 文本扫描

	private static Map<Site, Integer> scan(Path root) throws IOException {
		List<Path> sources = new ArrayList<>();
		try (Stream<Path> dirs = Files.list(root)) {
			for (Path dir : dirs.filter(Files::isDirectory).toList()) {
				String name = dir.getFileName().toString();
				if (!name.equals("core") && !name.startsWith("plugin-")) continue;
				Path src = dir.resolve("src");
				if (!Files.isDirectory(src)) continue;
				try (Stream<Path> walk = Files.walk(src)) {
					walk.filter(Files::isRegularFile)
					 .filter(path -> path.toString().endsWith(".java"))
					 .sorted()
					 .forEach(sources::add);
				}
			}
		}
		assertTrue(!sources.isEmpty(), "no production sources found under " + root);

		Map<Site, Integer> found = new LinkedHashMap<>();
		for (Path source : sources) {
			String file = root.relativize(source).toString().replace('\\', '/');
			String code = stripCommentsAndLiterals(Files.readString(source, StandardCharsets.UTF_8));
			Matcher m = CALL.matcher(code);
			while (m.find()) {
				int open = code.indexOf('(', m.start());
				int close = matchingParen(code, open);
				String args = collapse(code.substring(open + 1, close));
				Site site = new Site(file, enclosingMethod(code, m.start()), args);
				found.merge(site, 1, Integer::sum);
			}
		}
		return found;
	}

	/** 注释 / 字符串 / 字符字面量全部抹成空格，保证 Javadoc 里的 {@code redefineClasses} 不算调用点。 */
	static String stripCommentsAndLiterals(String source) {
		char[] out = source.toCharArray();
		int i = 0;
		int n = out.length;
		while (i < n) {
			char c = out[i];
			if (c == '/' && i + 1 < n && out[i + 1] == '/') {
				while (i < n && out[i] != '\n') out[i++] = ' ';
			} else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
				out[i++] = ' ';
				out[i++] = ' ';
				while (i < n) {
					if (out[i] == '*' && i + 1 < n && out[i + 1] == '/') {
						out[i++] = ' ';
						out[i++] = ' ';
						break;
					}
					if (out[i] != '\n') out[i] = ' ';
					i++;
				}
			} else if (c == '"') {
				boolean textBlock = i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"';
				out[i++] = ' ';
				if (textBlock) {
					out[i++] = ' ';
					out[i++] = ' ';
				}
				while (i < n) {
					char d = out[i];
					if (d == '\\') {
						out[i++] = ' ';
						if (i < n && out[i] != '\n') out[i++] = ' ';
						continue;
					}
					if (textBlock) {
						if (d == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"') {
							out[i++] = ' ';
							out[i++] = ' ';
							out[i++] = ' ';
							break;
						}
						if (d != '\n') out[i] = ' ';
						i++;
						continue;
					}
					out[i++] = ' ';
					if (d == '"' || d == '\n') break;   // 单行字符串（未闭合时按行尾收敛）
				}
			} else if (c == '\'') {
				out[i++] = ' ';
				while (i < n) {
					char d = out[i];
					if (d == '\\') {
						out[i++] = ' ';
						if (i < n) out[i++] = ' ';
						continue;
					}
					out[i++] = ' ';
					if (d == '\'' || d == '\n') break;
				}
			} else {
				i++;
			}
		}
		return new String(out);
	}

	/** 调用点所在的<b>最内层声明名</b>（方法 / 构造器）；落在字段初始化或静态块里则回 {@code <initializer>}。 */
	static String enclosingMethod(String code, int position) {
		Deque<String> scopes = new ArrayDeque<>();
		for (int i = 0; i < position; i++) {
			char c = code.charAt(i);
			if (c == '{') {
				String name = declarationName(code, i);
				scopes.push(name);
			} else if (c == '}' && !scopes.isEmpty()) {
				scopes.pop();
			}
		}
		for (String name : scopes) {
			if (!name.isEmpty()) return name;
		}
		return "<initializer>";
	}

	/** 该 {@code {} 左括号"前面紧邻的声明名"；控制流块 / 匿名类 / 数组初始化返回空串。 */
	private static String declarationName(String code, int braceIndex) {
		// 声明头里可能夹着 `throws ...`，所以不能只看紧邻 `{` 的那个字符：先退到本语句的边界
		// （`;` / `{` / `}`），再在那段里找最后一个 `)`。控制流块与匿名类的判定交给下面的名字过滤。
		int boundary = braceIndex - 1;
		while (boundary >= 0 && code.charAt(boundary) != ';'
		    && code.charAt(boundary) != '{' && code.charAt(boundary) != '}') {
			boundary--;
		}
		int j = braceIndex - 1;
		while (j > boundary && code.charAt(j) != ')') j--;
		if (j <= boundary) return "";
		int open = -1;
		int depth = 0;
		for (int k = j; k >= 0; k--) {
			char c = code.charAt(k);
			if (c == ')') depth++;
			else if (c == '(' && --depth == 0) {
				open = k;
				break;
			}
		}
		if (open <= 0) return "";
		int nameEnd = open;
		int nameStart = nameEnd;
		while (nameStart > 0 && Character.isJavaIdentifierPart(code.charAt(nameStart - 1))) nameStart--;
		String name = code.substring(nameStart, nameEnd);
		if (name.isEmpty() || NOT_A_METHOD_NAME.contains(name)) return "";
		int p = nameStart - 1;
		while (p >= 0 && Character.isWhitespace(code.charAt(p))) p--;
		int wordEnd = p + 1;
		int wordStart = wordEnd;
		while (wordStart > 0 && Character.isJavaIdentifierPart(code.charAt(wordStart - 1))) wordStart--;
		String preceding = code.substring(wordStart, wordEnd);
		if ("new".equals(preceding) || "record".equals(preceding)) return "";
		return name;
	}

	private static String collapse(String text) {
		return text.replaceAll("\\s+", " ").trim();
	}

	private static Path repositoryRoot() {
		Path current = Path.of("").toAbsolutePath();
		for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
			if (Files.isRegularFile(candidate.resolve("settings.gradle"))
			 && Files.isDirectory(candidate.resolve("core/src"))) {
				return candidate;
			}
		}
		throw new IllegalStateException("unable to locate the repository root above " + current);
	}
}
