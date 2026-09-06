package com.example.listentomyguitarlive
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val RECORD_AUDIO_PERMISSION_CODE = 101

    private lateinit var tvDeviceStatus: TextView
    private lateinit var btnToggleAudio: ToggleButton
    private lateinit var btnToggleRecord: ToggleButton
    private lateinit var seekBarBpm: SeekBar
    private lateinit var tvBpmLabel: TextView
    private lateinit var tvRecordStatus: TextView
    private lateinit var btnToggleMetronome: ToggleButton

    private var isRecording = false

    @Volatile
    private var bpm = 120

    @Volatile
    private var isMetronomeOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDeviceStatus = findViewById(R.id.tvDeviceStatus)
        btnToggleAudio = findViewById(R.id.btnToggleAudio)
        btnToggleRecord = findViewById(R.id.btnToggleRecord)
        seekBarBpm = findViewById(R.id.seekBarBpm)
        tvBpmLabel = findViewById(R.id.tvBpmLabel)
        tvRecordStatus = findViewById(R.id.tvRecordStatus)
        btnToggleMetronome = findViewById(R.id.btnToggleMetronome)

        checkPermissions()
        updateAudioDeviceList()

        seekBarBpm.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            //override fun onProgressChanged(seekBar: SeekBar?, progress: Boolean, fromUser: Boolean) {} ai garbage line
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                bpm = progress + 40 // Range: 40 to 200 BPM
                tvBpmLabel.text = "Metronome Tempo (BPM): $bpm"
                AudioService.instance?.setBMP(bpm)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnToggleMetronome.setOnCheckedChangeListener { _, isChecked ->
            isMetronomeOn = isChecked
            AudioService.instance?.setMetronomeBoolean(isMetronomeOn);
        }

        btnToggleAudio.setOnCheckedChangeListener { _, isChecked ->
            val serviceIntent = Intent(this, AudioService::class.java)
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                btnToggleRecord.isEnabled = true

            } else {
                if (isRecording) {
                    btnToggleRecord.isChecked = false
                }
                AudioService.instance?.stopAudioPipeline()
                stopService(serviceIntent)
                btnToggleRecord.isEnabled = false
                btnToggleMetronome.isChecked = false
            }
        }

        btnToggleRecord.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                tvRecordStatus.text = "Recording clean guitar..."
                btnToggleRecord.isEnabled = true
                AudioService.instance?.startRecordingSession()
            } else {
                AudioService.instance?.stopRecordingSession()
                tvRecordStatus.text = "Saved to Music/ListenToMyGuitarLive!"
                btnToggleRecord.isEnabled = false
            }
        }
    }



    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
        }
    }

    private fun updateAudioDeviceList() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val sb = StringBuilder("Detected Audio Inputs:\n")

        var foundUsbOrHeadset = false
        for (device in devices) {
            sb.append("- ${device.productName} (Type: ${device.type})\n")
            if (device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                foundUsbOrHeadset = true
            }
        }

        if (!foundUsbOrHeadset) {
            sb.append("\n⚠️ No USB guitar interface detected. Plug in your USB-C cord and reopen the app.")
        } else {
            sb.append("\n✅ USB audio interface ready!")
        }
        tvDeviceStatus.text = sb.toString()
    }


}