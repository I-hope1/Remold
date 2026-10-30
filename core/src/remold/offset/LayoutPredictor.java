package remold.offset;

import remold.Reflect;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * 物理字段布局预测器（S1/S2）。
 *
 * <p>在执行 {@code Instrumentation.redefineClasses} 之前，通过抛弃式 {@link ClassLoader}
 * clean-load 新字节码，并用 {@link Unsafe#objectFieldOffset} 测算其实际偏移，
 * 预测重定义后的物理字段布局。</p>
 *
 * <p><b>物理原理与实测保证（OffsetStabilityProbe A3）</b>：
 * DCEVM 重定义后的类布局与全新类加载的布局 100% 一致（经单类复杂类型排布、对齐空洞、
 * 三层继承继承链实测验证）。</p>
 */
public final class LayoutPredictor {

	private static final Unsafe UNSAFE = Reflect.UNSAFE;

	private LayoutPredictor() {}

	/**
	 * 单个字段的偏移漂移记录。
	 */
	public record DriftingField(
		String internalClassName,
		String fieldName,
		long oldOffset,
		long newOffset
	) {
		public long delta() {
			return newOffset - oldOffset;
		}

		@Override
		public String toString() {
			return internalClassName + "." + fieldName + " (offset " + oldOffset + " -> " + newOffset + ", delta=" + delta() + ")";
		}
	}

	/**
	 * 单个被删除字段记录。
	 */
	public record DeletedField(
		String internalClassName,
		String fieldName,
		long oldOffset
	) {
		@Override
		public String toString() {
			return internalClassName + "." + fieldName + " (deleted, former offset " + oldOffset + ")";
		}
	}

	/**
	 * 物理布局预测结果。
	 */
	public record PredictionResult(
		boolean isComplete,
		List<DriftingField> driftingFields,
		List<DeletedField> deletedFields,
		Set<String> unpredictedClasses,
		String failureReason
	) {
		public static PredictionResult complete(List<DriftingField> drifting, List<DeletedField> deleted) {
			return new PredictionResult(
				true,
				drifting != null ? Collections.unmodifiableList(drifting) : Collections.emptyList(),
				deleted != null ? Collections.unmodifiableList(deleted) : Collections.emptyList(),
				Collections.emptySet(),
				null
			);
		}

		public static PredictionResult partial(List<DriftingField> drifting, List<DeletedField> deleted, Set<String> unpredicted) {
			return new PredictionResult(
				false,
				drifting != null ? Collections.unmodifiableList(drifting) : Collections.emptyList(),
				deleted != null ? Collections.unmodifiableList(deleted) : Collections.emptyList(),
				unpredicted != null ? Collections.unmodifiableSet(unpredicted) : Collections.emptySet(),
				"Prediction incomplete for classes: " + unpredicted
			);
		}

		public static PredictionResult failure(String reason, Set<String> unpredicted) {
			return new PredictionResult(
				false,
				Collections.emptyList(),
				Collections.emptyList(),
				unpredicted != null ? Collections.unmodifiableSet(unpredicted) : Collections.emptySet(),
				reason
			);
		}

		public boolean hasDrift() {
			return !driftingFields.isEmpty();
		}

		public boolean hasDeletedFields() {
			return !deletedFields.isEmpty();
		}
	}

	/**
	 * 字节码提供器：给定类内部名，返回字节码。
	 */
	@FunctionalInterface
	public interface BytecodeProvider {
		byte[] getBytecode(String internalName);
	}

	/**
	 * 子孙类提供器：给定类内部名，返回所有已加载的子孙类内部名集合。
	 */
	@FunctionalInterface
	public interface DescendantSupplier {
		Set<String> getDescendants(String baseInternalName);
	}

	/**
	 * 预测给定类在应用新字节码后的物理字段偏移。
	 *
	 * @param targetClassDotName 类全限定名（如 "com.example.Foo"）
	 * @param newBytecodes       本次重定义批次的新字节码（key: 内部名，如 "com/example/Foo"）
	 * @param fallbackProvider   备用字节码提供器（用于提供子孙类已有的字节码）
	 * @param parentLoader       用于委派父类/系统类的 ClassLoader
	 * @return 字段名 -> 预测物理偏移的映射；如果预测失败则返回 null
	 */
	public static Map<String, Long> predictOffsets(
		String targetClassDotName,
		Map<String, byte[]> newBytecodes,
		BytecodeProvider fallbackProvider,
		ClassLoader parentLoader
	) {
		Map<String, byte[]> batch = new HashMap<>();
		if (newBytecodes != null) batch.putAll(newBytecodes);
		if (parentLoader != null && fallbackProvider != null) {
			try {
				Class<?> liveClass = Class.forName(targetClassDotName, false, parentLoader);
				populateNonJdkSuperclasses(liveClass, batch, fallbackProvider);
			} catch (Throwable ignored) {}
		}
		EphemeralClassLoader cl = new EphemeralClassLoader(batch, fallbackProvider, parentLoader);
		try {
			Class<?> clazz = Class.forName(targetClassDotName, false, cl);
			Map<String, Long> offsets = new LinkedHashMap<>();
			for (Field f : clazz.getDeclaredFields()) {
				if ((f.getModifiers() & Modifier.STATIC) == 0) {
					try {
						offsets.put(f.getName(), UNSAFE.objectFieldOffset(f));
					} catch (Throwable ignored) {}
				}
			}
			return offsets;
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * 预测目标类自身及其所有已加载子孙类在应用新字节码后的物理字段布局结果。
	 *
	 * @param targetClass        正在被重定义的 live Class 对象
	 * @param newBytes           该类的新字节码
	 * @param newBatchBytes      本次重定义批次中所有类的新字节码（key: 内部名）
	 * @param fallbackProvider   字节码提供器（读取子孙类或旧类的字节码）
	 * @param descendantSupplier 获取已加载子孙类的提供器
	 * @return 布局预测完整结果，包含漂移字段、删除字段以及未能成功验证的子孙类集合
	 */
	public static PredictionResult predictLayout(
		Class<?> targetClass,
		byte[] newBytes,
		Map<String, byte[]> newBatchBytes,
		BytecodeProvider fallbackProvider,
		DescendantSupplier descendantSupplier
	) {
		if (targetClass == null || newBytes == null) {
			return PredictionResult.complete(Collections.emptyList(), Collections.emptyList());
		}

		String targetDotName = targetClass.getName();
		String targetInternal = targetDotName.replace('.', '/');

		Map<String, byte[]> batch = new HashMap<>();
		if (newBatchBytes != null) batch.putAll(newBatchBytes);
		batch.put(targetInternal, newBytes);

		ClassLoader parentLoader = targetClass.getClassLoader();

		// 将 targetClass 的整条非 JDK 祖先链预先注入 batch，避免委派给 parentLoader
		// 导致 package-private 祖先类跨 Loader 抛出 IllegalAccessError (Runtime Package 隔离)
		populateNonJdkSuperclasses(targetClass, batch, fallbackProvider);

		// 如果有子孙类，也同样预注入子孙类自身的整条非 JDK 祖先链
		Set<String> descendants = null;
		if (descendantSupplier != null) {
			descendants = descendantSupplier.getDescendants(targetInternal);
			if (descendants != null && !descendants.isEmpty()) {
				for (String descInternal : descendants) {
					if (descInternal == null || descInternal.equals(targetInternal)) continue;
					try {
						Class<?> liveDescClass = Class.forName(descInternal.replace('/', '.'), false, parentLoader);
						populateNonJdkSuperclasses(liveDescClass, batch, fallbackProvider);
					} catch (Throwable ignored) {}
				}
			}
		}

		EphemeralClassLoader cl = new EphemeralClassLoader(batch, fallbackProvider, parentLoader);

		List<DriftingField> drifting = new ArrayList<>();
		List<DeletedField> deleted = new ArrayList<>();
		Set<String> unpredicted = new LinkedHashSet<>();

		// 1. 检查目标类自身的所有已有实例字段
		try {
			Class<?> predictedTarget = Class.forName(targetDotName, false, cl);
			for (Field liveField : targetClass.getDeclaredFields()) {
				if ((liveField.getModifiers() & Modifier.STATIC) != 0) continue;
				String fn = liveField.getName();
				long oldOff = UNSAFE.objectFieldOffset(liveField);
				Field predField;
				try {
					predField = predictedTarget.getDeclaredField(fn);
				} catch (NoSuchFieldException e) {
					// 字段被删除：记录到删除字段中，以便后续检查是否有缓存持有者
					deleted.add(new DeletedField(targetInternal, fn, oldOff));
					continue;
				}
				long newOff = UNSAFE.objectFieldOffset(predField);
				if (oldOff != newOff) {
					drifting.add(new DriftingField(targetInternal, fn, oldOff, newOff));
				}
			}
		} catch (Throwable t) {
			unpredicted.add(targetInternal);
			return PredictionResult.failure("Failed to clean-load target class " + targetDotName + ": " + t, unpredicted);
		}

		// 2. 检查所有已加载子孙类的已有实例字段
		if (descendants != null && !descendants.isEmpty()) {
			for (String descInternal : descendants) {
				if (descInternal == null || descInternal.equals(targetInternal)) continue;
				String descDotName = descInternal.replace('/', '.');
				try {
					Class<?> liveDescClass = Class.forName(descDotName, false, parentLoader);
					Class<?> predDescClass = Class.forName(descDotName, false, cl);
					for (Field liveField : liveDescClass.getDeclaredFields()) {
						if ((liveField.getModifiers() & Modifier.STATIC) != 0) continue;
						String fn = liveField.getName();
						long oldOff = UNSAFE.objectFieldOffset(liveField);
						Field predField;
						try {
							predField = predDescClass.getDeclaredField(fn);
						} catch (NoSuchFieldException e) {
							deleted.add(new DeletedField(descInternal, fn, oldOff));
							continue;
						}
						long newOff = UNSAFE.objectFieldOffset(predField);
						if (oldOff != newOff) {
							drifting.add(new DriftingField(descInternal, fn, oldOff, newOff));
						}
					}
				} catch (Throwable t) {
					// 子孙类无法在抛弃式加载器中复现（如动态生成类、hidden class、无法读取字节码或链接报错）
					unpredicted.add(descInternal);
				}
			}
		}

		if (!unpredicted.isEmpty()) {
			return PredictionResult.partial(drifting, deleted, unpredicted);
		}
		return PredictionResult.complete(drifting, deleted);
	}

	private static boolean isJdk(String name) {
		return name.startsWith("java.") || name.startsWith("javax.")
				|| name.startsWith("jdk.") || name.startsWith("sun.")
				|| name.startsWith("com.sun.");
	}

	private static void populateNonJdkSuperclasses(
		Class<?> clazz,
		Map<String, byte[]> batch,
		BytecodeProvider fallbackProvider
	) {
		if (clazz == null || fallbackProvider == null) return;
		for (Class<?> curr = clazz.getSuperclass(); curr != null && curr != Object.class; curr = curr.getSuperclass()) {
			String name = curr.getName();
			if (isJdk(name)) break;
			String internal = name.replace('.', '/');
			if (!batch.containsKey(internal)) {
				byte[] bytes = fallbackProvider.getBytecode(internal);
				if (bytes != null) {
					batch.put(internal, bytes);
				}
			}
			for (Class<?> itf : curr.getInterfaces()) {
				String itfName = itf.getName();
				if (!isJdk(itfName)) {
					String itfInternal = itfName.replace('.', '/');
					if (!batch.containsKey(itfInternal)) {
						byte[] b = fallbackProvider.getBytecode(itfInternal);
						if (b != null) batch.put(itfInternal, b);
					}
				}
			}
		}
	}

	/**
	 * 扫描目标类自身及其所有已加载子孙类中发生物理偏移漂移的所有实例字段（简化视图）。
	 *
	 * @param targetClass        正在被重定义的 live Class 对象
	 * @param newBytes           该类的新字节码
	 * @param newBatchBytes      本次重定义批次中所有类的新字节码（key: 内部名）
	 * @param fallbackProvider   字节码提供器（读取子孙类或旧类的字节码）
	 * @param descendantSupplier 获取已加载子孙类的提供器
	 * @return 发生漂移的字段列表；若无漂移则返回空列表；若无法预测目标类则返回 null
	 */
	public static List<DriftingField> findDriftingFields(
		Class<?> targetClass,
		byte[] newBytes,
		Map<String, byte[]> newBatchBytes,
		BytecodeProvider fallbackProvider,
		DescendantSupplier descendantSupplier
	) {
		PredictionResult res = predictLayout(targetClass, newBytes, newBatchBytes, fallbackProvider, descendantSupplier);
		if (!res.isComplete() && targetClass != null && res.unpredictedClasses().contains(targetClass.getName().replace('.', '/'))) {
			return null;
		}
		return res.driftingFields();
	}

	/**
	 * 抛弃式 ClassLoader：用于独立干净加载新字节码，完全隔离主应用环境。
	 */
	static final class EphemeralClassLoader extends ClassLoader {
		private final Map<String, byte[]> definedBytes;
		private final BytecodeProvider fallback;

		EphemeralClassLoader(Map<String, byte[]> definedBytes, BytecodeProvider fallback, ClassLoader parent) {
			super(parent);
			this.definedBytes = definedBytes != null ? definedBytes : Collections.emptyMap();
			this.fallback = fallback;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				// 1. 已加载检查
				Class<?> c = findLoadedClass(name);
				if (c == null) {
					// 2. Child-first: 优先在本批次新字节码及回退字节码中查找
					String internal = name.replace('.', '/');
					byte[] bytes = definedBytes.get(internal);
					if (bytes == null && fallback != null && !isJdk(name)) {
						bytes = fallback.getBytecode(internal);
					}
					if (bytes != null) {
						c = defineClass(name, bytes, 0, bytes.length);
					}
				}
				if (c == null) {
					// 3. 委派给父加载器（JDK 核心类、未改动依赖等）
					c = super.loadClass(name, false);
				}
				if (resolve) {
					resolveClass(c);
				}
				return c;
			}
		}
	}
}
