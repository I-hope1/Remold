package remold.mixin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Fixtures for the synthetic-inner-class redefine gate suite.
 *
 * <p>These are real classes loaded by the test classloader (not hand-written bytecode stubs),
 * because {@code MixinInnerClassHotSwap.redefineSyntheticSafely} derives two of its gates from
 * reflection over the live class: {@code getDeclaredConstructors()} and
 * {@code MixinSyntheticSchema.loadedFields(live)}. A fixture that only exists as bytes would not
 * exercise those paths.</p>
 *
 * <p>The class is deliberately shaped like a Mixin-generated anonymous class: it carries captured
 * fields plus both a no-arg bridge constructor and a capture-taking constructor, so "drop a
 * constructor" / "drop a field" produce the same member shapes the JVM sees after a Mixin
 * re-weave shrinks an anonymous class's capture list.</p>
 */
final class MixinSyntheticGateFixtures {

	private MixinSyntheticGateFixtures() {
	}

	/**
	 * Real carrier for the gate cases: two capture-like fields, two constructors, a body method
	 * that reads the fields (so instance state can be asserted through behaviour, not reflection
	 * metadata).
	 */
	static final class Capturing {
		int value;
		Object payload;

		Capturing() {
			this(0, null);
		}

		Capturing(int value, Object payload) {
			this.value = value;
			this.payload = payload;
		}

		String describe() {
			return "v1:" + this.value + ":" + this.payload;
		}

		@Override
		public String toString() {
			return describe();
		}
	}

	/** Exact class-file bytes of a loaded fixture class. */
	static byte[] bytesOf(Class<?> type) {
		String resource = "/" + type.getName().replace('.', '/') + ".class";
		try (InputStream in = type.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("fixture bytecode must be on the test runtime classpath: " + resource);
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) >= 0) {
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("cannot read fixture bytecode " + resource, e);
		}
	}

	/** Fixture bytes with a synthetic-like extra member set intact (the "pass" candidate). */
	static byte[] candidateBytes(Class<?> type) {
		return bytesOf(type);
	}

	/**
	 * Fixture bytes with every {@code <init>} removed: models a re-weave that dropped an overload
	 * the already-loaded class still owns (old call sites would hit {@code NoSuchMethodError}).
	 */
	static byte[] withoutConstructors(Class<?> type) {
		ClassNode node = nodeOf(type);
		List<MethodNode> dropped = new ArrayList<>();
		for (MethodNode m : node.methods) {
			if ("<init>".equals(m.name)) {
				dropped.add(m);
			}
		}
		if (dropped.isEmpty()) {
			throw new IllegalStateException("fixture " + type.getName() + " has no constructor to drop");
		}
		node.methods.removeAll(dropped);
		return serialize(node);
	}

	/**
	 * Fixture bytes with one declared field removed: models a capture-list shrink, which is exactly
	 * the shape that makes a DCEVM/JBR redefine run {@code ModifyInstances} and re-lay out live
	 * objects.
	 */
	static byte[] withoutField(Class<?> type, String fieldName) {
		ClassNode node = nodeOf(type);
		FieldNode target = null;
		for (FieldNode f : node.fields) {
			if (fieldName.equals(f.name)) {
				target = f;
				break;
			}
		}
		if (target == null) {
			throw new IllegalStateException("fixture " + type.getName() + " has no field " + fieldName);
		}
		node.fields.remove(target);
		return serialize(node);
	}

	static ClassNode nodeOf(Class<?> type) {
		ClassNode node = new ClassNode();
		new ClassReader(bytesOf(type)).accept(node, 0);
		return node;
	}

	private static byte[] serialize(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
