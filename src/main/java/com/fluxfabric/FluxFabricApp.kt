package com.fluxfabric

import android.app.Application
import android.util.Log

class FluxFabricApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val pythonClass = Class.forName("com.chaquo.python.Python")
            val isStarted = pythonClass.getMethod("isStarted").invoke(null) as Boolean
            if (!isStarted) {
                val platformClass = Class.forName("com.chaquo.python.android.AndroidPlatform")
                val platform = platformClass.getConstructor(android.content.Context::class.java).newInstance(this)
                // Python.start(Platform) - parameter type is Python.Platform (superclass of AndroidPlatform)
                val platformSuper = platformClass.superclass
                val startMethod = pythonClass.getMethod("start", platformSuper)
                startMethod.invoke(null, platform)
            }
            Log.i(TAG, "Chaquopy initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Chaquopy not available", e)
        }
    }

    companion object {
        private const val TAG = "FluxFabricApp"
    }
}
