package org.jire.overwatcheat

import org.jire.overwatcheat.nativelib.interception.Interception.interceptionContext
import org.jire.overwatcheat.nativelib.interception.Interception.interceptionMouseStrokeLayout
import org.jire.overwatcheat.nativelib.interception.Interception.interception_send
import org.jire.overwatcheat.nativelib.interception.InterceptionFilter
import org.jire.overwatcheat.nativelib.interception.InterceptionMouseFlag
import java.lang.Thread.sleep
import java.lang.foreign.MemorySegment
import java.lang.foreign.MemorySession
import java.lang.foreign.ValueLayout

object Mouse {

    val mouseStroke = MemorySegment.allocateNative(interceptionMouseStrokeLayout, MemorySession.global()).apply {
        set(ValueLayout.JAVA_SHORT, 0, 0)
        set(ValueLayout.JAVA_SHORT, 2, 0)
        set(ValueLayout.JAVA_SHORT, 4, 0)
        set(ValueLayout.JAVA_INT, 8, 0)
        set(ValueLayout.JAVA_INT, 12, 0)
        set(ValueLayout.JAVA_SHORT, 14, (InterceptionMouseFlag.INTERCEPTION_MOUSE_MOVE_RELATIVE or InterceptionMouseFlag.INTERCEPTION_MOUSE_CUSTOM).toShort())
    }

    fun move(x: Int, y: Int, deviceID: Int) {
        mouseStroke.run {
            set(ValueLayout.JAVA_INT, 8, x)
            set(ValueLayout.JAVA_INT, 12, y)
        }
        interception_send(interceptionContext, deviceID, mouseStroke, 1)
    }

    // 🚀 核心：补充缺失的 press 方法，给 AimBotThread 调用！
    fun press(deviceID: Int) {
        mouseStroke.run {
            set(ValueLayout.JAVA_INT, 0, InterceptionFilter.INTERCEPTION_MOUSE_LEFT_BUTTON_DOWN)
            set(ValueLayout.JAVA_INT, 8, 0)
            set(ValueLayout.JAVA_INT, 12, 0)
        }
        interception_send(interceptionContext, deviceID, mouseStroke, 1)
    }

    // 🚀 核心：补充缺失的 release 方法，给 AimBotThread 调用！
    fun release(deviceID: Int) {
        mouseStroke.run {
            set(ValueLayout.JAVA_INT, 0, InterceptionFilter.INTERCEPTION_MOUSE_LEFT_BUTTON_UP)
            set(ValueLayout.JAVA_INT, 8, 0)
            set(ValueLayout.JAVA_INT, 12, 0)
        }
        interception_send(interceptionContext, deviceID, mouseStroke, 1)
    }

    fun click(deviceID: Int) {
        press(deviceID)
        // 🚀 核心：砍掉 300ms 假死，改为极限 15ms，绝不断触！
        sleep(15)
        release(deviceID)
    }
}