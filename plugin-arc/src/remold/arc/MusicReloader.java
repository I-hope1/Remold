package remold.arc;

import arc.Core;
import arc.audio.Music;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.FileTree;
import mindustry.gen.Musics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Arc / Mindustry 流式音乐原位动态重载器。
 *
 * <p>{@link Music} 与短音效不同：{@code Music.load(Fi)} 会把源文件复制到
 * {@code cache/<文件名>__<文件长度>.<扩展名>}，并以“文件名 + 长度”作为缓存键。
 * 因此热重载前必须先删除同名同长度的缓存，否则修改后长度不变的文件会继续加载旧缓存。</p>
 *
 * <p>与 {@link SoundReloader} 一样保持 {@link Music} 实例不变：Mindustry 的
 * {@link Musics} 静态字段、{@link FileTree#loadMusic(String)} 与播放列表都持有实例引用。
 * 播放中的曲目重载后从头继续播放；音量、循环等控制参数不重置。</p>
 */
public final class MusicReloader {
	private static Field loadedMusicField;
	private static Method cachesMethod;

	static {
		try {
			loadedMusicField = FileTree.class.getDeclaredField("loadedMusic");
			loadedMusicField.setAccessible(true);
		} catch (Throwable ignored) {
		}
		try {
			cachesMethod = Music.class.getDeclaredMethod("caches", String.class);
			cachesMethod.setAccessible(true);
		} catch (Throwable ignored) {
		}
	}

	private MusicReloader() {}

	/**
	 * 重载指定音乐文件对应的全部 {@link Music} 实例。
	 *
	 * @param musicPath 磁盘音乐文件路径
	 * @return 是否找到并重载了匹配的音乐
	 */
	public static boolean reloadMusic(Path musicPath) {
		if (musicPath == null || Core.audio == null) return false;

		Path target;
		try {
			target = musicPath.toAbsolutePath().normalize();
		} catch (Throwable t) {
			return false;
		}
		if (!Files.isRegularFile(target)) return false;

		ObjectSet<Music> seen = new ObjectSet<>();
		int reloaded = 0;
		int failed = 0;

		for (Music music : collectMusic()) {
			if (music == null || !seen.add(music) || !sameFile(music.file, target)) continue;
			if (reloadOne(music, target)) reloaded++;
			else failed++;
		}

		if (reloaded == 0) {
			Log.warn("[Remold-Arc] No loaded music matched @; a restart is required", target);
			return false;
		}
		if (failed > 0) {
			Log.warn("[Remold-Arc] Reloaded @ music track(s), but @ failed for @", reloaded, failed, target);
		}
		Log.info("[Remold-Arc] Successfully in-place reloaded music '@' (@ instance(s))",
			musicName(target), reloaded);
		return true;
	}

	/**
	 * 从任意绝对/相对路径提取 {@code music/} 下的音乐键（不含扩展名）。
	 * 仅用于日志与测试；实际匹配以 {@link Music#file} 的绝对路径为准。
	 */
	public static String musicName(Path musicPath) {
		if (musicPath == null) return null;
		String path = musicPath.toAbsolutePath().normalize().toString().replace('\\', '/');
		String lower = path.toLowerCase(Locale.ROOT);
		String marker = "/music/";
		int index = lower.lastIndexOf(marker);
		String relative;
		if (index >= 0) {
			relative = path.substring(index + marker.length());
		} else if (lower.startsWith("music/")) {
			relative = path.substring("music/".length());
		} else {
			return null;
		}

		int slash = relative.lastIndexOf('/');
		int dot = relative.lastIndexOf('.');
		if (dot > slash) relative = relative.substring(0, dot);
		return relative.isEmpty() ? null : relative;
	}

	private static ObjectSet<Music> collectMusic() {
		ObjectSet<Music> result = new ObjectSet<>();

		for (Field field : Musics.class.getFields()) {
			if (!Modifier.isStatic(field.getModifiers()) || !Music.class.isAssignableFrom(field.getType())) continue;
			try {
				Music music = (Music) field.get(null);
				if (music != null) result.add(music);
			} catch (Throwable ignored) {
			}
		}

		if (Vars.tree != null && loadedMusicField != null) {
			try {
				Object map = loadedMusicField.get(Vars.tree);
				if (map instanceof ObjectMap<?, ?> loaded) {
					for (Object value : loaded.values()) {
						if (value instanceof Music music) result.add(music);
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

	private static boolean reloadOne(Music music, Path target) {
		boolean wasPlaying = music.isPlaying();
		Fi file = new Fi(target.toFile());
		try {
			// 先释放旧 handle，Windows 上仍在流式读取的缓存文件无法删除。
			music.stop();
			music.dispose();
			clearCaches(file);
			music.load(file);
			if (wasPlaying) music.play();
			return true;
		} catch (Throwable t) {
			Log.err("[Remold-Arc] Failed to reload music " + target, t);
			return false;
		}
	}

	/**
	 * 删除 {@code Music.load(Fi)} 将要使用的同键缓存，避免文件长度未变时命中旧内容。
	 */
	private static void clearCaches(Fi file) {
		if (cachesMethod == null) return;
		try {
			String key = file.nameWithoutExtension() + "__" + file.length() + "." + file.extension();
			Fi[] caches = (Fi[]) cachesMethod.invoke(null, key);
			if (caches == null) return;
			for (Fi cache : caches) {
				if (cache != null && cache.exists()) cache.delete();
			}
		} catch (Throwable t) {
			Log.debug("[Remold-Arc] Could not clear music cache for @: @", file.name(), t.toString());
		}
	}
}
