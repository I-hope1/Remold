package remold.arc;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.gl.Shader;
import arc.struct.ObjectIntMap;
import arc.util.Log;
import mindustry.graphics.Shaders;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Arc / Mindustry 内置着色器原位热替换。
 *
 * <p>着色器文件在构造 {@link Shader} 时就被编译进 GPU 程序，静态字段里保存的是编译结果。
 * 这里不重建 {@link Shaders} 的静态对象（那会留下 {@code CacheLayer}、{@code PlanetMesh} 等
 * 旧引用），而是按文件到字段的绑定重新编译，并把新程序的句柄、uniform/attribute 查找表
 * 拷回原对象，使所有既有引用继续指向同一个实例。</p>
 *
 * <p>只处理 Mindustry 内置着色器文件；模组自定义着色器没有可靠的字段映射，继续提示重启。
 * 编译失败时保留旧程序，不替换任何句柄。</p>
 */
public final class ShaderReloader {
	private record Binding(String field, String vert, String frag) {}

	private static final List<Binding> BINDINGS = List.of(
		new Binding("blockbuild", "default", "blockbuild"),
		new Binding("shield", "screenspace", "shield"),
		new Binding("buildBeam", "screenspace", "buildbeam"),
		new Binding("build", "default", "unitbuild"),
		new Binding("armor", "default", "unitarmor"),
		new Binding("darkness", "default", "darkness"),
		new Binding("fog", "default", "fog"),
		new Binding("light", "screenspace", "light"),
		new Binding("water", "screenspace", "water"),
		new Binding("mud", "screenspace", "mud"),
		new Binding("tar", "screenspace", "tar"),
		new Binding("slag", "screenspace", "slag"),
		new Binding("cryofluid", "screenspace", "cryofluid"),
		new Binding("arkycite", "screenspace", "arkycite"),
		new Binding("space", "screenspace", "space"),
		new Binding("caustics", "screenspace", "caustics"),
		new Binding("planet", "planet", "planet"),
		new Binding("clouds", "clouds", "planet"),
		new Binding("planetGrid", "planetgrid", "planetgrid"),
		new Binding("atmosphere", "atmosphere", "atmosphere"),
		new Binding("shockwave", "screenspace", "shockwave"),
		new Binding("mesh", "mesh", "planet"),
		new Binding("unlit", "unlit", "planet"),
		new Binding("unlitWhite", "unlitwhite", "planet"),
		new Binding("screenspace", "screenspace", "screenspace")
	);

	private static final Map<String, Binding> BY_FIELD;
	private static final Map<String, List<String>> AFFECTED;

	private static final Field programField;
	private static final Field vertexHandleField;
	private static final Field fragmentHandleField;
	private static final Field isCompiledField;
	private static final Field logField;
	private static final Field uniformsField;
	private static final Field uniformTypesField;
	private static final Field uniformSizesField;
	private static final Field attributesField;
	private static final Field attributeTypesField;
	private static final Field attributeSizesField;
	private static final Field uniformNamesField;
	private static final Field attributeNamesField;
	private static final Field vertexSourceField;
	private static final Field fragmentSourceField;

	static {
		Map<String, Binding> byField = new LinkedHashMap<>();
		Map<String, LinkedHashSet<String>> affected = new HashMap<>();
		for (Binding binding : BINDINGS) {
			byField.put(binding.field(), binding);
			affected.computeIfAbsent(binding.vert() + ".vert", ignored -> new LinkedHashSet<>()).add(binding.field());
			affected.computeIfAbsent(binding.frag() + ".frag", ignored -> new LinkedHashSet<>()).add(binding.field());
		}
		BY_FIELD = Map.copyOf(byField);
		Map<String, List<String>> affectedCopy = new HashMap<>();
		affected.forEach((file, fields) -> affectedCopy.put(file, List.copyOf(fields)));
		AFFECTED = Map.copyOf(affectedCopy);

		programField = shaderField("program");
		vertexHandleField = shaderField("vertexShaderHandle");
		fragmentHandleField = shaderField("fragmentShaderHandle");
		isCompiledField = shaderField("isCompiled");
		logField = shaderField("log");
		uniformsField = shaderField("uniforms");
		uniformTypesField = shaderField("uniformTypes");
		uniformSizesField = shaderField("uniformSizes");
		attributesField = shaderField("attributes");
		attributeTypesField = shaderField("attributeTypes");
		attributeSizesField = shaderField("attributeSizes");
		uniformNamesField = shaderField("uniformNames");
		attributeNamesField = shaderField("attributeNames");
		vertexSourceField = shaderField("vertexShaderSource");
		fragmentSourceField = shaderField("fragmentShaderSource");
	}

	private ShaderReloader() {}

