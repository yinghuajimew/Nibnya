package yhjmew.nibnya.util

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import yhjmew.nibnya.R

/**
 * 按当前配色方案给界面刷色（2026-11-02）。
 *
 * **必须在 `setContentView` 之后、界面稳定之前调用一次**，
 * 否则用户改完颜色回到主页，看到的还是布局里那份默认色。
 *
 * 处理三类东西：
 * 1. 纯色块 View（`ColorSlots.viewIds`）—— `setBackgroundColor`
 * 2. 按钮（`ColorSlots.buttonIds`）—— 用 `ColorDrawable` 覆盖 backgroundTint
 *    因为 `backgroundTint` 是布局属性，运行时改要换 tint list
 * 3. 圆形徽章 —— 保留 oval 形状，只换 solid 色
 *
 * 文字颜色：深底配白字、浅底配深字，避免用户选了浅黄之后白字看不见。
 * 这是 [ColorScheme.onColor] 的职责。
 */
object ColorApplier {

    fun apply(activity: Activity, prefs: SharedPreferences) {
        val ctx: Context = activity
        val night = ColorScheme.isNight(ctx)
        val slots = ColorSlots.build(ctx)
        val byKey = slots.associateBy { it.key }

        // ── 1) 按钮类 ──
        ColorSlots.buttonIds.forEach { (key, viewId) ->
            val slot = byKey[key] ?: return@forEach
            val v = activity.findViewById<View?>(viewId) ?: return@forEach

            // 只有用户真的改过这一项才覆盖；没改过就不碰，
            // 让布局里的 android:background / backgroundTint 原样生效。
            // ⚠️ 早期版本无条件覆盖，把 Material 按钮自带的圆角状态背景
            //    整个抹掉，按钮变成直角（2026-11-02 修）。
            if (!ColorScheme.isModified(prefs, slot, night)) {
                restoreOriginal(v)
                return@forEach
            }

            val color = ColorScheme.value(prefs, slot, night)
            rememberOriginal(v)

            if (v is Button) {
                v.background = roundedDrawable(activity, color, cornerDp = 2f)
                v.backgroundTintList = null
                v.setTextColor(ColorScheme.onColor(color))
            } else {
                v.setBackgroundColor(color)
                if (v is android.view.ViewGroup) {
                    applyTextColorRecursive(v, ColorScheme.onColor(color))
                }
            }
        }

        // ── 2) 纯色块 View ──
        ColorSlots.viewIds.forEach { (key, viewId) ->
            val slot = byKey[key] ?: return@forEach
            val v = activity.findViewById<View?>(viewId) ?: return@forEach
            if (!ColorScheme.isModified(prefs, slot, night)) {
                restoreOriginal(v)
                return@forEach
            }
            rememberOriginal(v)
            v.setBackgroundColor(ColorScheme.value(prefs, slot, night))
        }

        // ── 3) 圆形徽章：保留 oval，只换 solid ──
        run {
            val settingsBadge = activity.findViewById<View?>(R.id.badge_settings_circle)
            val aboutBadge = activity.findViewById<View?>(R.id.badge_about_circle)
            // 这个布局在对话框里，不属于 Activity，由 EditorDialogHelper 单独处理
        }
    }

    /**
     * 给一个具体的控件列表套上某个色槽的颜色。
     *
     * 用于「同一个色槽影响多处」的场景，例如 c_accent 同时作用于
     * 标签栏、勾选框、链接文字 —— 这些不在 [ColorSlots.buttonIds] 里，
     * 因为它们不是「一个有 id 的按钮」，而是散落在各处的通用元素。
     */
    fun applyToViews(prefs: SharedPreferences, night: Boolean, key: String, views: List<View?>) {
        val slot = ColorSlots.build(views.firstOrNull()?.context ?: return)
            .firstOrNull { it.key == key } ?: return
        if (!ColorScheme.isModified(prefs, slot, night)) return
        val color = ColorScheme.value(prefs, slot, night)
        views.forEach { v ->
            when (v) {
                null -> {}
                is TextView -> v.setTextColor(color)
                else -> {
                    rememberOriginal(v)
                    v.setBackgroundColor(color)
                }
            }
        }
    }

    /** 只刷 Activity 自身布局里的东西；对话框另走 [applyToDialogView] */
    fun applyToDialogView(
        root: View,
        prefs: SharedPreferences,
        night: Boolean,
        settingsBadgeRes: Int,
        aboutBadgeRes: Int,
    ) {
        val ctx = root.context
        val slots = ColorSlots.build(ctx).associateBy { it.key }

        // 同样只在用户改过该项时才覆盖，否则保留 drawable 里的原始观感
        fun changed(key: String): Int? {
            val slot = slots[key] ?: return null
            return if (ColorScheme.isModified(prefs, slot, night))
                ColorScheme.value(prefs, slot, night) else null
        }

        changed("c_badge_settings")?.let { c ->
            root.findViewById<View?>(settingsBadgeRes)?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
            }
        }
        changed("c_badge_about")?.let { c ->
            root.findViewById<View?>(aboutBadgeRes)?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
            }
        }

        // 顶部渐变头：两端都改过才重画，只改一端也照样生效
        val startSlot = slots["c_header_green"]
        val endSlot = slots["c_header_green_dark"]
        val startChanged = startSlot != null && ColorScheme.isModified(prefs, startSlot, night)
        val endChanged = endSlot != null && ColorScheme.isModified(prefs, endSlot, night)
        if ((startChanged || endChanged) && startSlot != null && endSlot != null) {
            val start = ColorScheme.value(prefs, startSlot, night)
            val end = ColorScheme.value(prefs, endSlot, night)
            root.findViewById<View?>(R.id.dialog_color_header)?.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(start, end),
            ).apply {
                cornerRadii = floatArrayOf(
                    dp(ctx, 8f), dp(ctx, 8f), dp(ctx, 8f), dp(ctx, 8f),
                    0f, 0f, 0f, 0f,
                )
            }
        }
    }

    /**
     * 把控件背景还原成布局里定义的原始资源。
     *
     * 为什么需要：用户改过颜色 -> 被覆盖 -> 再点「恢复默认」，
     * 此时 `isModified` 变回 false，如果只是「跳过不处理」，
     * View 上还留着上次覆盖的背景，永远回不去（2026-11-02）。
     *
     * 用 WeakHashMap 记录原始背景，第一次覆盖前记下，还原时取回。
     * （不能用 View.setTag(String, ...) —— 那个 key 必须是资源 id（Int），传字符串编译不过）
     */
    private val originals = java.util.WeakHashMap<View, android.graphics.drawable.Drawable?>()

    private fun rememberOriginal(v: View) {
        if (originals.containsKey(v)) return
        originals[v] = v.background
    }

    private fun restoreOriginal(v: View) {
        if (!originals.containsKey(v)) return
        v.background = originals.remove(v)
    }

    /** 圆角纯色 drawable —— 用于替代 Material 按钮背景时保住圆角 */
    private fun roundedDrawable(ctx: Context, color: Int, cornerDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerDp * ctx.resources.displayMetrics.density
            setColor(color)
        }

    private fun applyTextColorRecursive(v: android.view.ViewGroup, color: Int) {
        for (i in 0 until v.childCount) {
            when (val child = v.getChildAt(i)) {
                is TextView -> child.setTextColor(color)
                is android.view.ViewGroup -> applyTextColorRecursive(child, color)
            }
        }
    }

    private fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
}
