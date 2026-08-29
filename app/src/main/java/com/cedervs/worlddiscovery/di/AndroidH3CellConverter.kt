package com.cedervs.worlddiscovery.di

import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.DiscoveryEngineVersion
import com.cedervs.worlddiscovery.core.discovery.H3CellConverter
import com.uber.h3core.H3Core

/**
 * [H3CellConverter] backed by `com.uber:h3-android` — the AAR variant of Uber's official H3
 * bindings, packaged with the Android-native libraries (`jni/arm64-v8a`, `jni/armeabi-v7a`)
 * instead of desktop natives.
 *
 * This exists only because AAR dependencies can be consumed exclusively from an Android module:
 * `:core-discovery-engine` is deliberately plain Kotlin/JVM (docs/architecture.md §4) and keeps
 * using the generic `com.uber:h3` artifact for its own JVM unit tests — see
 * [com.cedervs.worlddiscovery.core.discovery.H3JavaCellConverter]. Same H3 API, same canonical
 * resolution (§8 of discovery-engine.md), same behavior — only the packaged native library
 * differs. `app/build.gradle.kts` excludes the generic artifact from the packaged app so the two
 * never collide on the same classpath.
 *
 * Uses [H3Core.newSystemInstance] rather than [H3Core.newInstance]: the latter unpacks and loads
 * the bundled native library manually (the path that produced `UnsatisfiedLinkError: No native
 * resource found at /android-arm64/libh3-java.so` on a physical device), while
 * `newSystemInstance()` relies on the native library the Android package manager already
 * extracted from the AAR's `jni/<abi>/` layout via `System.loadLibrary`. H3 4.5.0 (up from 4.4.0)
 * additionally fixes a missing `libm` link in the Android native library that broke
 * `newSystemInstance()` on 4.4.0.
 */
class AndroidH3CellConverter(
    private val resolution: Int = DiscoveryEngineVersion.CANONICAL_H3_RESOLUTION,
    private val h3Core: H3Core = H3Core.newSystemInstance(),
) : H3CellConverter {

    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell {
        val h3Index = h3Core.latLngToCellAddress(coordinate.latitude, coordinate.longitude, resolution)
        return CanonicalCell(h3Index = h3Index, resolution = resolution)
    }
}
