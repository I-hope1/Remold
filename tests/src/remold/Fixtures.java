package remold;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * 内存 javac 夹具编译器（批次 J 的注解默认值测试共用）。
 *
 * <p>测试必须拿真实 {@code javac} 产出的类字节：注解默认值写在 {@code AnnotationDefault}
 * 方法属性里，手写 ASM 结点会绕过这条路径，测不到真正要比对的东西。</p>
 */
final class Fixtures {

	private Fixtures() {
	}

	/** 内存编译一组源码，返回「二进制名 → class 字节」。 */
	static Map<String, byte[]> compile(Map<String, String> sources) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new IllegalStateException("the test JVM has no system Java compiler");
		}
		Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
		StandardJavaFileManager standard =
			compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
		JavaFileManager memory = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
			@Override
			public JavaFileObject getJavaFileForOutput(Location location, String className,
					JavaFileObject.Kind kind, FileObject sibling) {
				ByteArrayOutputStream buffer = new ByteArrayOutputStream();
				buffers.put(className, buffer);
				URI uri = URI.create("mem:///" + className.replace('.', '/') + kind.extension);
				return new SimpleJavaFileObject(uri, kind) {
					@Override
					public OutputStream openOutputStream() {
						return buffer;
					}
				};
			}
		};
		List<JavaFileObject> units = new ArrayList<>();
		for (Map.Entry<String, String> entry : sources.entrySet()) {
			URI uri = URI.create("string:///" + entry.getKey().replace('.', '/') + ".java");
			units.add(new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
				@Override
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
					return entry.getValue();
				}
			});
		}
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		StringWriter out = new StringWriter();
		boolean ok = Boolean.TRUE.equals(compiler.getTask(out, memory, diagnostics,
			List.of("-proc:none", "-nowarn", "-g:none"), null, units).call());
		if (!ok) {
			StringBuilder message = new StringBuilder("fixture compilation failed:");
			for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
				message.append("\n  ").append(diagnostic.getKind()).append(' ')
					.append(diagnostic.getMessage(null));
			}
			throw new IllegalStateException(message.toString());
		}
		Map<String, byte[]> output = new LinkedHashMap<>();
		for (Map.Entry<String, ByteArrayOutputStream> entry : buffers.entrySet()) {
			output.put(entry.getKey(), entry.getValue().toByteArray());
		}
		return output;
	}
}
