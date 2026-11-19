package com.bringtech.ft600

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Conexão com o scanner ELM327: Bluetooth clássico (SPP) ou BLE.
 * Os dois modos entregam bytes para o mesmo buffer; send() manda um comando e espera o ">".
 */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
class ObdLink(private val context: Context) {

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val buf = StringBuilder()
    private var writer: ((ByteArray) -> Boolean)? = null
    private var closer: (() -> Unit)? = null

    @Volatile
    var connected = false
        private set

    private var gatt: BluetoothGatt? = null
    @Volatile private var bleRx: BluetoothGattCharacteristic? = null
    @Volatile private var bleTx: BluetoothGattCharacteristic? = null
    @Volatile private var bleOk = false

    private fun feed(data: ByteArray, len: Int) {
        synchronized(buf) { buf.append(String(data, 0, len, Charsets.ISO_8859_1)) }
    }

    private fun clean(s: String): String = s.replace("\u0000", "").trim()

    /** Envia um comando (sem o \r) e devolve a resposta até o prompt ">". */
    @Throws(IOException::class)
    fun send(cmd: String, timeoutMs: Long = 2500): String {
        synchronized(buf) { buf.setLength(0) }
        val w = writer ?: throw IOException("sem conexão")
        if (!w((cmd + "\r").toByteArray())) throw IOException("falha ao enviar")
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            synchronized(buf) {
                val i = buf.indexOf(">")
                if (i >= 0) {
                    val s = buf.substring(0, i)
                    buf.setLength(0)
                    return clean(s)
                }
            }
            Thread.sleep(15)
        }
        val s = synchronized(buf) {
            val t = buf.toString()
            buf.setLength(0)
            t
        }
        return clean(s)
    }

    // ---------------- Bluetooth clássico ----------------

    private fun openRfcomm(device: BluetoothDevice, fallback: Boolean): BluetoothSocket? {
        var s: BluetoothSocket? = null
        try {
            val sock: BluetoothSocket = if (!fallback) {
                device.createRfcommSocketToServiceRecord(SPP)
            } else {
                device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            }
            s = sock
            sock.connect()
            return sock
        } catch (e: Exception) {
            try { s?.close() } catch (_: Exception) {}
            return null
        }
    }

    fun connectClassic(device: BluetoothDevice): Boolean {
        close()
        try { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } catch (_: Exception) {}
        val sock = openRfcomm(device, false) ?: openRfcomm(device, true) ?: return false
        val inp = sock.inputStream
        val out = sock.outputStream
        writer = { b ->
            try { out.write(b); out.flush(); true } catch (e: IOException) { false }
        }
        closer = { try { sock.close() } catch (_: Exception) {} }
        connected = true
        val t = Thread {
            val tmp = ByteArray(512)
            try {
                while (true) {
                    val n = inp.read(tmp)
                    if (n < 0) break
                    feed(tmp, n)
                }
            } catch (_: Exception) {
            }
            connected = false
        }
        t.isDaemon = true
        t.start()
        return true
    }

    // ---------------- BLE ----------------

    fun connectBle(device: BluetoothDevice): Boolean {
        close()
        bleRx = null
        bleTx = null
        bleOk = false
        val ready = CountDownLatch(1)

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected = false
                    ready.countDown()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                var rx: BluetoothGattCharacteristic? = null
                var tx: BluetoothGattCharacteristic? = null
                val notifyMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE
                val writeMask = BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                for (svc in g.services) {
                    val u = svc.uuid.toString().lowercase()
                    if (u.startsWith("00001800") || u.startsWith("00001801") || u.startsWith("0000180a")) continue
                    for (ch in svc.characteristics) {
                        val p = ch.properties
                        val n = (p and notifyMask) != 0
                        val w = (p and writeMask) != 0
                        if (n && w) {
                            rx = ch
                            tx = ch
                        } else {
                            if (n && rx == null) rx = ch
                            if (w && tx == null) tx = ch
                        }
                    }
                }
                val r = rx
                val t = tx
                if (r == null || t == null) {
                    ready.countDown()
                    return
                }
                bleRx = r
                bleTx = t
                g.setCharacteristicNotification(r, true)
                val d = r.getDescriptor(CCCD)
                if (d == null) {
                    bleOk = true
                    ready.countDown()
                    return
                }
                d.value = if ((r.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                g.writeDescriptor(d)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                bleOk = status == BluetoothGatt.GATT_SUCCESS
                ready.countDown()
            }

            // Android 13+
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                feed(value, value.size)
            }

            // Android 12 ou menor
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) {
                    val v = ch.value
                    if (v != null) feed(v, v.size)
                }
            }
        }

        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        val done = ready.await(12, TimeUnit.SECONDS)
        if (!done || !bleOk || bleTx == null) {
            close()
            return false
        }
        writer = { b ->
            val t = bleTx
            val g = gatt
            if (t == null || g == null) {
                false
            } else {
                var ok = true
                var i = 0
                while (i < b.size && ok) {
                    val chunk = b.copyOfRange(i, minOf(i + 20, b.size))
                    t.writeType = if ((t.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    else
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    t.value = chunk
                    ok = g.writeCharacteristic(t)
                    i += 20
                    if (ok && i < b.size) Thread.sleep(30)
                }
                ok
            }
        }
        connected = true
        return true
    }

    fun close() {
        connected = false
        writer = null
        try { closer?.invoke() } catch (_: Exception) {}
        closer = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
        gatt = null
        bleRx = null
        bleTx = null
        synchronized(buf) { buf.setLength(0) }
    }
}
