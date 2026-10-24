package com.mumflorence.app

import android.app.Activity
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/** Helper to build the dark, rounded settings screens. Controls added with a key are saved by save(). */
class Form(val a: Activity, val store: Store) {
    private val pad = Theme.dp(a, 16)
    val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad * 2) }
    private val savers = ArrayList<() -> Unit>()

    init { Theme.styleWindow(a) }

    private fun styledAdapter(options: List<String>) = object : ArrayAdapter<String>(a, android.R.layout.simple_spinner_item, options) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply { setTextColor(Theme.TEXT) }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getDropDownView(position, convertView, parent) as TextView).apply {
                setTextColor(Theme.TEXT); setBackgroundColor(Theme.CARD2); setPadding(pad, pad, pad, pad)
            }
    }

    private fun addCard(v: View) { root.addView(v) }

    fun title(t: String): TextView = TextView(a).apply {
        text = t; textSize = 20f; setTextColor(Theme.ACCENT2); setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, pad, 0, pad / 3); root.addView(this)
    }

    fun text(t: String, size: Float = 15f): TextView = TextView(a).apply {
        text = t; textSize = size; setTextColor(Theme.TEXT); setPadding(0, pad / 4, 0, pad / 4); root.addView(this)
    }

    fun muted(t: String, size: Float = 13f): TextView = text(t, size).apply { setTextColor(Theme.MUTED) }

    fun button(label: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(a).apply {
        text = label; setOnClickListener { onClick() }; Theme.styleButton(this, primary)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = pad / 2; layoutParams = lp
        root.addView(this)
    }

    private fun styledEdit(hint: String, value: String): EditText = EditText(a).apply {
        this.hint = hint; setText(value); setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED)
        background = Theme.card(a, Theme.CARD, 14); setPadding(pad, pad / 2 + 4, pad, pad / 2 + 4)
        root.addView(this)
    }

    /** A text box with no key (you read it yourself). */
    fun plainEdit(label: String, hint: String = "", value: String = ""): EditText {
        muted(label); return styledEdit(hint, value)
    }

    fun plainChoice(label: String, options: List<String>): Spinner {
        muted(label)
        return Spinner(a).apply {
            adapter = styledAdapter(options); background = Theme.card(a, Theme.CARD, 14); root.addView(this)
        }
    }

    fun edit(key: String, label: String, default: String = "", hint: String = "", secret: Boolean = false): EditText {
        muted(label)
        val e = styledEdit(hint, store.get(key, default))
        if (secret) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        savers.add { store.put(key, e.text.toString().trim()) }
        return e
    }

    fun toggle(key: String, label: String, default: Boolean = true) {
        val s = Switch(a).apply {
            text = label; isChecked = store.on(key, default); setTextColor(Theme.TEXT)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2); background = Theme.card(a, Theme.CARD, 14)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = pad / 3; layoutParams = lp
            root.addView(this)
        }
        savers.add { store.put(key, if (s.isChecked) "on" else "off") }
    }

    fun seek(key: String, label: String, min: Int, max: Int, default: Int) {
        val v = (store.get(key, default.toString()).toIntOrNull() ?: default).coerceIn(min, max)
        val tv = text("$label: $v")
        val sb = SeekBar(a).apply {
            this.max = max - min
            progress = v - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { tv.text = "$label: ${p + min}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            root.addView(this)
        }
        savers.add { store.put(key, (sb.progress + min).toString()) }
    }

    fun choice(key: String, label: String, options: List<String>, default: String) {
        muted(label)
        val sp = Spinner(a).apply {
            adapter = styledAdapter(options); background = Theme.card(a, Theme.CARD, 14)
            setSelection(options.indexOf(store.get(key, default)).coerceAtLeast(0))
            root.addView(this)
        }
        savers.add { store.put(key, sp.selectedItem.toString()) }
    }

    fun onSave(f: () -> Unit) { savers.add(f) }
    fun save() { savers.forEach { it() } }
    fun scroll(): View = ScrollView(a).apply { setBackgroundColor(Color.TRANSPARENT); addView(root) }
}
