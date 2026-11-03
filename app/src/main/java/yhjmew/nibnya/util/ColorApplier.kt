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
                restoreOriginal(v, slot)
                return@forEach
            }

            val color = ColorScheme.value(prefs, slot, night)

            if (v is Button) {
                // 两种背景机制要分开处理：
                //   A) 布局写 backgroundTint="@color/xxx"（复制/保存/编辑/备份等）
                //      -> 改 tint，原 background 不动，保留圆角和按下效果
                //   B) 布局写 background="@color/xxx"（联机玩家/地图/村庄/任意 NBT/全局）
                //      -> 那是纯色，backgroundTint 对它无效，必须直接换背景
                //
                // ⚠️ 之前只处理 A，B 类按钮就「雷打不动」（2026-11-02）。
                if (usesBackgroundTint(v)) {
                    v.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                } else {
                    v.setBackgroundColor(color)
                }
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
                restoreOriginal(v, slot)
                return@forEach
            }
            // 图标块用的是 shape + backgroundTint，不是纯色 background，
            // 直接 setBackgroundColor 会把 drawable 换掉、圆角丢失。
            // 有 tint 的就改 tint，没有的才改 background。
            if (v.backgroundTintList != null || v.background is android.graphics.drawable.GradientDrawable) {
                v.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ColorScheme.value(prefs, slot, night)
                )
            } else {
                v.setBackgroundColor(ColorScheme.value(prefs, slot, night))
            }
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
                else -> v.setBackgroundColor(color)
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
     * 这个控件是否用 backgroundTint 上色。
     *
     * 布局里 `backgroundTint="@color/x"` 会被解析成非空 ColorStateList；
     * 而 `background="@color/x"` 是纯色 ColorDrawable，没有 tint。
     * 依赖 tint 的控件，改 background 会丢掉圆角状态，必须改 tint。
     */
    private fun usesBackgroundTint(v: View): Boolean =
        v.backgroundTintList != null

    /**
     * 把控件还原成「布局里写的那个颜色」。
     *
     * ⚠️ 早期版本用 WeakHashMap 缓存 View 的原始 background/tint，有两个坑：
     *
     *   1. `restoreOriginal` 用 `remove()` 取一次就删，下次上色又记一次，
     *      而这时 View 上残留的是上一次覆盖的颜色 —— 被当成原值存进缓存。
     *   2. Activity 与 Dialog 共用同一个 object 的缓存，
     *      两边的同一批控件（若 id 相同）会互相覆盖对方的「原值」。
     *
     * 结果：切主题时某些按钮「雷打不动」，切回默认时又回不到原样。
     *
     * 现在改成不做缓存 —— 直接把这个 slot 对应的**资源默认值**取出来写回去。
     * slot 里已经带着 `defaultResId`（已解析的 ARGB），见 ColorScheme.Slot。
     */
    private fun restoreOriginal(v: View, slot: ColorScheme.Slot) {
        if (usesBackgroundTint(v)) {
            v.backgroundTintList = android.content.res.ColorStateList.valueOf(slot.defaultResId)
        } else {
            v.setBackgroundColor(slot.defaultResId)
        }
        if (v is TextView) v.setTextColor(ColorScheme.onColor(slot.defaultResId))
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
