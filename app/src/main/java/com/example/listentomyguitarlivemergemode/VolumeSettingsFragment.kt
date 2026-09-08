package com.example.listentomyguitarlivemergemode

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.Switch
import kotlin.math.roundToInt


class VolumeSettingsFragment : Fragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    }

    //override fun onDestroyView() {
    //    super.onDestroyView()

        //clear any fragment view reference
    //}

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_volume_settings, container, false)
    }


}