package com.dev.googlesheetwarehouse

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.util.Log
import android.view.*
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dev.googlesheetwarehouse.api.SheetsAPIDataSource
import com.dev.googlesheetwarehouse.api.SheetsRepository
import com.dev.googlesheetwarehouse.auth.AuthenticationManager
import com.dev.googlesheetwarehouse.model.PhoneStockInfo
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.drive.Drive
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest
import com.google.api.services.sheets.v4.model.ValueRange
import com.google.zxing.Result
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.functions.Consumer
import io.reactivex.schedulers.Schedulers
import me.dm7.barcodescanner.zxing.ZXingScannerView
import me.dm7.barcodescanner.zxing.ZXingScannerView.ResultHandler
import java.util.*


class ScannerActivitySingle : ScannerActivityBase(), ResultHandler {
    // Scanner
    private var mScannerView: ZXingScannerView? = null
    private var mFlash = false

    // Views
    private lateinit var rlPleaseScanItems: RelativeLayout
    private lateinit var rlScannedItems: RelativeLayout
    private lateinit var tvScannedItemName: TextView
    private lateinit var tvPleaseScanItems: TextView
    private lateinit var btnMinus: Button
    private lateinit var btnPlus: Button
    private lateinit var etCount: EditText

    // Authentication and Sheets API
    private lateinit var signInOptions: GoogleSignInOptions
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var googleAccountCredential: GoogleAccountCredential

    private lateinit var authenticationManager: AuthenticationManager
    private lateinit var sheetsRepository: SheetsRepository
    private lateinit var sheetsApiDataSource: SheetsAPIDataSource

    private lateinit var readSpreadsheetDisposable: Disposable
    private lateinit var updateSpreadsheetDisposable: Disposable
    private val phones: MutableList<PhoneStockInfo> = mutableListOf()

    private lateinit var scannedItemsList: ArrayList<String>

    private lateinit var batchUpdateRequest: BatchUpdateValuesRequest

    private var dataLoaded = false

