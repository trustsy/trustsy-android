package com.trustsy.verify.internal.dipchip

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume

/**
 * A USB smart-card reader (CCID) through Android's USB host API: find it, ask
 * the user for access, claim its interface. Works with any class-0x0B reader
 * on a USB-C / OTG port; the protocol itself is [Ccid].
 */
internal class UsbCcidReader(private val context: Context) {
    private val usb: UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** False on a device without USB host mode — no reader can ever attach. */
    val hostSupported: Boolean
        get() = usb != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    /** The first attached smart-card reader, if any. */
    fun find(): UsbDevice? = usb?.deviceList?.values?.firstOrNull { smartCardInterface(it) != null }

    fun hasPermission(device: UsbDevice): Boolean = usb?.hasPermission(device) == true

    /** Show the system "allow access to this reader" dialog; true when granted. */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        val manager = usb ?: return false
        if (manager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val action = "${context.packageName}.trustsy.USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    runCatching { context.unregisterReceiver(this) }
                    // Re-ask the manager rather than trusting the extra: some OEM
                    // builds deliver the broadcast without EXTRA_PERMISSION_GRANTED.
                    if (cont.isActive) cont.resume(manager.hasPermission(device))
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            // MUTABLE: the system fills in the device and the answer. The
            // package makes it explicit, which Android 14 requires of a mutable one.
            val intent = Intent(action).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
            manager.requestPermission(device, pending)
        }
    }

    /** Claim the reader. Throws [DipChipException] (`reader_unsupported`, `reader_io`). */
    fun open(device: UsbDevice): CcidReaderSession {
        val manager = usb ?: throw DipChipException(DipChipException.READER_IO, "USB host is not available")
        val iface = smartCardInterface(device)
            ?: throw DipChipException(DipChipException.READER_UNSUPPORTED, "not a smart card reader")
        val (bulkIn, bulkOut) = bulkEndpoints(iface)
            ?: throw DipChipException(DipChipException.READER_UNSUPPORTED, "the reader has no bulk endpoints")
        val connection = manager.openDevice(device)
            ?: throw DipChipException(DipChipException.READER_IO, "could not open the reader")
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            throw DipChipException(DipChipException.READER_IO, "the reader is in use")
        }
        val frames = connection.rawDescriptors?.let { CcidDescriptor.exchangesFrames(it, iface.id) }
        if (frames == false) {
            connection.releaseInterface(iface)
            connection.close()
            throw DipChipException(DipChipException.READER_UNSUPPORTED, "character-level readers are not supported")
        }
        return CcidReaderSession(connection, iface, Ccid(UsbBulkPipe(connection, bulkIn, bulkOut)), device.productName)
    }

    private fun smartCardInterface(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
            it.interfaceClass == UsbConstants.USB_CLASS_CSCID
        }

    private fun bulkEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until iface.endpointCount).map(iface::getEndpoint)
            .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val bulkIn = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return null
        val bulkOut = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return null
        return bulkIn to bulkOut
    }
}

/** A claimed reader. Not thread-safe: one coroutine drives it at a time. */
internal class CcidReaderSession(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val ccid: Ccid,
    val name: String?,
) : Closeable {
    fun status(): Ccid.IccStatus = ccid.status()

    /** Power the card and hand back a transport for [ThaiIdCard.read]. */
    fun powerOn(): CardTransport {
        val atr = ccid.powerOn()
        return object : CardTransport {
            override val atr: ByteArray = atr
            override fun transmit(command: ByteArray): ByteArray = ccid.transmit(command)
        }
    }

    override fun close() {
        runCatching { ccid.powerOff() }
        runCatching { connection.releaseInterface(iface) }
        connection.close()
    }
}

private class UsbBulkPipe(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : Ccid.BulkPipe {
    override fun write(bytes: ByteArray) {
        var sent = 0
        while (sent < bytes.size) {
            val n = connection.bulkTransfer(bulkOut, bytes, sent, bytes.size - sent, WRITE_TIMEOUT_MS)
            if (n <= 0) throw DipChipException(DipChipException.READER_IO, "could not write to the reader")
            sent += n
        }
    }

    override fun read(timeoutMs: Int): ByteArray {
        val buffer = ByteArray(bulkIn.maxPacketSize.coerceAtLeast(64))
        val n = connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMs)
        return if (n <= 0) ByteArray(0) else buffer.copyOf(n)
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 5_000
    }
}
