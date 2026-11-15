package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.*;

class MountManagerRefreshTest {

	@Test
	void refreshPackageLoadersHandlesPrimitivesArraysAndChildLoaderUpgrade() throws Exception {
		String pkg = "remold.pkgrefresh.test" + System.nanoTime();
		String parentClassName = pkg + ".ParentCls";
		String childClassName = pkg + ".ChildCls";

		ClassLoader parent = new ByteDefClassLoader(
			MountManagerRefreshTest.class.getClassLoader(), parentClassName, emptyClassBytes(parentClassName));
		ClassLoader child = new ByteDefClassLoader(
			parent, childClassName, emptyClassBytes(childClassName));

		Class<?> parentCls = Class.forName(parentClassName, false, parent);
		Class<?> childCls = Class.forName(childClassName, false, child);

		try {
			// Primitives/arrays must be skipped cleanly; repeated parent stays stable; child upgrades parent.
			MountManager.refreshPackageLoaders(new Class<?>[]{int.class, int[].class, String[].class, parentCls});
			assertSame(parent, MountManager.packageLoaders.get(pkg).get());

			MountManager.refreshPackageLoaders(new Class<?>[]{parentCls, parentCls});
			assertSame(parent, MountManager.packageLoaders.get(pkg).get());

			MountManager.refreshPackageLoaders(new Class<?>[]{childCls});
			assertSame(child, MountManager.packageLoaders.get(pkg).get());

			// Same-rank non-child loader must not downgrade/replace an already selected child loader.
			ClassLoader sibling = new ByteDefClassLoader(
				MountManagerRefreshTest.class.getClassLoader(), pkg + ".SiblingCls",
				emptyClassBytes(pkg + ".SiblingCls"));
			Class<?> siblingCls = Class.forName(pkg + ".SiblingCls", false, sibling);
			MountManager.refreshPackageLoaders(new Class<?>[]{siblingCls});
			assertSame(child, MountManager.packageLoaders.get(pkg).get());

			// Cleared WeakReference slot recovers cleanly on the next scan.
			MountManager.packageLoaders.put(pkg, new WeakReference<>(null));
			MountManager.refreshPackageLoaders(new Class<?>[]{parentCls});
			assertSame(parent, MountManager.packageLoaders.get(pkg).get());
		} finally {
			MountManager.packageLoaders.remove(pkg);
		}
	}

	private static byte[] emptyClassBytes(String dotName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, dotName.replace('.', '/'),
			null, "java/lang/Object", null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static final class ByteDefClassLoader extends ClassLoader {
		private final String targetName;
		private final byte[] bytes;

		ByteDefClassLoader(ClassLoader parent, String targetName, byte[] bytes) {
			super(parent);
			this.targetName = targetName;
			this.bytes = bytes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			if (targetName.equals(name)) {
				return defineClass(name, bytes, 0, bytes.length);
			}
			throw new ClassNotFoundException(name);
		}
	}
}
