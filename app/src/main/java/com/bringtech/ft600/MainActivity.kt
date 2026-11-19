package com.bringtech.ft600

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

@SuppressLint("MissingPermission")
class Dev(val device: BluetoothDevice, val ble: Boolean, val bonded: Boolean) {
    val key: String get() = device.address + ble

    fun name(): String {
        val n: String? = try { device.name } catch (e: SecurityException) { null }
        return if (n.isNullOrBlank()) "(sem nome)" else n
    }

    fun hasName(): Boolean = name() != "(sem nome)"

    fun looksObd(): Boolean {
        val n = name().uppercase()
        return listOf("OBD", "ELM", "VLINK", "V-LINK", "VGATE", "HH", "CAN", "KONNWEI", "VEEPEAK", "SCAN", "LELINK", "ICAR")
            .any { n.contains(it) }
    }

    fun score(): Int = (if (looksObd()) 3 else 0) + (if (bonded) 2 else 0)

    fun label(): String =
        (if (looksObd()) "★ " else "") + name() + "\n" + device.address + " · " +
            (if (ble) "BLE" else "Bluetooth clássico") + (if (bonded) " · pareado" else "")
}

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val busLock = Mutex()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var link: ObdLink
    private var adapter: BluetoothAdapter? = null

    private lateinit var root: FrameLayout
    private lateinit var connectView: LinearLayout
    private lateinit var dashView: LinearLayout
    private lateinit var status: TextView
    private lateinit var scanBtn: Button
    private lateinit var listAdapter: ArrayAdapter<String>
    private lateinit var gauge: GaugeView
    private lateinit var milLabel: TextView

    private val devices = ArrayList<Dev>()
    private val labels = ArrayList<String>()
    private val tiles = HashMap<String, TextView>()

    private var scanning = false
    private var connecting = false
    private var pollJob: Job? = null

    @Volatile private var isCan = false
    @Volatile private var lastRpm = 0f

    private val stopScanRunnable = Runnable { stopScan() }

    // ------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        link = ObdLink(this)
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        listAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val t = super.getView(position, convertView, parent) as TextView
                t.setTextColor(Color.WHITE)
                t.textSize = 15f
                return t
            }
        }

        root = FrameLayout(this)
        root.setBackgroundColor(Cores.BG)
        connectView = buildConnect()
        dashView = buildDash()
        root.addView(connectView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(dashView, FrameLayout.LayoutParams(MATCH, MATCH))
        dashView.visibility = View.GONE
        setContentView(root)

        val f = IntentFilter()
        f.addAction(BluetoothDevice.ACTION_FOUND)
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }

        setStatus("Ligue o scanner OBD2 na tomada do carro e toque em PROCURAR.")
        if (hasPerms()) startScan() else askPerms()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        stopScan()
        pollJob?.cancel()
        link.close()
        scope.cancel()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (dashView.visibility == View.VISIBLE) {
            showConnect()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------ permissões

    private fun neededPerms(): Array<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_SCAN)
            l.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return l.toTypedArray()
    }

    private fun hasPerms(): Boolean =
        neededPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun askPerms() {
        requestPermissions(neededPerms(), 10)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (hasPerms()) startScan()
            else setStatus("Sem permissão de Bluetooth. Libere nas configurações do app para procurar o scanner.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20 && resultCode == RESULT_OK) startScan()
    }

    // ------------------------------------------------------------ busca de aparelhos

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = deviceFrom(i)
                    if (d != null) {
                        addDevice(d, d.type == BluetoothDevice.DEVICE_TYPE_LE, d.bondState == BluetoothDevice.BOND_BONDED)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // o BLE continua até o timeout
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private val bleCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device ?: return
            val nome: String? = result.scanRecord?.deviceName ?: (try { d.name } catch (e: SecurityException) { null })
            if (nome.isNullOrBlank()) return // BLE sem nome = ruído
            addDevice(d, true, d.bondState == BluetoothDevice.BOND_BONDED)
        }
    }

    private fun addDevice(d: BluetoothDevice, ble: Boolean, bonded: Boolean) {
        val dev = Dev(d, ble, bonded)
        if (devices.any { it.key == dev.key }) return
        devices.add(dev)
        devices.sortWith(compareByDescending<Dev> { it.score() }.thenBy { it.name() })
        refreshList()
    }

    private fun refreshList() {
        labels.clear()
        labels.addAll(devices.map { it.label() })
        listAdapter.notifyDataSetChanged()
    }

    private fun startScan() {
        val a = adapter
        if (a == null) {
            setStatus("Este celular não tem Bluetooth.")
            return
        }
        if (!a.isEnabled) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 20)
            return
        }
        if (!hasPerms()) {
            askPerms()
            return
        }
        stopScan()
        devices.clear()
        refreshList()
        for (d in a.bondedDevices) {
            addDevice(d, d.type == BluetoothDevice.DEVICE_TYPE_LE, true)
        }
        scanning = true
        scanBtn.text = "PROCURANDO…"
        setStatus("Procurando aparelhos (Bluetooth clássico e BLE)… uns 12 segundos.")
        try { a.startDiscovery() } catch (_: Exception) {}
        try { a.bluetoothLeScanner?.startScan(bleCb) } catch (_: Exception) {}
        ui.removeCallbacks(stopScanRunnable)
        ui.postDelayed(stopScanRunnable, 12000)
    }

    private fun stopScan() {
        ui.removeCallbacks(stopScanRunnable)
        val a = adapter
        try { a?.cancelDiscovery() } catch (_: Exception) {}
        try { a?.bluetoothLeScanner?.stopScan(bleCb) } catch (_: Exception) {}
        if (scanning) {
            scanning = false
            if (::scanBtn.isInitialized) scanBtn.text = "PROCURAR APARELHOS"
            if (devices.isEmpty()) {
                setStatus("Nada encontrado. Confira se o scanner está na tomada com a chave ligada. (Android 11 ou menor: ligue a Localização.)")
            } else {
                setStatus("Toque no seu scanner (ex.: OBDII) para conectar.")
            }
        }
    }

    // ------------------------------------------------------------ conexão

    private fun connectTo(dev: Dev) {
        if (connecting) return
        connecting = true
        stopScan()
        setStatus("Conectando em ${dev.name()}… (pode levar uns segundos)")
        scope.launch {
            val err: String? = withContext(Dispatchers.IO) {
                try {
                    val ok = if (dev.ble) link.connectBle(dev.device) else link.connectClassic(dev.device)
                    if (!ok) {
                        "Não consegui conectar. Se pedir senha, use 1234. Se for um aparelho que aparece duas vezes, tente a outra entrada (clássico/BLE)."
                    } else {
                        initElm()
                    }
                } catch (e: Exception) {
                    "Erro: ${e.message}"
                }
            }
            connecting = false
            if (err == null) {
                showDash()
                startPolling()
            } else {
                link.close()
                setStatus(err)
            }
        }
    }

    /** Devolve null se deu certo, ou o texto do erro. */
    private fun initElm(): String? {
        val z = link.send("ATZ", 4000)
        val e = link.send("ATE0")
        if (z.isBlank() && e.isBlank()) return "Conectou, mas o aparelho não respondeu como scanner OBD2 (ELM327)."
        link.send("ATL0")
        link.send("ATS0")
        link.send("ATH0")
        link.send("ATAT1")
        link.send("ATSP0")
        val r = link.send("0100", 10000)
        if (Elm.hexBytes(r, "4100") == null) {
            return if (r.uppercase().contains("UNABLE") || r.uppercase().contains("ERROR"))
                "O scanner conectou, mas não conseguiu falar com a ECU. Ligue a chave (ignição) e tente de novo."
            else
                "O scanner conectou, mas o carro não respondeu. Ligue a chave (ignição) e tente de novo."
        }
        val p = link.send("ATDPN").replace(" ", "").uppercase().removePrefix("A")
        isCan = p.isNotEmpty() && p[0] in '6'..'9'
        return null
    }

    private fun showDash() {
        for (t in tiles.values) t.text = "--"
        gauge.setValue(0f)
        milLabel.text = "LUZ DO PAINEL: --"
        milLabel.setTextColor(Cores.GRAY)
        connectView.visibility = View.GONE
        dashView.visibility = View.VISIBLE
    }

    private fun showConnect() {
        pollJob?.cancel()
        link.close()
        dashView.visibility = View.GONE
        connectView.visibility = View.VISIBLE
        setStatus("Desconectado. Toque no scanner para conectar de novo.")
    }

    // ------------------------------------------------------------ leitura ao vivo

    private suspend fun pid(cmd: String, prefix: String): IntArray? =
        busLock.withLock { Elm.hexBytes(link.send(cmd, 1500), prefix) }

    private fun setTile(key: String, value: String) {
        runOnUiThread { tiles[key]?.text = value }
    }

    private fun updateMil(m: MilInfo) {
        runOnUiThread {
            milLabel.text = (if (m.milOn) "LUZ DO PAINEL: ACESA" else "LUZ DO PAINEL: apagada") +
                " · ${m.count} falha(s)"
            milLabel.setTextColor(if (m.milOn) Cores.RED else Cores.GREEN)
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            try {
                var n = 0
                while (isActive && link.connected) {
                    val rpm = pid("010C", "410C")
                    if (rpm != null && rpm.size >= 2) {
                        val v = (rpm[0] * 256 + rpm[1]) / 4f
                        lastRpm = v
                        runOnUiThread { gauge.setValue(v) }
                    } else {
                        delay(200)
                    }

                    val sp = pid("010D", "410D")
                    if (sp != null && sp.isNotEmpty()) setTile("speed", "${sp[0]}")

                    when (n % 6) {
                        0 -> pid("0105", "4105")?.let { if (it.isNotEmpty()) setTile("temp", "${it[0] - 40}") }
                        1 -> pid("0104", "4104")?.let { if (it.isNotEmpty()) setTile("load", "${it[0] * 100 / 255}") }
                        2 -> pid("0111", "4111")?.let { if (it.isNotEmpty()) setTile("tps", "${it[0] * 100 / 255}") }
                        3 -> pid("010F", "410F")?.let { if (it.isNotEmpty()) setTile("iat", "${it[0] - 40}") }
                        4 -> pid("0142", "4142")?.let {
                            if (it.size >= 2) {
                                val volts = (it[0] * 256 + it[1]) / 1000.0
                                setTile("volt", String.format(Locale.US, "%.1f", volts))
                            }
                        }
                        5 -> pid("0101", "4101")?.let { b -> Elm.milFromBytes(b)?.let { m -> updateMil(m) } }
                    }
                    n++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // caiu a conexão
            }
            if (isActive) {
                withContext(Dispatchers.Main) {
                    toast("Conexão perdida com o scanner")
                    showConnect()
                }
            }
        }
    }

    // ------------------------------------------------------------ LER / APAGAR FALHAS

    private fun readDtcs() {
        if (!link.connected) {
            toast("Sem conexão")
            return
        }
        scope.launch {
            val msg: String = withContext(Dispatchers.IO) {
                try {
                    busLock.withLock {
                        val mil = Elm.parseMil(link.send("0101", 2000))
                        val raw = link.send("03", 6000)
                        val codes = Elm.parseDtcs(raw, isCan)
                        val sb = StringBuilder()
                        if (mil != null) {
                            sb.append("Luz do painel (MIL): ")
                                .append(if (mil.milOn) "ACESA" else "apagada")
                                .append("\nFalhas informadas pela ECU: ")
                                .append(mil.count)
                                .append("\n\n")
                            updateMil(mil)
                        }
                        if (codes.isEmpty()) sb.append("Nenhuma falha armazenada.")
                        else sb.append(codes.joinToString("\n"))
                        sb.append("\n\n(resposta bruta: ")
                            .append(raw.replace("\r", " ").replace("\n", " ").trim())
                            .append(")")
                        sb.toString()
                    }
                } catch (e: Exception) {
                    "Erro de comunicação: ${e.message}"
                }
            }
            dialog("Falhas (DTC)", msg)
        }
    }

    private fun confirmClear() {
        if (!link.connected) {
            toast("Sem conexão")
            return
        }
        var aviso = ""
        if (lastRpm > 300f) {
            aviso = "\n\n⚠ O motor parece estar ligado (${lastRpm.toInt()} rpm). Muitos carros só aceitam apagar com a chave ligada e o motor DESLIGADO."
        }
        AlertDialog.Builder(this)
            .setTitle("Apagar falhas e luz do painel")
            .setMessage(
                "Isso apaga os códigos de falha, o freeze frame e zera os monitores de prontidão " +
                    "(o carro pode precisar rodar um ciclo de condução antes de uma inspeção).\n\n" +
                    "Se o defeito ainda existir, a luz volta a acender." + aviso + "\n\nContinuar?"
            )
            .setPositiveButton("APAGAR") { _, _ -> doClear() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun doClear() {
        scope.launch {
            val msg: String = withContext(Dispatchers.IO) {
                try {
                    busLock.withLock {
                        val r = link.send("04", 5000)
                        when (Elm.clearResult(r)) {
                            ClearResult.OK -> {
                                Thread.sleep(800)
                                val mil = Elm.parseMil(link.send("0101", 2000))
                                if (mil != null) updateMil(mil)
                                if (mil == null) {
                                    "Comando aceito pela ECU. Não consegui confirmar o estado da luz."
                                } else if (!mil.milOn && mil.count == 0) {
                                    "Pronto! Falhas apagadas e luz do painel desligada ✅"
                                } else {
                                    "Comando aceito, mas a ECU ainda informa ${mil.count} falha(s)" +
                                        (if (mil.milOn) " e luz acesa" else "") +
                                        ". O defeito pode continuar presente."
                                }
                            }
                            ClearResult.REFUSED ->
                                "A ECU recusou apagar. Deixe a chave ligada com o motor desligado e tente de novo."
                            ClearResult.NONE ->
                                "Sem resposta da ECU ao comando de apagar. Confira a ignição e a conexão.\n(resposta: " +
                                    r.replace("\r", " ").replace("\n", " ").trim() + ")"
                        }
                    }
                } catch (e: Exception) {
                    "Erro de comunicação: ${e.message}"
                }
            }
            dialog("Apagar falhas", msg)
        }
    }

    // ------------------------------------------------------------ interface

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun setStatus(s: String) {
        if (::status.isInitialized) status.text = s
    }

    private fun dialog(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun tv(s: String, size: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun button(label: String, color: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 13f
        b.setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(8).toFloat()
        b.background = bg
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildConnect(): LinearLayout {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.setBackgroundColor(Cores.BG)
        v.setPadding(dp(20), dp(10), dp(20), dp(10))

        v.addView(tv("BRINGTECH  FT600", 22f, Cores.ORANGE, true))
        status = tv("", 13f, Cores.GRAY, false)
        v.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))

        scanBtn = button("PROCURAR APARELHOS", Cores.ORANGE) { startScan() }
        val lp = LinearLayout.LayoutParams(MATCH, dp(44))
        lp.topMargin = dp(6)
        lp.bottomMargin = dp(6)
        v.addView(scanBtn, lp)

        val list = ListView(this)
        list.adapter = listAdapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val d = devices.getOrNull(pos)
            if (d != null) connectTo(d)
        }
        v.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return v
    }

    private fun makeTile(key: String, label: String, unit: String): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.setColor(Cores.PANEL)
        bg.cornerRadius = dp(8).toFloat()
        box.background = bg

        val l = tv(label, 10f, Cores.GRAY, false)
        l.gravity = Gravity.CENTER
        box.addView(l)
        val v = tv("--", 24f, Color.WHITE, true)
        tiles[key] = v
        box.addView(v)
        box.addView(tv(unit, 10f, Cores.ORANGE, false))
        return box
    }

    private fun buildDash(): LinearLayout {
        val h = LinearLayout(this)
        h.orientation = LinearLayout.HORIZONTAL
        h.setBackgroundColor(Cores.BG)
        h.setPadding(dp(12), dp(8), dp(12), dp(8))

        gauge = GaugeView(this)
        h.addView(gauge, LinearLayout.LayoutParams(0, MATCH, 1f))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        val rlp = LinearLayout.LayoutParams(0, MATCH, 1f)
        rlp.leftMargin = dp(12)
        h.addView(right, rlp)

        val defs = listOf(
            Triple("speed", "VELOCIDADE", "km/h"),
            Triple("temp", "TEMP. ÁGUA", "°C"),
            Triple("load", "CARGA MOTOR", "%"),
            Triple("tps", "BORBOLETA (TPS)", "%"),
            Triple("iat", "TEMP. AR ADMISSÃO", "°C"),
            Triple("volt", "TENSÃO", "V")
        )
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        right.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        for (r in 0 until 3) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            grid.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
            for (c in 0 until 2) {
                val d = defs[r * 2 + c]
                val tlp = LinearLayout.LayoutParams(0, MATCH, 1f)
                tlp.setMargins(dp(3), dp(3), dp(3), dp(3))
                row.addView(makeTile(d.first, d.second, d.third), tlp)
            }
        }

        milLabel = tv("LUZ DO PAINEL: --", 14f, Cores.GRAY, true)
        milLabel.gravity = Gravity.CENTER
        val mlp = LinearLayout.LayoutParams(MATCH, WRAP)
        mlp.topMargin = dp(4)
        mlp.bottomMargin = dp(4)
        right.addView(milLabel, mlp)

        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        val b1 = LinearLayout.LayoutParams(0, dp(46), 1f)
        b1.rightMargin = dp(4)
        btns.addView(button("LER FALHAS", Cores.PANEL2) { readDtcs() }, b1)
        val b2 = LinearLayout.LayoutParams(0, dp(46), 1.3f)
        b2.rightMargin = dp(4)
        btns.addView(button("APAGAR FALHAS", Cores.RED) { confirmClear() }, b2)
        btns.addView(button("SAIR", Cores.PANEL2) { showConnect() }, LinearLayout.LayoutParams(0, dp(46), 0.7f))
        right.addView(btns, LinearLayout.LayoutParams(MATCH, WRAP))
        return h
    }
}