	/**
	 * 重新编译与变动文件绑定的内置着色器。
	 *
	 * @param shaderPath 发生变动的着色器文件路径
	 * @return 是否至少成功替换了一个内置着色器
	 */
	public static boolean reloadShader(Path shaderPath) {
		if (shaderPath == null || shaderPath.getFileName() == null) return false;
		if (Core.app == null || Core.gl == null) return false;
		if (!available()) {
			Log.warn("[Remold-Arc] Shader reload unavailable (Shader internals changed); a restart is required for @",
				shaderPath.getFileName());
			return false;
		}

		String fileName = shaderPath.getFileName().toString().toLowerCase(Locale.ROOT);
		List<String> fields = affectedFields(fileName);
		if (fields.isEmpty()) {
			Log.warn("[Remold-Arc] No built-in shader matched @; a restart is required", shaderPath.getFileName());
			return false;
		}

		List<String> reloaded = new ArrayList<>();
		int failed = 0;
		for (String fieldName : fields) {
			Binding binding = BY_FIELD.get(fieldName);
			if (binding == null) continue;
			try {
				Field field = Shaders.class.getField(fieldName);
				if (!Modifier.isStatic(field.getModifiers())) continue;
				Shader target = (Shader) field.get(null);
				if (target == null || target.isDisposed()) continue;

				if (recompile(target, binding.vert(), binding.frag())) {
					reloaded.add(fieldName);
				} else {
					failed++;
				}
			} catch (Throwable t) {
				failed++;
				Log.err("[Remold-Arc] Failed to reload shader field " + fieldName + " for " + fileName, t);
			}
		}

		if (reloaded.isEmpty()) {
			if (failed == 0) {
				Log.debug("[Remold-Arc] Shader @ matched no live built-in instance", fileName);
			} else {
				Log.warn("[Remold-Arc] Shader @ could not be recompiled; the old program is still active", fileName);
			}
			return false;
		}
		if (failed > 0) {
			Log.warn("[Remold-Arc] Reloaded shader field(s) @ for @, but @ failed",
				reloaded, fileName, failed);
		}
		Log.info("[Remold-Arc] In-place reloaded built-in shader field(s) @ for @", reloaded, fileName);
		return true;
	}

	/**
	 * 返回给定着色器文件名（含扩展名）绑定的内置字段。
	 */
	public static List<String> affectedFields(String shaderFileName) {
		if (shaderFileName == null) return List.of();
		List<String> fields = AFFECTED.get(shaderFileName.toLowerCase(Locale.ROOT));
		return fields == null ? List.of() : fields;
	}

	/**
	 * 返回字段对应的着色器文件名（含扩展名）。
	 */
	public static List<String> shaderFiles(String fieldName) {
		Binding binding = BY_FIELD.get(fieldName);
		if (binding == null) return List.of();
		return List.of(binding.vert() + ".vert", binding.frag() + ".frag");
	}

	/**
	 * 字段是否属于已知的内置着色器绑定。
	 */
	public static boolean hasBinding(String fieldName) {
		return BY_FIELD.containsKey(fieldName);
	}

	private static boolean recompile(Shader target, String vertName, String fragName) {
		try {
			Fi vert = Shaders.getShaderFi(vertName + ".vert");
			Fi frag = Shaders.getShaderFi(fragName + ".frag");
			if (vert == null || frag == null || !vert.exists() || !frag.exists()) {
				Log.warn("[Remold-Arc] Shader source missing: @.vert / @.frag", vertName, fragName);
				return false;
			}

			Shader fresh = new Shader(vert.readString(), frag.readString());
			int oldProgram = programField.getInt(target);
			int oldVertex = vertexHandleField.getInt(target);
			int oldFragment = fragmentHandleField.getInt(target);

			copyState(fresh, target);
			disposeOld(oldProgram, oldVertex, oldFragment);
			return true;
		} catch (Throwable t) {
			Log.err("[Remold-Arc] Failed to compile shader " + vertName + ".vert / " + fragName + ".frag", t);
			return false;
		}
	}

	private static void copyState(Shader from, Shader to) throws IllegalAccessException {
		programField.setInt(to, programField.getInt(from));
		vertexHandleField.setInt(to, vertexHandleField.getInt(from));
		fragmentHandleField.setInt(to, fragmentHandleField.getInt(from));
		isCompiledField.setBoolean(to, isCompiledField.getBoolean(from));
		logField.set(to, logField.get(from));
		copyIntMap(uniformsField, from, to);
		copyIntMap(uniformTypesField, from, to);
		copyIntMap(uniformSizesField, from, to);
		copyIntMap(attributesField, from, to);
		copyIntMap(attributeTypesField, from, to);
		copyIntMap(attributeSizesField, from, to);
		uniformNamesField.set(to, uniformNamesField.get(from));
		attributeNamesField.set(to, attributeNamesField.get(from));
		setSource(vertexSourceField, from, to);
		setSource(fragmentSourceField, from, to);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void copyIntMap(Field field, Shader from, Shader to) throws IllegalAccessException {
		ObjectIntMap source = (ObjectIntMap) field.get(from);
		ObjectIntMap destination = (ObjectIntMap) field.get(to);
		destination.clear();
		destination.putAll(source);
	}

	private static void setSource(Field field, Shader from, Shader to) {
		try {
			field.set(to, field.get(from));
		} catch (Throwable ignored) {
		}
	}

	private static void disposeOld(int program, int vertex, int fragment) {
		if (program > 0) Gl.deleteProgram(program);
		if (vertex > 0) Gl.deleteShader(vertex);
		if (fragment > 0) Gl.deleteShader(fragment);
	}

	private static boolean available() {
		return programField != null
			&& vertexHandleField != null
			&& fragmentHandleField != null
			&& isCompiledField != null
			&& logField != null
			&& uniformsField != null
			&& uniformTypesField != null
			&& uniformSizesField != null
			&& attributesField != null
			&& attributeTypesField != null
			&& attributeSizesField != null
			&& uniformNamesField != null
			&& attributeNamesField != null
			&& vertexSourceField != null
			&& fragmentSourceField != null;
	}

	private static Field shaderField(String name) {
		try {
			Field field = Shader.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (Throwable ignored) {
			return null;
		}
	}
}
