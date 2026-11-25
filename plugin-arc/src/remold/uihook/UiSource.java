package remold.uihook;

import arc.scene.Element;
import arc.scene.ui.layout.Cell;

/**
 * UI 元素源码视图的调试入口。
 *
 * <p>{@link #of(Cell)} 读取 {@link CellPropertyRef} 记录的创建站点，并把该站点周围的当前磁盘
 * 源码渲染成片段（默认前后各 4 行）。整个入口只读：不修改追踪器，不参与任何热更判决。</p>
 *
 * <pre>{@code
 * Cell<?> cell = table.image(icon).size(48);
 * System.out.println(UiSource.of(cell));
 * }</pre>
 *
 * <p>前提：{@link CellPropertyRef} 的追踪处于开启状态（模组侧调用 {@link CellPropertyRef#enable()}）；
 * 追踪关闭、或该 Cell 不是经 Table/Cell 创建链绑定时，返回只带位置与原因的片段。</p>
 *
 * <p>源码根：{@code -Dremold.source_roots=<dir>[:<dir>...]}（按主机路径分隔符切分），或别名
 * {@code -Dremold.agent.source_roots}；两者都未设置时，探测进程工作目录下的
 * {@code src/main/java} 与 {@code src/main/kotlin}。</p>
 *
 * <p>下游模组直接依赖本插件模块即可调用；运行期这些类由 Agent 的 jar 提供。</p>
 */
public final class UiSource {

	private UiSource() {}

	/** 返回该 Cell 对应元素的源码片段（前后各 4 行上下文）。 */
	public static SourceSnippet of(Cell<?> cell) {
		return of(cell, SourceSnippet.DEFAULT_CONTEXT);
	}

	/** 返回该 Cell 对应元素的源码片段（{@code context} 为两侧上下文行数）。 */
	public static SourceSnippet of(Cell<?> cell, int context) {
		if (cell == null) return SourceSnippet.unresolved("", "", -1, "no cell given");
		CellPropertyRef.CellIdentity id = CellPropertyRef.identityOf(cell);
		if (id == null) {
			return SourceSnippet.unresolved("", "", -1,
			 "cell is not tracked by CellPropertyRef (tracking disabled, or no creator call was observed)");
		}
		return SourceSnippet.at(id.hostClass(), id.hostMethod(), id.line(),
		 CellPropertyRef.sourceFileOf(cell), context);
	}

	/** 返回渲染该元素的受追踪 Cell 的源码片段（前后各 4 行上下文）。 */
	public static SourceSnippet of(Element element) {
		return of(element, SourceSnippet.DEFAULT_CONTEXT);
	}

	/** 返回渲染该元素的受追踪 Cell 的源码片段（{@code context} 为两侧上下文行数）。 */
	public static SourceSnippet of(Element element, int context) {
		if (element == null) return SourceSnippet.unresolved("", "", -1, "no element given");
		Cell<?> cell = CellPropertyRef.cellOf(element);
		if (cell == null) {
			return SourceSnippet.unresolved("", "", -1,
			 "no tracked cell renders this element (tracking disabled, or the element is not a tracked cell)");
		}
		return of(cell, context);
	}

	/** 调试视图：渲染 {@code element} 的受追踪 Cell；没有时返回 {@code null}。 */
	public static Cell<?> cellOf(Element element) {
		return CellPropertyRef.cellOf(element);
	}
}
