package com.aelant.freqshift

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory

/**
 * App-level [Application] that customises Coil so [Track] instances passed
 * as the `model` of an `AsyncImage` resolve through [TrackArtFetcher] —
 * MediaStore thumbnail first, embedded APIC frame second.
 */
class FreqShiftApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(TrackArtFetcher.Factory(this@FreqShiftApp)) }
            // Keep Coil's defaults for cache sizes — they're sane for our
            // small library sizes (typically <2k tracks per device) and
            // tuned to OS memory pressure.
            .build()
}
