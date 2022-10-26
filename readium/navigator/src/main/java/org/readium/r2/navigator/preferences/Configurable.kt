/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.preferences

import kotlinx.coroutines.flow.StateFlow
import org.readium.r2.navigator.preferences.Configurable.Settings
import org.readium.r2.navigator.preferences.Configurable.Preferences
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * A [Configurable] is a component with a set of configurable [Settings].
 */
@ExperimentalReadiumApi
interface Configurable<T : Settings, P: Preferences> {

    /**
     * Marker interface for the [Setting] properties holder.
     */
    interface Settings

    interface Preferences

    /**
     * Current [Settings] values.
     *
     * Implementers: Override to set the actual [Settings] sub-type.
     */
    val settings: StateFlow<T>

    /**
     * Submits a new set of [Preferences] to update the current [Settings].
     *
     * Note that the [Configurable] might not update its [settings] right away, or might even ignore
     * some of the provided preferences. They are only used as hints to compute the new settings.
     */

    fun submitPreferences(preferences: P)
}

@ExperimentalReadiumApi
interface PreferencesSerializer<P: Preferences> {

    fun serialize(preferences: P): String

    fun deserialize(preferences: String): P
}

@ExperimentalReadiumApi
interface PreferencesEditor<P: Preferences> {

    val preferences: P

    fun clear()
}

@ExperimentalReadiumApi
fun interface PreferencesFilter<T: Preferences> {

    fun filter(preferences: T): T
}

