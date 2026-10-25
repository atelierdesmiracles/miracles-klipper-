package com.octo4a.serial

interface VSPListener {
    fun onDataReceived(data: SerialData?)
}
