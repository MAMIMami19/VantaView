// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.aisubs

/** The whisper.cpp speech engine (built from src/main/cpp). */
object WhisperNative {
    /** False when the native library isn't in this APK or can't load on this device. */
    val available: Boolean = runCatching { System.loadLibrary("vvwhisper") }.isSuccess

    external fun init(modelPath: String): Long
    external fun free(ctx: Long)
    /** Returns "<language>\n<text>" for 16 kHz mono samples. */
    external fun transcribe(ctx: Long, samples: FloatArray, translate: Boolean, threads: Int, prompt: String?): String
}
