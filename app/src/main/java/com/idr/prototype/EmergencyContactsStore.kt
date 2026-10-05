package com.idr.prototype

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stores only contacts entered in this app; it never reads the address book. */
class EmergencyContactsStore(context: Context) {
    private val preferences = context.getSharedPreferences("idr_emergency_contacts", Context.MODE_PRIVATE)

    fun load(): List<EmergencyContact> {
        val array = try { JSONArray(preferences.getString(KEY_CONTACTS, "[]")) } catch (_: Exception) { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val phone = item.optString("phone").trim()
                if (name.isNotEmpty() && phone.isNotEmpty()) {
                    add(EmergencyContact(item.optString("id", UUID.randomUUID().toString()), name, phone))
                }
            }
        }
    }

    fun save(contacts: List<EmergencyContact>) {
        val array = JSONArray()
        contacts.forEach { contact ->
            array.put(JSONObject().put("id", contact.id).put("name", contact.name).put("phone", contact.phoneNumber))
        }
        preferences.edit().putString(KEY_CONTACTS, array.toString()).apply()
    }

    companion object { private const val KEY_CONTACTS = "contacts" }
}
