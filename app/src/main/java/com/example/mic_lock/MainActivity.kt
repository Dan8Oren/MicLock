package com.example.mic_lock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var micSpinner: Spinner

    private val audioPerms = arrayOf(Manifest.permission.RECORD_AUDIO)
    private val notifPerms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    @RequiresApi(Build.VERSION_CODES.P)
    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasAllPerms()) {
            populateMics()
        }
        updateUi()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        micSpinner = findViewById(R.id.micSpinner)

        startBtn.setOnClickListener {
            if (!hasAllPerms()) {
                reqPerms.launch(audioPerms + notifPerms)
            } else {
                startMicLock()
            }
        }
        stopBtn.setOnClickListener { stopMicLock() }

        micSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val item = parent?.adapter?.getItem(pos) as MicItem
                if (Prefs.getSelectedAddress(this@MainActivity) != item.address) {
                    Prefs.setSelectedAddress(this@MainActivity, item.address)
                    // Ask service to reconfigure if running
                    if (MicLockService.isRunning) {
                        val intent = Intent(this@MainActivity, MicLockService::class.java)
                        intent.action = MicLockService.ACTION_RECONFIGURE
                        ContextCompat.startForegroundService(this@MainActivity, intent)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (hasAllPerms()) populateMics()
        updateUi()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onResume() {
        super.onResume()
        if (hasAllPerms()) populateMics()
        updateUi()
    }

    private fun hasAllPerms(): Boolean {
        val all = audioPerms + notifPerms
        return all.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun startMicLock() {
        val intent = Intent(this, MicLockService::class.java)
        ContextCompat.startForegroundService(this, intent)
        updateUi()
    }

    private fun stopMicLock() {
        stopService(Intent(this, MicLockService::class.java))
        updateUi()
    }

    private fun updateUi() {
        val running = MicLockService.isRunning
        statusText.text = if (running) "Mic-lock is ON" else "Mic-lock is OFF"
        startBtn.isEnabled = !running
        stopBtn.isEnabled = running
    }

    // ----- mic list UI -----

    data class MicItem(val label: String, val address: String) {
        override fun toString(): String = label
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun populateMics() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val choices = AudioSelector.listBuiltinMicChoices(am)

        val items = mutableListOf<MicItem>()
        items += MicItem("Auto (bottom mic)", Prefs.VALUE_AUTO)
        choices.forEach { ch ->
            val addr = ch.device.address ?: ch.micInfo?.address ?: "(unknown)"
            val y = ch.micInfo?.position?.y
            val yTxt = y?.let { " y=%.3f".format(it) } ?: ""
            val label = "addr=$addr$yTxt"
            items += MicItem(label, ch.device.address ?: addr)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        micSpinner.adapter = adapter

        // Select current pref
        val selAddr = Prefs.getSelectedAddress(this)
        val idx = items.indexOfFirst { it.address == selAddr }
            .takeIf { it >= 0 } ?: 0
        micSpinner.setSelection(idx)
    }
}
