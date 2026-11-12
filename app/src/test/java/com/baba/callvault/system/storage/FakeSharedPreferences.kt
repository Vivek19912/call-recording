/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.SharedPreferences

/**
 * HashMap-backed [SharedPreferences] for JVM unit tests: lets tests drive
 * the real [com.baba.callvault.data.AppPreferences] (real key names,
 * defaults and read-back semantics) with no Robolectric.
 */
class FakeSharedPreferences : SharedPreferences {
    private val map = HashMap<String, Any?>()

    override fun getAll(): Map<String, *> = map.toMap()

    override fun getString(key: String?, defValue: String?): String? =
        if (key != null && map.containsKey(key)) map[key] as? String else defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        if (key != null && map.containsKey(key)) (map[key] as? Set<String>)?.toSet() else defValues

    override fun getInt(key: String?, defValue: Int): Int =
        if (key != null && map.containsKey(key)) (map[key] as? Int) ?: defValue else defValue

    override fun getLong(key: String?, defValue: Long): Long =
        if (key != null && map.containsKey(key)) (map[key] as? Long) ?: defValue else defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        if (key != null && map.containsKey(key)) (map[key] as? Float) ?: defValue else defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        if (key != null && map.containsKey(key)) (map[key] as? Boolean) ?: defValue else defValue

    override fun contains(key: String?): Boolean = key != null && map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            stage(key, value)

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor =
            stage(key, values?.toSet())

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            stage(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            stage(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            stage(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            stage(key, value)

        private fun stage(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) {
                removals.remove(key)
                pending[key] = value
            }
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) {
                removals.add(key)
                pending.remove(key)
            }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            pending.clear()
            removals.clear()
            return this
        }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun applyChanges() {
            if (clearAll) {
                map.clear()
                clearAll = false
            }
            for (key in removals) map.remove(key)
            removals.clear()
            map.putAll(pending)
            pending.clear()
        }
    }
}
