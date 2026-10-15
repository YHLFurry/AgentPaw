package com.paw.agent.device.accessibility

import com.paw.agent.device.RectBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [AccessibilityClickResolver] 纯逻辑单测——无障碍模式点击精度的核心规则：
 * 「最深可见节点 + 沿父链找可点击/可长按祖先」。
 */
class AccessibilityClickResolverTest {

    private fun snap(
        id: Int,
        parent: Int,
        bounds: RectBounds,
        clickable: Boolean = false,
        longClickable: Boolean = false,
        visible: Boolean = true,
        text: String = "",
        desc: String = "",
    ) = ClickableNodeSnapshot(
        id = id,
        parentId = parent,
        bounds = bounds,
        isClickable = clickable,
        isLongClickable = longClickable,
        visibleToUser = visible,
        text = text,
        contentDescription = desc,
    )

    private fun bounds(l: Int, t: Int, r: Int, b: Int) = RectBounds(l, t, r, b)

    @Test
    fun `deepest visible node wins over overlapping larger parent`() {
        val nodes = listOf(
            snap(0, -1, bounds(0, 0, 1000, 200), clickable = true),      // 行容器
            snap(1, 0, bounds(400, 50, 600, 150), clickable = true),     // 行内按钮
        )
        val target = AccessibilityClickResolver.resolveClickTargetAt(500f, 100f, nodes)
        assertSame(nodes[1], target)
    }

    @Test
    fun `non-clickable deepest node resolves to clickable ancestor`() {
        val nodes = listOf(
            snap(0, -1, bounds(0, 0, 1000, 200), clickable = true),      // 可点击行
            snap(1, 0, bounds(400, 50, 600, 150)),                       // 行内文本（不可点）
        )
        val target = AccessibilityClickResolver.resolveClickTargetAt(500f, 100f, nodes)
        assertSame(nodes[0], target)
    }

    @Test
    fun `walks up multiple levels to find clickable ancestor`() {
        val nodes = listOf(
            snap(0, -1, bounds(0, 0, 500, 500), clickable = true),       // 卡片（可点）
            snap(1, 0, bounds(0, 0, 500, 500)),                          // 容器（不可点）
            snap(2, 1, bounds(100, 100, 200, 200)),                      // 文本（不可点）
        )
        val target = AccessibilityClickResolver.resolveClickTargetAt(150f, 150f, nodes)
        assertSame(nodes[0], target)
    }

    @Test
    fun `returns null when no clickable node in the chain`() {
        val nodes = listOf(
            snap(0, -1, bounds(0, 0, 500, 500)),
            snap(1, 0, bounds(100, 100, 200, 200)),
        )
        assertNull(AccessibilityClickResolver.resolveClickTargetAt(150f, 150f, nodes))
    }

    @Test
    fun `returns null when point hits no node`() {
        val nodes = listOf(
            snap(0, -1, bounds(0, 0, 100, 100), clickable = true),
        )
        assertNull(AccessibilityClickResolver.resolveClickTargetAt(2000f, 2000f, nodes))
        assertNull(AccessibilityClickResolver.resolveClickTargetAt(-5f, 50f, nodes))
    }

    @Test
    fun `invisible nodes are skipped`() {
        val nodes = listOf(
            snap(0, -1, bounds(400, 50, 600, 150), clickable = true, visible = false), // 已隐藏的按钮
            snap(1, -1, bounds(0, 0, 1000, 200), clickable = true),                    // 可见行
        )
        val target = AccessibilityClickResolver.resolveClickTargetAt(500f, 100f, nodes)
        assertSame(nodes[1], target)
    }

    @Test
    fun `identical bounds resolve to the later sibling`() {
        val nodes = listOf(
            snap(0, -1, bounds(0, 0, 100, 100), clickable = true),
            snap(1, -1, bounds(0, 0, 100, 100), clickable = true),
        )
        val target = AccessibilityClickResolver.resolveClickTargetAt(50f, 50f, nodes)
        assertSame(nodes[1], target)
    }

    @Test
    fun `boundary point uses half-open semantics`() {
        val nodes = listOf(
            snap(0, -1, bounds(0, 0, 100, 100), clickable = true),
            snap(1, -1, bounds(100, 0, 200, 100), clickable = true),
        )
        // x=100 恰好是 0 号节点的右边界：归属相邻的 1 号节点，避免双命中
        val target = AccessibilityClickResolver.resolveClickTargetAt(100f, 50f, nodes)
        assertSame(nodes[1], target)
        assertNull(AccessibilityClickResolver.resolveClickTargetAt(100f, 100f, nodes))
    }

    @Test
    fun `degenerate zero-width node never matches`() {
        val nodes = listOf(
            snap(0, -1, bounds(50, 50, 50, 100), clickable = true),
        )
        assertNull(AccessibilityClickResolver.resolveClickTargetAt(50f, 70f, nodes))
    }

    @Test
    fun `long press requires explicit long-clickable declaration in chain`() {
        // 链上只有 clickable、没有 longClickable：不走节点长按，交回手势长按
        val nodes = listOf(
            snap(0, -1, bounds(0, 0, 1000, 200), clickable = true),
            snap(1, 0, bounds(400, 50, 600, 150)),
        )
        assertNull(AccessibilityClickResolver.resolveLongPressTargetAt(500f, 100f, nodes))
    }

    @Test
    fun `long press resolves to nearest long-clickable ancestor`() {
        val nodes = listOf(
            snap(0, -1, bounds(0, 0, 500, 500), longClickable = true),   // 外层可长按
            snap(1, 0, bounds(100, 100, 200, 200), longClickable = true), // 内层也可长按
        )
        // 点在内层：取最近的（最深层）可长按节点
        assertSame(nodes[1], AccessibilityClickResolver.resolveLongPressTargetAt(150f, 150f, nodes))
        // 点在内层之外：落到外层
        assertSame(nodes[0], AccessibilityClickResolver.resolveLongPressTargetAt(300f, 300f, nodes))
    }

    @Test
    fun `parent chain cycle is guarded against infinite loop`() {
        val nodes = listOf(
            snap(0, 1, bounds(0, 0, 100, 100)),
            snap(1, 0, bounds(0, 0, 100, 100)),
        )
        assertNull(AccessibilityClickResolver.resolveClickTargetAt(50f, 50f, nodes))
        assertNull(AccessibilityClickResolver.resolveLongPressTargetAt(50f, 50f, nodes))
    }

    @Test
    fun `empty node list resolves to null`() {
        assertNull(AccessibilityClickResolver.resolveClickTargetAt(100f, 100f, emptyList()))
        assertNull(AccessibilityClickResolver.resolveLongPressTargetAt(100f, 100f, emptyList()))
    }

    @Test
    fun `element center from real bounds always resolves to its clickable node`() {
        // 回归 click_element 主链路：get_screen_state 给出 bounds → 取 center → 命中
        val row = bounds(0, 800, 1080, 950)
        val nodes = listOf(
            snap(0, -1, row, clickable = true),
            snap(1, 0, bounds(60, 830, 500, 920), text = "设置"),
        )
        val target = AccessibilityClickResolver.resolveClickTargetAt(
            row.centerX.toFloat(),
            row.centerY.toFloat(),
            nodes,
        )
        assertSame(nodes[0], target)
        assertEquals(540, row.centerX)
        assertEquals(875, row.centerY)
    }
}
