package com.example.listentomyguitarlivemergemode

data class Settings(
    val seekGuitar: Int,
    val seekBacking: Int,
    val switchLowpass: Boolean,
    val seekCutoff: Int,
    val switchLimiter: Boolean,
    val seekThreshold: Int,
    val seekCeiling: Int,
    val seekRatio: Int,
    val checkGuitar: Boolean
)