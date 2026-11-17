package com.separ.locationdrifter.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 提供只读 ContentProvider，便于调试或其他受限环境查询当前选点配置
 */
class LocationConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val cfg = ConfigManager.load(ctx)
        val cursor = MatrixCursor(
            arrayOf(
                ConfigManager.KEY_ENABLED,
                ConfigManager.KEY_WGS_LAT,
                ConfigManager.KEY_WGS_LNG,
                ConfigManager.KEY_GCJ_LAT,
                ConfigManager.KEY_GCJ_LNG,
                ConfigManager.KEY_ADDRESS,
                ConfigManager.KEY_BLOCK_NETWORK_DRIFT,
                ConfigManager.KEY_JITTER,
                ConfigManager.KEY_UPDATED_AT
            )
        )
        cursor.addRow(
            arrayOf<Any>(
                if (cfg.enabled) 1 else 0,
                cfg.wgsLat,
                cfg.wgsLng,
                cfg.gcjLat,
                cfg.gcjLng,
                cfg.address,
                if (cfg.blockNetworkDrift) 1 else 0,
                if (cfg.enableJitter) 1 else 0,
                cfg.updatedAt
            )
        )
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.com.separ.locationdrifter.config"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
