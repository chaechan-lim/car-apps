package dev.carapps.fuel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.carapps.probe.core.CrashLog
import dev.carapps.probe.core.padForSystemBars
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Everything that has to happen on the phone, before the car is involved.
 *
 * Three of the probe's four wasted weeks came from things that could only be
 * checked in a moving car: a permission prompt nobody could see, and failures that
 * read as missing data. So this screen front-loads all of it — the key, the
 * permissions, and a real search from the phone's own position — and the car is
 * only needed once those already work.
 */
class FuelPhoneActivity : AppCompatActivity() {

    private val settings by lazy { FuelSettings(this) }
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var keyInput: EditText
    private lateinit var thresholdInput: EditText
    private lateinit var status: TextView
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PAD, PAD, PAD, PAD)
        }

        column.addView(heading("1. 오피넷 API 키"))
        column.addView(note("무료로 발급됩니다. 발급 후 아래에 붙여넣고 저장하세요."))
        column.addView(button("오피넷 키 발급 페이지 열기") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OPINET_KEY_PAGE)))
        })
        keyInput = EditText(this).apply {
            hint = "인증키"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(settings.apiKey)
        }
        column.addView(keyInput)

        column.addView(heading("2. 유종"))
        val products = RadioGroup(this)
        FuelProduct.entries.forEach { product ->
            products.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = product.label
                isChecked = product == settings.product
                setOnCheckedChangeListener { _, checked -> if (checked) settings.product = product }
            })
        }
        column.addView(products)

        column.addView(heading("3. 알림 기준 (주행가능거리, km)"))
        column.addView(note("이보다 적게 남으면 운전 중 한 번 알려줍니다. 앱을 그 주행에서 한 번은 열었어야 합니다."))
        thresholdInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.alertThresholdKm.toString())
        }
        column.addView(thresholdInput)
        column.addView(button("저장") { save() })

        column.addView(heading("4. 권한"))
        column.addView(note(
            "차 화면에서도 요청하지만 그 팝업은 폰에 뜹니다. 운전 중엔 아무도 못 보니, 여기서 미리 허용하세요.",
        ))
        column.addView(button("권한 허용") { requestPermissions() })

        column.addView(heading("5. 차 없이 테스트"))
        column.addView(note("폰의 현재 위치로 실제 검색을 해봅니다. 키와 데이터가 맞는지 여기서 먼저 확인하세요."))
        column.addView(button("지금 위치로 찾기") { testNow() })

        status = mono()
        output = mono()
        column.addView(status)
        column.addView(output)

        val root = ScrollView(this).apply { addView(column) }
        root.padForSystemBars()
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    private fun save() {
        settings.apiKey = keyInput.text.toString()
        thresholdInput.text.toString().toIntOrNull()?.let { settings.alertThresholdKm = it }
        thresholdInput.setText(settings.alertThresholdKm.toString())
        Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
        renderStatus()
    }

    private fun renderStatus() {
        status.text = buildString {
            appendLine()
            appendLine("── 상태 ──")
            appendLine("키        : ${if (settings.apiKey.isBlank()) "없음" else "입력됨"}")
            appendLine("유종      : ${settings.product.label}")
            appendLine("알림 기준 : ${settings.alertThresholdKm}km")
            appendLine("오늘 요청 : ${settings.requestsToday} / ${FuelSettings.DAILY_QUOTA}")
            wantedPermissions().forEach {
                val granted = ContextCompat.checkSelfPermission(this@FuelPhoneActivity, it) ==
                    PackageManager.PERMISSION_GRANTED
                appendLine("${it.substringAfterLast('.').padEnd(20)}: ${if (granted) "granted" else "DENIED"}")
            }
            CrashLog.read(this@FuelPhoneActivity)?.let {
                appendLine()
                appendLine("── 마지막 크래시 ──")
                appendLine(it)
            }
        }
    }

    private fun wantedPermissions() = buildList {
        addAll(CarFeeds.REQUIRED_PERMISSIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Asks for whatever is still missing. A permission refused twice is never
     * prompted for again, and the request then returns denied without showing
     * anything — so that case goes to the app's settings page, where it can still
     * be changed.
     */
    private fun requestPermissions() {
        val missing = wantedPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            Toast.makeText(this, "모두 허용되어 있습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = getSharedPreferences("phone", MODE_PRIVATE)
        val askedBefore = prefs.getBoolean("asked", false)
        if (askedBefore && missing.none { shouldShowRequestPermissionRationale(it) }) {
            Toast.makeText(this, "이전에 거부됨 — 권한 메뉴에서 직접 켜주세요", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
            return
        }
        prefs.edit().putBoolean("asked", true).apply()
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderStatus()
    }

    @SuppressLint("MissingPermission") // checked at the top
    private fun testNow() {
        save()
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            output.text = "\n위치 권한이 없습니다 — \"권한 허용\"을 먼저 누르세요."
            return
        }
        output.text = "\n위치 확인 중…"
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else {
            LocationManager.NETWORK_PROVIDER
        }
        val lastKnown = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        LocationManagerCompat.getCurrentLocation(
            manager,
            provider,
            null as android.os.CancellationSignal?,
            ContextCompat.getMainExecutor(this),
        ) { fix: Location? ->
            val here = fix ?: lastKnown
            if (here == null) {
                output.text = "\n위치를 못 받았습니다. 위치 서비스가 켜져 있는지 확인하세요."
            } else {
                search(here)
            }
        }
    }

    private fun search(here: Location) {
        output.text = "\n%.5f, %.5f 에서 ${settings.product.label} 검색 중…".format(Locale.US, here.latitude, here.longitude)
        val client = settings.client()
        val product = settings.product
        io.execute {
            val result = StationSearch.search(client, here.latitude, here.longitude, product)
            runOnUiThread {
                output.text = describe(result)
                renderStatus()
            }
        }
    }

    private fun describe(result: SearchResult): String = buildString {
        appendLine()
        when (result) {
            is SearchResult.Found -> {
                appendLine("${result.stations.size}곳 (반경 5km, 가까운 순)")
                appendLine()
                result.stations.forEachIndexed { index, nearby ->
                    val s = nearby.station
                    append("${index + 1}. ${s.name}")
                    if (nearby.cheapest && result.stations.size > 1) append("  [최저가]")
                    appendLine()
                    append("   %.1fkm · ".format(Locale.US, nearby.distanceMeters / 1000))
                    if (s.price > 0) append("%,d원 · ".format(Locale.KOREA, s.price))
                    appendLine(s.brand)
                }
            }
            SearchResult.NoneNearby -> appendLine(
                "키는 정상입니다 (강남 기준 확인됨). 반경 5km 안에 ${settings.product.label} 주유소가 없습니다.",
            )
            is SearchResult.Failed -> appendLine("실패: ${result.reason}")
        }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, PAD, 0, 0)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
    }

    private fun mono() = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextIsSelectable(true)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private companion object {
        const val PAD = 40
        const val OPINET_KEY_PAGE = "https://www.opinet.co.kr/user/custapi/custApiInfo.do"
    }
}
