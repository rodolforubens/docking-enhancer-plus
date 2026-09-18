package com.odininputmirror.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal class AndroidBluetoothAliases(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun byAddress(): Map<String, String> {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyMap()
        }

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return emptyMap()
        return runCatching {
            adapter.bondedDevices.mapNotNull { device ->
                val label = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    device.alias
                } else {
                    device.name
                }
                label?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                    normalizeBluetoothAddress(device.address) to name
                }
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}

internal fun normalizeBluetoothAddress(address: String): String = address.trim().lowercase()
