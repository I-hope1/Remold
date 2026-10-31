package remold.arc;

import arc.Core;
import arc.audio.AudioSource;
import arc.audio.Sound;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.FileTree;
import mindustry.gen.Sounds;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Arc / Mindustry 音效原位动态重载器。
 *
 * <p>只处理短音效 {@link Sound}：Mindustry 的 {@code Sounds} 静态字段和
 * {@link FileTree#loadSound(String)} 都持有长期存活的 {@link Sound} 实例，
 * 内容字段（{@code shootSound}、{@code ambientSound} 等）也直接引用这些实例。
 * 因此不能替换对象，只能对同一个实例执行 {@code stop → dispose → load(Fi)}，
 * 保留所有既有引用与控制参数。</p>
 *
 * <p>{@code music/} 下的流式音乐不在本类处理范围：{@code Music.load(Fi)} 会把文件
 * 复制到以“文件名 + 文件长度”命名的缓存，且播放列表持有实例，无法安全原位换源。</p>
 */
public final class SoundReloader {
	private static Field loadedSoundsField;
	private static Method setParamsAfterLoad;

	static {
		try {
			loadedSoundsField = FileTree.class.getDeclaredField("loadedSounds");
			loadedSoundsField.setAccessible(true);
		} catch (Throwable ignored) {
		}
		try {
			setParamsAfterLoad = AudioSource.class.getDeclaredMethod("setParamsAfterLoad");
			setParamsAfterLoad.setAccessible(true);
		} catch (Throwable ignored) {
		}
	}

	private SoundReloader() {}

	/**
	 * 重载指定音效文件对应的全部 {@link Sound} 实例。
	 *
	 * @param soundPath 磁盘音效文件路径
	 * @return 是否找到并处理了匹配的音效（延迟加载音效也算已处理）
	 */
	public static boolean reloadSound(Path soundPath) {
		if (soundPath == null || Core.audio == null) return false;

		Path target;
		try {
			target = soundPath.toAbsolutePath().normalize();
		} catch (Throwable t) {
			return false;
		}
		if (!Files.isRegularFile(target)) return false;

		ObjectSet<Sound> seen = new ObjectSet<>();
		int reloaded = 0;
		int deferred = 0;
		int failed = 0;

		for (Sound sound : collectSounds()) {
			if (sound == null || !seen.add(sound) || !sameFile(sound.file, target)) continue;

			// 从未播放过的 lazy sound 还没有分配 handle；下次播放本来就会重新读盘。
			if (sound.isLazy() && !sound.valid()) {
				deferred++;
				continue;
			}
			if (reloadOne(sound, target)) reloaded++;
			else failed++;
		}

		if (reloaded == 0 && deferred == 0) {
			Log.warn("[Remold-Arc] No loaded sound matched @; a restart is required", target);
			return false;
		}
		if (failed > 0) {
			Log.warn("[Remold-Arc] Reloaded @ sound(s), but @ failed for @", reloaded, failed, target);
		}
		if (deferred > 0) {
			Log.info("[Remold-Arc] Sound '@' is not loaded yet; it will use the new file on next play",
				soundName(target));
		}
		if (reloaded > 0) {
			Log.info("[Remold-Arc] Successfully in-place reloaded sound '@' (@ instance(s))",
				soundName(target), reloaded);
		}
		return true;
	}

	/**
	 * 从任意绝对/相对路径提取 {@code sounds/} 下的音效键（不含扩展名）。
	 * 仅用于日志与测试；实际匹配以 {@link Sound#file} 的绝对路径为准。
	 */
	public static String soundName(Path soundPath) {
		if (soundPath == null) return null;
		String path = soundPath.toAbsolutePath().normalize().toString().replace('\\', '/');
		String lower = path.toLowerCase(Locale.ROOT);
		String marker = "/sounds/";
		int index = lower.lastIndexOf(marker);
		String relative;
		if (index >= 0) {
			relative = path.substring(index + marker.length());
		} else if (lower.startsWith("sounds/")) {
			relative = path.substring("sounds/".length());
		} else {
			return null;
		}

		int slash = relative.lastIndexOf('/');
		int dot = relative.lastIndexOf('.');
		if (dot > slash) relative = relative.substring(0, dot);
		return relative.isEmpty() ? null : relative;
	}

	private static ObjectSet<Sound> collectSounds() {
		ObjectSet<Sound> result = new ObjectSet<>();

		for (Field field : Sounds.class.getFields()) {
			if (!Modifier.isStatic(field.getModifiers()) || !Sound.class.isAssignableFrom(field.getType())) continue;
			try {
				Sound sound = (Sound) field.get(null);
				if (sound != null) result.add(sound);
			} catch (Throwable ignored) {
			}
		}

		if (Vars.tree != null && loadedSoundsField != null) {
			try {
				Object map = loadedSoundsField.get(Vars.tree);
				if (map instanceof ObjectMap<?, ?> loaded) {
					for (Object value : loaded.values()) {
						if (value instanceof Sound sound) result.add(sound);
					}
				}
			} catch (Throwable ignored) {
			}
		}
		return result;
	}

	private static boolean sameFile(Fi file, Path target) {
		if (file == null) return false;
		try {
			return Paths.get(file.path()).toAbsolutePath().normalize().equals(target);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static boolean reloadOne(Sound sound, Path target) {
		try {
			Fi file = new Fi(target.toFile());
			sound.stop();
			sound.dispose();
			sound.load(file);
			try {
				if (setParamsAfterLoad != null) setParamsAfterLoad.invoke(sound);
			} catch (Throwable t) {
				Log.debug("[Remold-Arc] Sound reloaded, but parameter restoration failed: @", t.toString());
			}
			return true;
		} catch (Throwable t) {
			Log.err("[Remold-Arc] Failed to reload sound " + target, t);
			return false;
		}
	}
}
