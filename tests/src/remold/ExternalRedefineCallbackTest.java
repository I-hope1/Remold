package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import remold.probe.ExternalCallbackFixture;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * External redefines must only schedule deferred post-redefine callbacks when the incoming
 * bytes represent a real change. IDE hotswap / external agents re-send unchanged bytes on
 * retransform, and those events previously fired {@code @OnReload} for classes the watcher
 * batch had voted "Unchanged".
 */
class ExternalRedefineCallbackTest {

	private static final String SLASH_NAME = "remold/probe/ExternalCallbackFixture";

	private final List<Runnable> dispatched = Collections.synchronizedList(new ArrayList<>());
	private byte[] fixtureBytes;
	private String className;

	@BeforeEach
	void setUp() throws Exception {
		className = ExternalCallbackFixture.class.getName();
		fixtureBytes = readFixture();
		RemoldAgent.bytecodeCache.remove(className);
		RemoldAgent.sourceBytecodeCache.remove(className);
		dispatched.clear();
		RemoldAgent.setMainThreadDispatcher(task -> {
			if (task != null) dispatched.add(task);
		});
	}

	@AfterEach
	void tearDown() {
		RemoldAgent.setMainThreadDispatcher(null);
		RemoldAgent.bytecodeCache.remove(className);
		RemoldAgent.sourceBytecodeCache.remove(className);
		dispatched.clear();
	}

	@Test
	void identicalToSourceBaselineDoesNotSchedule() throws Exception {
		RemoldAgent.sourceBytecodeCache.put(className, fixtureBytes.clone());
		RemoldAgent.bytecodeCache.put(className, fixtureBytes.clone());

		assertTrue(dispatched.isEmpty(), "precondition: dispatcher must start empty");
		long baselineBefore = RemoldAgent.recordedDiskBaseline(className);

		new AnnotationTransformer().transform(fixtureLoader(), SLASH_NAME,
			ExternalCallbackFixture.class, null, fixtureBytes.clone());

		assertEquals(0, settleCount(250),
			"no-op external redefine (bytes == source baseline) must not fire @OnReload");
		assertEquals(baselineBefore, RemoldAgent.recordedDiskBaseline(className),
			"no-op external redefine must not advance the recorded disk baseline");
	}

	@Test
	void identicalToRuntimeBaselineDoesNotSchedule() throws Exception {
		byte[] source = Arrays.copyOf(fixtureBytes, fixtureBytes.length + 1);
		RemoldAgent.sourceBytecodeCache.put(className, source);
		RemoldAgent.bytecodeCache.put(className, fixtureBytes.clone());

		assertTrue(dispatched.isEmpty(), "precondition: dispatcher must start empty");
		long baselineBefore = RemoldAgent.recordedDiskBaseline(className);

		new AnnotationTransformer().transform(fixtureLoader(), SLASH_NAME,
			ExternalCallbackFixture.class, null, fixtureBytes.clone());

		assertEquals(0, settleCount(250),
			"no-op external redefine (bytes == runtime baseline) must not fire @OnReload");
		assertEquals(baselineBefore, RemoldAgent.recordedDiskBaseline(className),
			"no-op external redefine must not advance the recorded disk baseline");
	}

	@Test
	void realChangeSchedulesExactlyOnceAndLaterIdenticalBytesAreIgnored() throws Exception {
		RemoldAgent.sourceBytecodeCache.put(className, fixtureBytes.clone());
		RemoldAgent.bytecodeCache.put(className, fixtureBytes.clone());

		byte[] changed = Arrays.copyOf(fixtureBytes, fixtureBytes.length + 1);

		assertTrue(dispatched.isEmpty(), "precondition: dispatcher must start empty");
		long baselineBefore = RemoldAgent.recordedDiskBaseline(className);

		new AnnotationTransformer().transform(fixtureLoader(), SLASH_NAME,
			ExternalCallbackFixture.class, null, changed.clone());

		assertEquals(1, settleCount(500),
			"a real external change must fire @OnReload exactly once");
		assertNotEquals(baselineBefore, RemoldAgent.recordedDiskBaseline(className),
			"a real external change must record the applied bytes so the watcher skips a duplicate round");

		new AnnotationTransformer().transform(fixtureLoader(), SLASH_NAME,
			ExternalCallbackFixture.class, null, changed.clone());

		assertEquals(1, settleCount(250),
			"re-sending the already applied bytes must not fire @OnReload again");
	}

	@Test
	void baselineCaptureRetransformUpdatesCachesWithoutCallback() throws Exception {
		RemoldAgent.sourceBytecodeCache.put(className, fixtureBytes.clone());
		RemoldAgent.bytecodeCache.put(className, fixtureBytes.clone());

		byte[] changed = Arrays.copyOf(fixtureBytes, fixtureBytes.length + 1);
		long baselineBefore = RemoldAgent.recordedDiskBaseline(className);

		assertTrue(dispatched.isEmpty(), "precondition: dispatcher must start empty");

		RemoldAgent.enterBaselineCapture();
		try {
			new AnnotationTransformer().transform(fixtureLoader(), SLASH_NAME,
				ExternalCallbackFixture.class, null, changed.clone());
		} finally {
			RemoldAgent.exitInternalRedefine();
		}

		assertEquals(0, settleCount(250),
			"a caller-driven bulk retransform must not fire @OnReload");
		assertArrayEquals(changed, RemoldAgent.bytecodeCache.get(className),
			"baseline capture must still refresh the runtime bytecode baseline");
		assertEquals(baselineBefore, RemoldAgent.recordedDiskBaseline(className),
			"baseline capture must not advance the recorded disk baseline");
	}

	private int settleCount(long timeoutMs) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		while (System.nanoTime() < deadline && dispatched.size() < 2) {
			Thread.sleep(10);
		}
		Thread.sleep(150);
		return dispatched.size();
	}

	private ClassLoader fixtureLoader() {
		return ExternalCallbackFixture.class.getClassLoader();
	}

	private byte[] readFixture() throws Exception {
		try (InputStream in = ExternalCallbackFixture.class.getResourceAsStream("ExternalCallbackFixture.class")) {
			assertNotNull(in, "fixture class bytes must be on the classpath");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
			return out.toByteArray();
		}
	}
}
