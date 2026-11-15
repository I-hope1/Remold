package remold.ref;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.*;

class StageBInstanceCacheTest {

	private static final class Dummy { }

	@AfterEach
	void cleanUp() {
		StageBInstanceCache.clear();
	}

	@Test
	void putThenTakeConsumesOnceIncludingEmptyArray() {
		Object[] populated = new Object[] { new Dummy(), new Dummy() };
		long t0 = System.nanoTime();
		StageBInstanceCache.put(Dummy.class, populated, t0);

		assertSame(populated, StageBInstanceCache.take(Dummy.class, t0 + 1));
		assertNull(StageBInstanceCache.take(Dummy.class, t0 + 2));

		Object[] empty = new Object[0];
		StageBInstanceCache.put(Dummy.class, empty, t0 + 3);
		assertSame(empty, StageBInstanceCache.take(Dummy.class, t0 + 4));
	}

	@Test
	void takeReturnsNullAfterTtlAndSweepsSmallCache() {
		long t0 = System.nanoTime();
		Object[] arr = new Object[] { new Dummy() };
		StageBInstanceCache.put(Dummy.class, arr, t0);

		long expired = t0 + StageBInstanceCache.TTL_NANOS + 1;
		assertNull(StageBInstanceCache.take(Dummy.class, expired));
		assertEquals(0, StageBInstanceCache.size());

		// Ensure a stale entry in a <= 8-entry cache is still swept once TTL passes.
		StageBInstanceCache.put(Dummy.class, arr, t0);
		StageBInstanceCache.put(String.class, new Object[] { "other" }, expired);
		assertNull(StageBInstanceCache.take(Dummy.class, expired + 1));
	}

	@Test
	void clearDiscardsPendingEntries() {
		StageBInstanceCache.put(Dummy.class, new Object[] { new Dummy() });
		assertEquals(1, StageBInstanceCache.size());
		StageBInstanceCache.clear();
		assertEquals(0, StageBInstanceCache.size());
		assertNull(StageBInstanceCache.take(Dummy.class));
	}

	@Test
	void valueArrayIsWeaklyHeldAndCollectedOnGc() {
		Object[] held = new Object[] { new Dummy() };
		WeakReference<Object[]> ref = new WeakReference<>(held);
		StageBInstanceCache.put(Dummy.class, held);
		held = null;

		for (int i = 0; i < 15 && ref.get() != null; i++) {
			System.gc();
			try {
				Thread.sleep(20);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		assertNull(ref.get(), "cached instance array must not be strongly pinned");
		assertNull(StageBInstanceCache.take(Dummy.class));
	}

	@Test
	void classKeyIsWeaklyHeldAndCollectedOnGc() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "remold/ref/EphemeralCacheTarget", null,
			"java/lang/Object", null);
		cw.visitEnd();
		byte[] bytes = cw.toByteArray();

		final class EphemeralLoader extends ClassLoader {
			EphemeralLoader() { super(null); }
			Class<?> define(byte[] b) { return defineClass("remold.ref.EphemeralCacheTarget", b, 0, b.length); }
		}

		EphemeralLoader loader = new EphemeralLoader();
		Class<?> dynamicClass = loader.define(bytes);
		WeakReference<Class<?>> classRef = new WeakReference<>(dynamicClass);
		StageBInstanceCache.put(dynamicClass, new Object[0]);
		dynamicClass = null;
		loader = null;

		for (int i = 0; i < 20 && classRef.get() != null; i++) {
			System.gc();
			try {
				Thread.sleep(25);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		assertNull(classRef.get(), "Class<?> key must not be strongly retained by StageBInstanceCache");
	}
}
