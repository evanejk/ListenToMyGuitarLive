package com.example.listentomyguitarlivemergemode

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.Manifest
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.content.Context
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.AudioDeviceInfo
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {
    private val RECORD_AUDIO_PERMISSION_CODE = 101

    private var isRecording = false

    @Volatile
    private var bpm = 120

    @Volatile
    private var isMetronomeOn = false

    var tvDeviceStatus: TextView ? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Call findViewById on the 'view' parameter
        tvDeviceStatus = view.findViewById<TextView>(R.id.tvDeviceStatus)
        val btnToggleAudio: ToggleButton = view.findViewById<ToggleButton>(R.id.btnToggleAudio)
        val btnToggleRecord: ToggleButton = view.findViewById<ToggleButton>(R.id.btnToggleRecord)
        val seekBarBpm: SeekBar = view.findViewById<SeekBar>(R.id.seekBarBpm)
        val tvBpmLabel: TextView = view.findViewById<TextView>(R.id.tvBpmLabel)
        val tvRecordStatus: TextView = view.findViewById<TextView>(R.id.tvRecordStatus)
        val btnToggleMetronome: ToggleButton = view.findViewById<ToggleButton>(R.id.btnToggleMetronome)
        val btnToggleBackground: ToggleButton = view.findViewById<ToggleButton>(R.id.btnToggleBackground)


        checkPermissions()
        updateAudioDeviceList()

        btnToggleBackground.isEnabled = false

        seekBarBpm.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            //override fun onProgressChanged(seekBar: SeekBar?, progress: Boolean, fromUser: Boolean) {} ai garbage line
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                bpm = progress + 40 // Range: 40 to 200 BPM
                tvBpmLabel.text = "Metronome Tempo (BPM): $bpm"
                AudioService.setBMP(bpm)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnToggleMetronome.setOnCheckedChangeListener { _, isChecked ->
            isMetronomeOn = isChecked
            AudioService.setMetronomeBoolean(isMetronomeOn);
        }
        btnToggleBackground.setOnCheckedChangeListener { _, isChecked ->
            if (!btnToggleBackground.isPressed) return@setOnCheckedChangeListener
            if(isChecked){
                pickAudioFile.launch("audio/*")
            }else{
                AudioService.unloadBackingTrackIntoMemory()

            }
        }
        btnToggleAudio.setOnCheckedChangeListener { _, isChecked ->
            val serviceIntent = Intent(requireContext(), AudioService::class.java)
            if (isChecked) {
                ContextCompat.startForegroundService(requireContext(),serviceIntent)
                btnToggleRecord.isEnabled = true
                btnToggleBackground.isEnabled = true

            } else {
                if (isRecording) {
                    btnToggleRecord.isChecked = false
                }
                AudioService.instance?.stopAudioPipeline()
                requireContext().stopService(serviceIntent)
                btnToggleRecord.isEnabled = false
                btnToggleMetronome.isChecked = false
                btnToggleBackground.isEnabled = false
            }
        }

        btnToggleRecord.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                tvRecordStatus.text = "Recording track..."
                btnToggleRecord.isEnabled = true
                AudioService.instance?.startRecordingSession()
            } else {
                AudioService.instance?.stopRecordingSession()
                tvRecordStatus.text = "Saved to Music/ListenToMyGuitarLive!"
                btnToggleRecord.isEnabled = true
            }
        }
    }

    private val pickAudioFile: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // Use requireContext() instead of 'this' inside a Fragment


            // Inside your Activity or Fragment
            lifecycleScope.launch(Dispatchers.IO) {
                // Heavy file reading and decoding happens here safely in the background
                AudioService.loadBackingTrackIntoMemory(requireContext(), uri)

                withContext(Dispatchers.Main) {
                    // Switch back to the main thread only if you need to update UI elements (like a "Ready" label)

                }
            }




        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
        }
    }

    private fun updateAudioDeviceList() {
        val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        //val sb = StringBuilder("Detected Audio Inputs:\n")
        val sb = StringBuilder()
        var foundUsbOrHeadset = false
        for (device in devices) {
            //sb.append("- ${device.productName} (Type: ${device.type})\n")
            if (device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                foundUsbOrHeadset = true
            }
        }

        if (!foundUsbOrHeadset) {
            sb.append("\n⚠️ No USB guitar interface detected. Plug in your USB-C cord.")
        } else {
            sb.append("✅ USB audio interface ready!")
        }

        tvDeviceStatus?.post {
            tvDeviceStatus?.text = sb.toString()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                   // val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        updateAudioDeviceList()
                    }, 1000)//sleep half a second to let the usb load before checking what was connected
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                  //  val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    updateAudioDeviceList();
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        requireActivity().registerReceiver(usbReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        requireActivity().unregisterReceiver(usbReceiver)
    }
}