package remold.arc;

import arc.graphics.g2d.TextureAtlas.AtlasRegion;

/**
 * 针对 Arc TextureRegion.set 遗漏 AtlasRegion 专有元数据（splits, pads, offsets 等）的缺陷，
 * 提供完整的 AtlasRegion 原位同步器。
 */
public final class AtlasRegionSync {
	private AtlasRegionSync() {}

	/**
	 * 将 src 的全部属性原位同步到 dest 中，保持对象引用不变。
	 */
	public static void sync(AtlasRegion dest, AtlasRegion src) {
		if (dest == null || src == null) return;

		// 1. 同步基础 TextureRegion 字段 (u, v, u2, v2, width, height, texture)
		dest.set(src);

		// 2. 同步 AtlasRegion 专有字段
		dest.name = src.name;
		dest.splits = src.splits != null ? src.splits.clone() : null;
		dest.pads = src.pads != null ? src.pads.clone() : null;
		dest.offsetX = src.offsetX;
		dest.offsetY = src.offsetY;
		dest.packedWidth = src.packedWidth;
		dest.packedHeight = src.packedHeight;
		dest.originalWidth = src.originalWidth;
		dest.originalHeight = src.originalHeight;
		dest.rotate = src.rotate;
		dest.pixmapRegion = src.pixmapRegion;
	}
}
