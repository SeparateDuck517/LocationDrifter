package com.separ.locationdrifter.xposed

/**
 * 用于检测 LSPosed 模块是否被激活
 */
object ModuleStatus {
    @JvmStatic
    fun isModuleActive(): Boolean = false
}
