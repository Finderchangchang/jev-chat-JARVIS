package com.jev.probe.core

/**
 * What an aggressive OEM build (HyperOS / MIUI, and the equivalents) needs before
 * the assistant actually survives in the background — and how the setup screen
 * should report it.
 *
 * Pure Kotlin with no Android imports on purpose: the verdict and the route table
 * are unit tested ([PowerSetupTest]); the intents that open these screens are
 * built in the Activity, where a failing component can be caught and the next
 * one tried.
 */
object PowerSetup {

    /**
     * Where "自启动 / autostart" hides. There is no public API for it, every ROM
     * puts it somewhere else, and the entry point moves between releases, so the
     * setup screen tries these in order and falls back to the app's own details
     * page. Treat the component names as best-effort, never as a guarantee.
     */
    val AUTOSTART_ROUTES: List<AutostartRoute> = listOf(
        AutostartRoute(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "MIUI / HyperOS 自启动管理"),
        AutostartRoute(
            "com.miui.securitycenter",
            "com.miui.powercenter.PowerSettings",
            "MIUI / HyperOS 省电策略"),
        AutostartRoute(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "ColorOS 自启动管理"),
        AutostartRoute(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "OriginOS 自启动管理"),
        AutostartRoute(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "EMUI 自启动管理"),
        AutostartRoute(
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity",
            "One UI 电池")
    )

    data class AutostartRoute(val pkg: String, val cls: String, val label: String)

    /** The four things that must hold before the assistant can work at all. */
    data class Readiness(
        val accessibility: Boolean,
        val overlay: Boolean,
        val key: Boolean,
        val batteryUnrestricted: Boolean
    ) {
        /**
         * Battery is part of "ready" on purpose. An accessibility service the ROM
         * freezes within seconds is indistinguishable from a broken app, and the
         * setup screen used to report "已就绪" without checking it at all — the
         * exact state a HyperOS user lands in.
         */
        val ready: Boolean get() = accessibility && overlay && key && batteryUnrestricted

        /** Labels for whatever is still missing, in the order it has to be done. */
        val missing: List<String>
            get() = buildList {
                if (!accessibility) add("无障碍权限")
                if (!overlay) add("悬浮窗权限")
                if (!key) add("判断接口密钥")
                if (!batteryUnrestricted) add("省电无限制")
            }
    }

    fun verdict(
        accessibility: Boolean,
        overlay: Boolean,
        key: Boolean,
        batteryUnrestricted: Boolean
    ): Readiness = Readiness(accessibility, overlay, key, batteryUnrestricted)
}
