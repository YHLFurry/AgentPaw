package com.paw.agent.device.accessibility

import com.paw.agent.device.RectBounds

/**
 * 无障碍节点树中与「点击命中」相关的轻量快照。
 *
 * 纯数据类，不持有任何 Android framework 对象（[AccessibilityNodeInfo] 由采集方
 * 另行保管），因此可以安全地跨线程传递、单元测试与序列化诊断。
 */
data class ClickableNodeSnapshot(
    /** 在整棵树先序扁平遍历中的稳定序号 */
    val id: Int,
    /** 父节点 id；根节点为 [NO_PARENT] */
    val parentId: Int,
    /** 节点在屏幕坐标系中的像素边界（来自 AccessibilityNodeInfo.getBoundsInScreen） */
    val bounds: RectBounds,
    /** 节点自身是否可点击（AccessibilityNodeInfo.isClickable） */
    val isClickable: Boolean,
    /** 节点自身是否可长按（AccessibilityNodeInfo.isLongClickable） */
    val isLongClickable: Boolean,
    /** 节点当前是否对用户可见（AccessibilityNodeInfo.isVisibleToUser） */
    val visibleToUser: Boolean,
    /** 节点文本（用于「发送/确认」类语义匹配，命中解析本身不使用） */
    val text: String = "",
    /** 节点内容描述（同上） */
    val contentDescription: String = "",
) {
    /** 边界面积（Long 防溢出；退化节点为 0） */
    val area: Long get() = (bounds.right - bounds.left).toLong() * (bounds.bottom - bounds.top).toLong()

    /**
     * 半开区间命中判断 `[left, right) x [top, bottom)`，与 Android 像素语义一致：
     * 恰好落在右/下边界的点属于相邻元素，避免相邻按钮「双命中」。
     */
    fun contains(x: Float, y: Float): Boolean =
        x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom

    companion object {
        const val NO_PARENT = -1
    }
}

/**
 * 无障碍「点击命中」解析器——Agent 点击精度的核心纯逻辑。
 *
 * 背景：在无障碍模式下，手势注入点击按坐标盲打，遇到以下场景会失手——
 * 1. 目标被其他窗口（Toast / 弹窗 / 输入法）部分遮挡，几何中心已不在可点区域；
 * 2. 可点击区域只是节点边界的子集（行容器只有左侧图标可点）；
 * 3. 图标级小目标（< 24dp）或滚动中刚发生位移的列表项；
 * 4. 部分 ROM 对极短注入手势有偶发吞击。
 *
 * 解法：与真实手指一致的「命中即最深节点，再沿父链找可点击祖先」，
 * 直接对命中节点执行 [AccessibilityNodeInfo.ACTION_CLICK] /
 * [AccessibilityNodeInfo.ACTION_LONG_CLICK]，把命中判定交还framework，
 * 从根上消除坐标误差；树信息缺失（WebView / 游戏 / SurfaceView）时由调用方回退手势。
 */
object AccessibilityClickResolver {

    /**
     * 解析坐标处应执行「点击」的节点。
     *
     * 规则：取包含该点的**最深层可见节点**（面积最小；面积相同取遍历更晚者，
     * 即绘制更靠上者），再沿父链向上找第一个自身可点击的节点。
     * 找不到可点击祖先时返回 null（调用方应回退手势注入）。
     */
    fun resolveClickTargetAt(
        pointX: Float,
        pointY: Float,
        nodes: List<ClickableNodeSnapshot>,
    ): ClickableNodeSnapshot? {
        val deepest = deepestVisibleNodeAt(pointX, pointY, nodes) ?: return null
        return walkUpTo(deepest, nodes) { it.isClickable }
    }

    /**
     * 解析坐标处应执行「长按」的节点。
     *
     * 与点击不同：长按只在无障碍树**显式声明**可长按（isLongClickable）时才走节点路径，
     * 否则返回 null 交回手势长按——很多应用用 onTouchEvent 自绘长按却不向无障碍树声明，
     * 盲信节点路径会漏掉这类交互。
     */
    fun resolveLongPressTargetAt(
        pointX: Float,
        pointY: Float,
        nodes: List<ClickableNodeSnapshot>,
    ): ClickableNodeSnapshot? {
        val deepest = deepestVisibleNodeAt(pointX, pointY, nodes) ?: return null
        return walkUpTo(deepest, nodes) { it.isLongClickable }
    }

    /** 取包含该点的最深层可见节点；平级时取遍历更晚（更靠上）者 */
    private fun deepestVisibleNodeAt(
        pointX: Float,
        pointY: Float,
        nodes: List<ClickableNodeSnapshot>,
    ): ClickableNodeSnapshot? = nodes
        .asSequence()
        .filter { it.visibleToUser && it.contains(pointX, pointY) }
        .minWithOrNull(
            compareBy<ClickableNodeSnapshot> { it.area }
                .thenByDescending { it.id },
        )

    /** 从 [start] 起沿父链找第一个满足 [predicate] 的节点（含自身）；防父链环 */
    private fun walkUpTo(
        start: ClickableNodeSnapshot,
        nodes: List<ClickableNodeSnapshot>,
        predicate: (ClickableNodeSnapshot) -> Boolean,
    ): ClickableNodeSnapshot? {
        val byId = nodes.associateBy { it.id }
        var current: ClickableNodeSnapshot = start
        val visited = HashSet<Int>()
        while (true) {
            if (predicate(current)) return current
            if (current.parentId == ClickableNodeSnapshot.NO_PARENT) return null
            if (!visited.add(current.id)) return null // 防御性：父链成环时直接放弃
            val parent = byId[current.parentId] ?: return null
            current = parent
        }
    }
}