    public override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_scanner_single)
        setupToolbar()
        initViews()

        initDependencies()

        if (!isOnline(this)) {
            Toast.makeText(
                this,
                "NO INTERNET!!\nOpen app after connecting to Internet.",
                Toast.LENGTH_LONG
            ).show()
        }

        if (!hasCameraPermission())
            ActivityCompat.requestPermissions(
                this@ScannerActivitySingle,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CODE_CAMERA
            )

        val contentFrame = findViewById<View>(R.id.content_frame) as ViewGroup
        mScannerView = ZXingScannerView(this)
        contentFrame.addView(mScannerView)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val actionBar = supportActionBar
        actionBar?.setTitle(R.string.scan_items_single)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.single).isVisible = false
        menu.findItem(R.id.multiple).isVisible = true
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            (R.id.flash) -> {
                toggleFlash()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    public override fun onResume() {
        super.onResume()
        mScannerView!!.setResultHandler(this)
        // You can optionally set aspect ratio tolerance level
        // that is used in calculating the optimal Camera preview size
        mScannerView!!.setAspectTolerance(0.2f)
        if (dataLoaded) {
            mScannerView!!.startCamera()
        }
        mScannerView!!.flash = mFlash
    }

    public override fun onPause() {
        super.onPause()
        mScannerView!!.stopCamera()
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(FLASH_STATE, mFlash)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_CAMERA) {
            when {
                grantResults.isEmpty() -> // If user interaction was interrupted, the permission request is cancelled and you
                    // receive empty arrays.
                    Log.i(TAG, "User interaction was cancelled.")
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED -> // Permission granted.
                {
                    // cameraSource.start(svScanner.holder)
                    Log.i(TAG, "Permission granted.")
                }
                else -> // Permission denied.
                    Toast.makeText(
                        this,
                        "Scanner will not work without permissions granted.",
                        Toast.LENGTH_SHORT
                    ).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_GOOGLE_SIGN_IN) {
            if (resultCode == Activity.RESULT_OK) {
                loginSuccessful()
            } else {
                loginFailed()
            }
        }
    }

    override fun handleResult(rawResult: Result) {
        if (rawResult.text.length > 5) {
            rlPleaseScanItems.visibility = GONE
            tvScannedItemName.text = rawResult.text
            rlScannedItems.visibility = VISIBLE

            // Display soft keyboard
            Handler(Looper.getMainLooper()).postDelayed({
                etCount.dispatchTouchEvent(
                    MotionEvent.obtain(
                        SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_DOWN,
                        0f,
                        0f,
                        0
                    )
                )
                etCount.dispatchTouchEvent(
                    MotionEvent.obtain(
                        SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP,
                        0f,
                        0f,
                        0
                    )
                )
            }, 200)

            scannedItem = rawResult.text

            playBeepTone()
        } else {
            Toast.makeText(this, "Scanned item QR code too short!", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                mScannerView!!.resumeCameraPreview(this)
            }, 1000)
        }
    }

    private fun setRangeAndValue(operator: String) {
        // Get range and value range for update
        var count = etCount.text.toString().toIntOrNull()
        if (count == null) count = 0
        var i = 1
        phones.forEach { t ->
            // Log.d(TAG, t.phoneModel)
            i++
            when (scannedItem) {
                (t.idClear) -> {
                    // Update list item first
                    var clear = t.countClear.toIntOrNull()
                    if (clear == null) clear = 0
                    t.countClear =
                        if (operator == "+") (clear + count).toString() else (clear - count).toString()
                    rangeUpdate = "'${sheetName}'!B$i"
                    valueRangeUpdate = ValueRange().setValues(
                        listOf(
                            listOf<Any>(t.countClear)
                        )
                    )
                }
                t.idMatt -> {
                    var matt = t.countMatt.toIntOrNull()
                    if (matt == null) matt = 0
                    t.countMatt =
                        if (operator == "+") (matt + count).toString() else (matt - count).toString()
                    rangeUpdate = "'${sheetName}'!E$i"
                    valueRangeUpdate = ValueRange().setValues(
                        listOf(
                            listOf<Any>(t.countMatt)
                        )
                    )
                }
                t.idTough -> {
                    var tough = t.countTough.toIntOrNull()
                    if (tough == null) tough = 0
                    t.countTough =
                        if (operator == "+") (tough + count).toString() else (tough - count).toString()
                    rangeUpdate = "'${sheetName}'!H$i"
                    valueRangeUpdate = ValueRange().setValues(
                        listOf(
                            listOf<Any>(t.countTough)
                        )
                    )
                }
            }
        }
        startUpdatingSpreadsheet(spreadsheetId, rangeUpdate, valueRangeUpdate)
    }

    private fun initViews() {
        rlPleaseScanItems = findViewById(R.id.rl_please_scan_items)
        rlScannedItems = findViewById(R.id.rl_buttons)
        tvScannedItemName = findViewById(R.id.tv_scanned_item_name)
        tvPleaseScanItems = findViewById(R.id.tv_please_scan_items)
        btnMinus = findViewById(R.id.btn_minus)
        btnPlus = findViewById(R.id.btn_plus)
        etCount = findViewById(R.id.et_count)

        btnMinus.setOnClickListener { setRangeAndValue("-") }
        btnPlus.setOnClickListener { setRangeAndValue("+") }
    }

    private fun initDependencies() {
        signInOptions =
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestScopes(Scope(SheetsScopes.SPREADSHEETS_READONLY))
                .requestScopes(Scope(SheetsScopes.SPREADSHEETS))
                .requestScopes(Drive.SCOPE_FILE)
                .requestEmail()
                .build()
        googleSignInClient = GoogleSignIn.getClient(this, signInOptions)
        googleAccountCredential = GoogleAccountCredential
            .usingOAuth2(this, Arrays.asList(*AuthenticationManager.SCOPES))
            .setBackOff(ExponentialBackOff())
        authenticationManager =
            AuthenticationManager(
                lazyOf(this),
                googleSignInClient,
                googleAccountCredential
            )
        sheetsApiDataSource =
            SheetsAPIDataSource(
                authenticationManager,
                AndroidHttp.newCompatibleTransport(),
                JacksonFactory.getDefaultInstance()
            )
        sheetsRepository = SheetsRepository(sheetsApiDataSource)
        launchAuthentication(googleSignInClient)
    }

    private fun launchAuthentication(client: GoogleSignInClient) {
        Log.d(TAG, "LOGIN PROMPT");
        startActivityForResult(client.signInIntent, REQUEST_CODE_GOOGLE_SIGN_IN)
    }

    private fun loginSuccessful() {
        Log.d(TAG, "LOGIN SUCCESSFUL");
        // view.showName(authenticationManager.getLastSignedAccount()?.displayName!!)
        authenticationManager.setUpGoogleAccountCredential()
        startReadingSpreadsheet(spreadsheetId, rangeData)
    }

    private fun loginFailed() {
        Log.d(TAG, "LOGIN FAILED");
    }

    private fun startReadingSpreadsheet(spreadsheetId: String, range: String) {
        phones.clear()
        readSpreadsheetDisposable =
            sheetsRepository.readSpreadSheet(spreadsheetId, range)
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError {
                    Toast.makeText(this, it.message!!, Toast.LENGTH_SHORT).show()
                }
                .subscribe(Consumer {
                    phones.addAll(it)
                    dataLoaded = true
                    mScannerView!!.startCamera()
                    tvPleaseScanItems.text = "Please scan an item ..."
                })
    }

    private fun startUpdatingSpreadsheet(
        spreadSheetIdUpdate: String,
        rangeUpdate: String,
        valueRangeUpdate: ValueRange
    ) {
        updateSpreadsheetDisposable = sheetsRepository.updateSpreadsheet(
            spreadSheetIdUpdate,
            rangeUpdate,
            valueRangeUpdate
        )
            .subscribeOn(Schedulers.computation())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnError {
                Log.d(TAG, "ERROR: " + it.message!!)
            }
            .subscribe(Consumer {
                etCount.setText("1")
                rlScannedItems.visibility = GONE
                rlPleaseScanItems.visibility = VISIBLE
                mScannerView!!.resumeCameraPreview(this)

                Log.d(TAG, "UPDATED CELLS: " + it.updatedCells.toString())
                Toast.makeText(this, "Updated", Toast.LENGTH_SHORT).show()
            })
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this@ScannerActivitySingle,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    }

    private fun toggleFlash() {
        mFlash = !mFlash
        mScannerView!!.flash = mFlash
    }

    private fun playBeepTone() {

        // Play beep tone
        val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 300)
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_PIP, 300)

        // Vibrate for 500 milliseconds
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(
                VibrationEffect.createOneShot(
                    500,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            //deprecated in API 26
            v.vibrate(500)
        }
    }

    private fun isOnline(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities =
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        if (capabilities != null) {
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    Log.i("Internet", "NetworkCapabilities.TRANSPORT_CELLULAR")
                    return true
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    Log.i("Internet", "NetworkCapabilities.TRANSPORT_WIFI")
                    return true
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    Log.i("Internet", "NetworkCapabilities.TRANSPORT_ETHERNET")
                    return true
                }
            }
        }
        return false
    }

    companion object {
        private const val TAG = "SCANNER_ACTIVITY_SINGLE"
        private const val FLASH_STATE = "FLASH_STATE"
        private const val REQUEST_CODE_CAMERA = 1001
        const val REQUEST_CODE_GOOGLE_SIGN_IN = 999

        // Test Sheet
        // private const val spreadsheetId = "1QpABUgh5Qydc3TL5uGrh9Plueen7p4RdIx51X1A29wE"

        // Main Sheet
        private const val spreadsheetId = "1NvMlBT2f_lnnHNffBsLqYmhaDJJWbvmDR2LLw0OFOUU"
        private const val sheetName = "STOCK"
        private const val rangeData = "STOCK!A2:J"

        private lateinit var scannedItem: String
        private lateinit var rangeUpdate: String
        private lateinit var valueRangeUpdate: ValueRange
    }
}