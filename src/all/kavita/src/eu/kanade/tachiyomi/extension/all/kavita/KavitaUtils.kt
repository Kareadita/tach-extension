package eu.kanade.tachiyomi.extension.all.kavita

import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import okhttp3.Headers
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

fun parseDateSafe(date: String?): Long {
    return date?.let {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(it)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    } ?: 0L
}

fun PreferenceScreen.addEditTextPreference(
    title: String,
    default: String,
    summary: String,
    dialogMessage: String? = null,
    inputType: Int? = null,
    validate: ((String) -> Boolean)? = null,
    validationMessage: String? = null,
    key: String = title,
    restartRequired: Boolean = false,
) {
    EditTextPreference(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        this.setDefaultValue(default)
        dialogTitle = title
        this.dialogMessage = dialogMessage

        setOnBindEditTextListener { editText ->
            if (inputType != null) {
                editText.inputType = inputType
            }

            if (validate != null) {
                editText.addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                        override fun afterTextChanged(editable: Editable?) {
                            requireNotNull(editable)

                            val text = editable.toString()

                            val isValid = text.isBlank() || validate(text)

                            editText.error = if (!isValid) validationMessage else null
                            editText.rootView.findViewById<Button>(android.R.id.button1)
                                ?.isEnabled = editText.error == null
                        }
                    },
                )
            }
        }

        setOnPreferenceChangeListener { _, newValue ->
            try {
                val text = newValue as String
                val result = text.isBlank() || validate?.invoke(text) ?: true

                if (restartRequired && result) {
                    Toast.makeText(context, "Restart Mihon to apply new setting.", Toast.LENGTH_LONG).show()
                }

                result
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }.also(::addPreference)
}

/**
 * Parses user-supplied reverse-proxy headers.
 *
 * Expected format: one `Header-Name: value` pair per line.
 * Blank lines and lines starting with `#` are ignored.
 * `Authorization` is reserved for Kavita JWT auth and is skipped.
 */
internal fun parseCustomHttpHeaders(raw: String): Headers {
    val builder = Headers.Builder()
    raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .forEach { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (name.equals("Authorization", ignoreCase = true)) return@forEach
            try {
                builder.removeAll(name)
                builder.add(name, value)
            } catch (_: IllegalArgumentException) {
                // Invalid names/values are rejected in the preference validator.
            }
        }
    return builder.build()
}

internal fun isValidCustomHttpHeaders(raw: String): Boolean {
    raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .forEach { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return false
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (name.isEmpty()) return false
            if (name.equals("Authorization", ignoreCase = true)) return false
            try {
                Headers.Builder().add(name, value)
            } catch (_: IllegalArgumentException) {
                return false
            }
        }
    return true
}
