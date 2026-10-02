package com.shilapi.xcertplay.transport
import java.io.IOException
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
}
