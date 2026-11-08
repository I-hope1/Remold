package com.example.client;

import java.util.List;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.util.Util;

/**
 * 右上角的 HotSwap 结果卡片：深色底、左侧强调条、标题 + 最多 3 行摘要。
 *
 * <p><b>1.21.1 适配版</b>：1.21.1 的 {@link Toast#draw} 形参是 {@code (DrawContext, ToastManager, long)}，
 * 且接口没有 {@code update} 方法；展示计时因此在 {@link #draw} 内完成，返回
 * {@link Visibility#HIDE} 让 {@link ToastManager} 收起卡片。</p>
 *
 * <p><b>刷新而不是叠加</b>：连续热更时复用同一个实例（见
 * {@link HotSwapToastBridge}），{@link #refresh} 会重置展示计时，因此不会出现十张卡片排队。</p>
 *
 * <p><b>渲染线程</b>：{@link ToastManager} 只在客户端渲染线程调用 {@link #draw}；
 * {@link #refresh} 也必须由客户端线程调用（桥接层用 {@code MinecraftClient.execute} 保证）。</p>
 */
public final class HotSwapToast implements Toast {

	/** 同一类型标识，供 {@link ToastManager#getToast} 找回正在展示/排队的实例。 */
	public static final Object TYPE = new Object();

	private static final int WIDTH = 224;
	private static final int HEIGHT = 64;
	private static final int ACCENT_WIDTH = 3;
	private static final long DEFAULT_DISPLAY_MS = 4000L;

	private static final int COLOR_BACKGROUND = 0xF0101218;
	private static final int COLOR_BORDER = 0xFF3A4356;
	private static final int COLOR_ACCENT = 0xFF55D6BE;
	private static final int COLOR_TITLE = 0xFFEAF2FF;
	private static final int COLOR_TEXT = 0xFFB9C4D6;
	private static final int COLOR_MIXIN = 0xFF9CDCFE;
	private static final int COLOR_INITFIX = 0xFFF2CC60;

	private volatile HotSwapToastModel model;
	private volatile long shownAtMillis;
	private volatile boolean timerStarted;

	public HotSwapToast(HotSwapToastModel model) {
		this.model = model;
	}

	/** 用新一批结果刷新同一张卡片，并重新计时。 */
	public void refresh(HotSwapToastModel newModel) {
		if (newModel == null) {
			return;
		}
		this.model = newModel;
		this.timerStarted = false;
	}

	@Override
	public Visibility draw(DrawContext context, ToastManager manager, long startTime) {
		TextRenderer textRenderer = manager.getClient().textRenderer;
		long now = Util.getMeasuringTimeMs();
		if (!timerStarted) {
			// 从真正开始展示时计时，而不是从入队时计时：排队期间不应消耗展示时长。
			shownAtMillis = now;
			timerStarted = true;
		}
		long displayMs = DEFAULT_DISPLAY_MS;
		try {
			// 尊重“通知显示时长”辅助功能选项；取不到就用默认值。
			displayMs = (long) (DEFAULT_DISPLAY_MS * manager.getNotificationDisplayTimeMultiplier());
		} catch (Throwable ignored) {
			// 保持默认值。
		}
		if (now - shownAtMillis >= displayMs) {
			return Visibility.HIDE;
		}

		HotSwapToastModel current = model;
		context.fill(0, 0, WIDTH, HEIGHT, COLOR_BACKGROUND);
		context.drawBorder(0, 0, WIDTH, HEIGHT, COLOR_BORDER);
		context.fill(0, 0, ACCENT_WIDTH, HEIGHT, COLOR_ACCENT);

		context.drawText(textRenderer, trim(textRenderer, current.getTitle(), WIDTH - 20),
			12, 8, COLOR_TITLE, false);

		List<String> lines = current.getSummaryLines();
		for (int i = 0; i < lines.size() && i < HotSwapToastModel.MAX_SUMMARY_LINES; i++) {
			String line = lines.get(i);
			context.drawText(textRenderer, trim(textRenderer, line, WIDTH - 20),
				12, 24 + i * 12, colorFor(line), false);
		}
		return Visibility.SHOW;
	}

	@Override
	public int getWidth() {
		return WIDTH;
	}

	@Override
	public int getHeight() {
		return HEIGHT;
	}

	@Override
	public Object getType() {
		return TYPE;
	}

	private static int colorFor(String line) {
		if (line.startsWith("Mixin")) {
			return COLOR_MIXIN;
		}
		if (line.startsWith("InitFix")) {
			return COLOR_INITFIX;
		}
		return COLOR_TEXT;
	}

	private static String trim(TextRenderer textRenderer, String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		return textRenderer.getWidth(text) <= maxWidth ? text : textRenderer.trimToWidth(text, maxWidth);
	}
}
