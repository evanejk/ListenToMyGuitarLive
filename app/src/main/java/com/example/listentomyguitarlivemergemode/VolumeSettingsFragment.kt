package com.example.listentomyguitarlivemergemode

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import kotlinx.coroutines.selects.select

class VolumeSettingsFragment : Fragment() {
    var spinner: Spinner ? = null
    // Save current effect values to a specific preset slot
    fun savePreset(context: Context,
                   presetIndex: Int,
                   seekGuitar: Int,
                   seekBacking: Int,
                   switchLowpass: Boolean,
                   seekCutoff: Int,
                   switchLimiter: Boolean,
                   seekThreshold: Int,
                   seekCeiling: Int,
                   seekRatio: Int,
                   checkGuitar: Boolean) {
        val prefs = context.getSharedPreferences("GuitarPresets", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("preset_${presetIndex}_seekGuitar", seekGuitar)
            putInt("preset_${presetIndex}_seekBacking", seekBacking)
            putBoolean("preset_${presetIndex}_switchLowpass", switchLowpass)
            putInt("preset_${presetIndex}_seekCutoff", seekCutoff)
            putBoolean("preset_${presetIndex}_switchLimiter", switchLimiter)
            putInt("preset_${seekThreshold}_seekThreshold", seekThreshold)
            putInt("preset_${presetIndex}_seekCeiling", seekCeiling)
            putInt("preset_${presetIndex}_seekRatio", seekRatio)
            putBoolean("preset_${presetIndex}_checkGuitar", checkGuitar)
            apply()
        }
    }

    // Load effect values for a specific preset slot
    fun loadPreset(context: Context, presetIndex: Int): Settings {
        val prefs = context.getSharedPreferences("GuitarPresets", Context.MODE_PRIVATE)
        // Provide sensible default fallback values if nothing is saved yet
        val seekGuitar = prefs.getInt("preset_${presetIndex}_seekGuitar", 100)
        val seekBacking = prefs.getInt("preset_${presetIndex}_seekBacking", 100)
        val switchLowpass = prefs.getBoolean("preset_${presetIndex}_switchLowpass", true)
        val seekCutoff = prefs.getInt("preset_${presetIndex}_seekCutoff", 30)
        val switchLimiter = prefs.getBoolean("preset_${presetIndex}_switchLimiter", true)
        val seekThreshold = prefs.getInt("preset_${presetIndex}_seekThreshold", 22000)
        val seekCeiling = prefs.getInt("preset_${presetIndex}_seekCeiling", 32000)
        val seekRatio = prefs.getInt("preset_${presetIndex}_seekRatio", 20)
        val checkGuitar = prefs.getBoolean("preset_${presetIndex}_checkGuitar", false)
        return Settings(
            seekGuitar,
            seekBacking,
            switchLowpass,
            seekCutoff,
            switchLimiter,
            seekThreshold,
            seekCeiling,
            seekRatio,
            checkGuitar
        );
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // This line is what actually draws your XML layout onto the screen
        return inflater.inflate(R.layout.fragment_volume_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        super.onViewCreated(view, savedInstanceState)

        // Call findViewById on the 'view' parameter
        val seekBarGuitar = view.findViewById<SeekBar>(R.id.seekBarGuitar)
        val seekBarBacking = view.findViewById<SeekBar>(R.id.seekBarBacking)
        val seekBarThreshold = view.findViewById<SeekBar>(R.id.seekBarThreshold)
        val seekBarCeiling = view.findViewById<SeekBar>(R.id.seekBarCeiling)
        val seekBarRatio = view.findViewById<SeekBar>(R.id.seekBarRatio)

        seekBarGuitar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                AudioService.guitarVolume = progress / 100.0f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBarBacking.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                AudioService.backingVolume = progress / 100.0f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                AudioService.threshold = progress
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBarCeiling.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                AudioService.ceiling = progress
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBarRatio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                AudioService.ratio = progress / 100.0F
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val switchLimiter = view.findViewById<Switch>(R.id.switchLimiter)
        switchLimiter.isChecked = AudioService.useLimiter == true
        switchLimiter.setOnCheckedChangeListener { _, isChecked ->
            AudioService.useLimiter = isChecked

        }

        val switchLowpass = view.findViewById<Switch>(R.id.switchLowpass)
        switchLowpass.isChecked = AudioService.useLowpass == true
        switchLimiter.setOnCheckedChangeListener { _, isChecked ->
            AudioService.useLowpass = isChecked

        }

        val seekBarCutoff = view.findViewById<SeekBar>(R.id.seekBarCutoff)
        seekBarCutoff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                AudioService.seekBarCutoff = progress
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val checkBoxRecordJustGuitar = view.findViewById<CheckBox>(R.id.checkboxRecordJustGuitar)
        checkBoxRecordJustGuitar.setOnCheckedChangeListener{ _, isChecked ->
            AudioService.recordJustGuitar = isChecked
        }
        spinner = view.findViewById<Spinner>(R.id.spinnerPresets)
        var adapter = ArrayAdapter.createFromResource(
            requireView().context,
            R.array.guitar_presets,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner?.adapter = adapter
        }
        var lastSelectedPresetToSave: Int = -1
        spinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selected = position
                if(lastSelectedPresetToSave != -1 && view != null){
                    savePreset(view.context,
                        lastSelectedPresetToSave,
                        seekBarGuitar.progress,
                        seekBarBacking.progress,
                        switchLowpass.isChecked,
                        seekBarCutoff.progress,
                        switchLimiter.isChecked,
                        seekBarThreshold.progress,
                        seekBarCeiling.progress,
                        seekBarRatio.progress,
                        checkBoxRecordJustGuitar.isChecked)

                }
                lastSelectedPresetToSave = selected
                if(view != null) {
                    var settings: Settings = loadPreset(view.context, selected)
                    seekBarGuitar.progress = settings.seekGuitar
                    seekBarBacking.progress = settings.seekBacking
                    switchLowpass.isChecked = settings.switchLowpass
                    seekBarCutoff.progress = settings.seekCutoff
                    switchLimiter.isChecked = settings.switchLimiter
                    seekBarThreshold.progress = settings.seekThreshold
                    seekBarCeiling.progress = settings.seekCeiling
                    seekBarRatio.progress = settings.seekRatio
                    checkBoxRecordJustGuitar.isChecked = settings.checkGuitar
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {

            }
        }

    }
}


