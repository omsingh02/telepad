package com.omsingh.telepad.core.wifi

class UdpSender {

    companion object {
        init {
            System.loadLibrary("telepad")
        }
    }

    fun connect(ipAddress: String, port: Int): Boolean {
        return nativeConnect(ipAddress, port)
    }

    fun disconnect() {
        nativeDisconnect()
    }

    external fun nativeConnect(ipAddress: String, port: Int): Boolean
    external fun nativeDisconnect()
    
    external fun nativeSendMouseMove(dx: Int, dy: Int)
    external fun nativeSendMouseButton(button: Int, pressed: Boolean)
    external fun nativeSendScroll(delta: Int)
    external fun nativeSendKey(pressed: Boolean, keycode: Int, modifiers: Int)
    external fun nativeSendMedia(action: Int)
    external fun nativeSendVolume(direction: Int)
    external fun nativeSendLockScreen()
}
