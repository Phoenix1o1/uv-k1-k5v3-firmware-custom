package io.github.phoenix1o1.uvbridge.chirp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.phoenix1o1.uvbridge.FileSaver
import io.github.phoenix1o1.uvbridge.R

/**
 * Shows and edits the channels and settings of a CHIRP image, through
 * [Channels] and [Settings]. Edits change the image in memory; "Save" writes
 * the file, "Upload" hands the edited image back to [ChirpActivity].
 */
class ImageEditorActivity : Activity() {

    private lateinit var image: ChirpImage
    private lateinit var fileName: String
    private var fileUri: Uri? = null
    private var modified = false

    private lateinit var title: TextView
    private lateinit var list: ListView
    private lateinit var filterRow: LinearLayout
    private lateinit var filter: Spinner
    private var showSettings = false

    /** Rows of the channel list, after the scan-list filter. */
    private var channels = listOf<Channel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bytes = (savedInstanceState?.getByteArray(EXTRA_IMAGE) ?: intent.getByteArrayExtra(EXTRA_IMAGE))
        image = try { ChirpImage.parse(bytes!!) } catch (e: Exception) { finish(); return }
        fileName = intent.getStringExtra(EXTRA_NAME) ?: "image.img"
        fileUri = intent.data
        modified = savedInstanceState?.getBoolean(STATE_MODIFIED) ?: false
        buildLayout()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putByteArray(EXTRA_IMAGE, image.toBytes())
        outState.putBoolean(STATE_MODIFIED, modified)
    }

    // ---- layout ----

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildLayout() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }
        root.addView(title)

        val tabs = LinearLayout(this)
        fun tab(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; isAllCaps = false; setOnClickListener { onClick() }
            tabs.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        tab("Channels") { showSettings = false; refresh() }
        tab("Settings") { showSettings = true; refresh() }
        tab("Save") { save() }
        tab("Upload") { uploadEdited() }
        root.addView(tabs)

        filterRow = LinearLayout(this)
        filter = Spinner(this)
        filterRow.addView(filter, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        filterRow.addView(Button(this).apply { text = "Add"; setOnClickListener { addChannel() } })
        root.addView(filterRow)
        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        list.setOnItemClickListener { _, _, pos, _ -> if (showSettings) editSetting(pos) else editChannel(channels[pos]) }

        val side = dp(12)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(bars.left + side, bars.top + side, bars.right + side, bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
        updateFilterChoices(0)
    }

    /** Filter entries: all, then each scan list (named when it has a name), then "no list". */
    private fun filterLabels(): List<String> {
        val names = image.listNames(withEmpty = true)
        return listOf("All channels") + (1..24).map { i -> names[i - 1].ifEmpty { "List $i" }.let { "$it [$i]" } } +
            listOf("All lists [25]", "In no list")
    }

    private fun updateFilterChoices(selected: Int) {
        filter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, filterLabels())
        filter.setSelection(selected)
    }

    private fun refresh() {
        title.text = fileName + if (modified) "  •  modified" else ""
        filterRow.visibility = if (showSettings) View.GONE else View.VISIBLE
        if (showSettings) {
            list.adapter = rows(Settings.ALL.map { s -> s.label to s.display(image.memory) },
                Settings.ALL.map { it.group })
            return
        }
        val all = (1..Channels.COUNT).mapNotNull { Channels.read(image.memory, it) }
        val f = filter.selectedItemPosition
        channels = when (f) {
            0, -1 -> all
            26 -> all.filter { it.scanList == 0 }
            else -> all.filter { it.scanList == f }
        }
        list.adapter = rows(channels.map { it.line1() to it.line2() }, null)
    }

    private fun Channel.line1() = "%4d   %s".format(number, name.ifEmpty { "(no name)" })

    private fun Channel.line2(): String {
        val parts = mutableListOf("%.5f".format(freqHz / 1e6))
        if (duplex != 0) parts += Channels.DUPLEXES[duplex] + "%.4f".format(offsetHz / 1e6).trimEnd('0').trimEnd('.')
        parts += Channels.MODES[mode]
        parts += Channels.POWERS[power].substringBefore(" (")
        if (txTone.type != Tone.NONE) parts += "T $txTone"
        if (rxTone.type != Tone.NONE) parts += "R $rxTone"
        parts += when (scanList) { 0 -> "no list"; 25 -> "all lists"; else -> image.listNames(withEmpty = true)[scanList - 1].ifEmpty { "list $scanList" } }
        return parts.joinToString("   ")
    }

    /** Two-line rows; with [groups], a header above each new group. */
    private fun rows(items: List<Pair<String, String>>, groups: List<String>?) = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val box = LinearLayout(this@ImageEditorActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8))
            }
            if (groups != null && (p == 0 || groups[p] != groups[p - 1]))
                box.addView(TextView(this@ImageEditorActivity).apply {
                    text = groups[p]; setTypeface(typeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPadding(0, dp(12), 0, dp(6))
                })
            box.addView(TextView(this@ImageEditorActivity).apply { text = items[p].first; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) })
            box.addView(TextView(this@ImageEditorActivity).apply { text = items[p].second; alpha = 0.7f })
            return box
        }
    }

    // ---- channels ----

    private fun addChannel() {
        val used = (1..Channels.COUNT).filter { !Channels.isEmpty(image.memory, it) }
        val free = ((used.maxOrNull() ?: 0) + 1).takeIf { it <= Channels.COUNT }
            ?: (1..Channels.COUNT).firstOrNull { Channels.isEmpty(image.memory, it) }
            ?: return toast("All 1024 channels are in use.")
        val list = filter.selectedItemPosition.takeIf { it in 1..25 } ?: 0
        editChannel(Channel(number = free, scanList = list), isNew = true)
    }

    private fun editChannel(c: Channel, isNew: Boolean = false) {
        val form = Form(this)
        val number = form.text("Channel number (1–1024)", c.number.toString(), InputType.TYPE_CLASS_NUMBER)
        number.isEnabled = isNew
        val name = form.text("Name (10 characters)", c.name, maxLength = 10)
        val freq = form.text("Frequency (MHz)", "%.5f".format(c.freqHz / 1e6), DECIMAL)
        val duplex = form.choice("Duplex", Channels.DUPLEXES, c.duplex)
        val offset = form.text("Offset (MHz)", "%.5f".format(c.offsetHz / 1e6), DECIMAL)
        val mode = form.choice("Mode", Channels.MODES, c.mode)
        val power = form.choice("Power", Channels.POWERS, c.power)
        val step = form.choice("Step (kHz)", Channels.STEPS.map { "%s".format(it).removeSuffix(".0") }, c.step)
        val txTone = form.tone("TX tone", c.txTone)
        val rxTone = form.tone("RX tone (squelch)", c.rxTone)
        val lists = listOf("OFF") + image.listNames(withEmpty = true).mapIndexed { i, n -> if (n.isEmpty()) "List ${i + 1}" else "$n [${i + 1}]" } + "ALL"
        val scan = form.choice("Scan list", lists, c.scanList.coerceIn(0, 25))
        val compander = form.choice("Compander", Channels.COMPANDER, c.compander)
        val pttId = form.choice("PTT ID", Channels.PTT_ID, c.pttId)
        val txLock = form.check("TX Lock (TXLock)", c.txLock)
        val busy = form.check("Busy channel lockout (BusyCL)", c.busyLock)
        val reverse = form.check("Reverse frequencies", c.reverse)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isNew) "New channel" else "Channel ${c.number}")
            .setView(form.view)
            .setPositiveButton("OK", null)
            .setNegativeButton(android.R.string.cancel, null)
            .apply { if (!isNew) setNeutralButton("Delete") { _, _ -> confirmDelete(c) } }
            .show()
        // OK validates before closing.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = number.text.toString().toIntOrNull()
            val hz = mhz(freq.text.toString())
            val off = mhz(offset.text.toString())
            val nameText = name.text.toString()
            val error = when {
                n == null || n !in 1..Channels.COUNT -> "The channel number must be 1 to 1024."
                isNew && !Channels.isEmpty(image.memory, n) -> "Channel $n is already used."
                hz == null || hz !in Channels.MIN_HZ..Channels.MAX_HZ -> "The frequency must be 18 to 1300 MHz."
                off == null || off < 0 -> "The offset is not a valid number."
                nameText.any { it.code !in 32..126 } -> "The name can only use plain ASCII characters."
                else -> null
            }
            if (error != null) return@setOnClickListener toast(error)
            val edited = Channel(number = n!!, name = nameText.trimEnd(), freqHz = hz!!, duplex = duplex.selectedItemPosition,
                offsetHz = off!!, mode = mode.selectedItemPosition, power = power.selectedItemPosition,
                step = step.selectedItemPosition, txTone = txTone(), rxTone = rxTone(), scanList = scan.selectedItemPosition,
                compander = compander.selectedItemPosition, txLock = txLock.isChecked, busyLock = busy.isChecked,
                reverse = reverse.isChecked, pttId = pttId.selectedItemPosition)
            if (edited.duplex != 0 && edited.txHz !in Channels.MIN_HZ..Channels.MAX_HZ)
                return@setOnClickListener toast("The transmit frequency is outside 18 to 1300 MHz.")
            if (edited != c || isNew) {
                Channels.write(image.memory, edited)
                changed()
            }
            dialog.dismiss()
        }
    }

    private fun confirmDelete(c: Channel) {
        AlertDialog.Builder(this)
            .setMessage("Delete channel ${c.number} (${c.name})?")
            .setPositiveButton("Delete") { _, _ -> Channels.delete(image.memory, c.number); changed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- settings ----

    private fun editSetting(pos: Int) {
        val s = Settings.ALL[pos]
        val read = s.text
        val write = s.setText
        if (read != null && write != null) {
            val form = Form(this)
            val field = form.text("${s.maxLength} characters at most", read(image.memory), maxLength = s.maxLength)
            AlertDialog.Builder(this).setTitle(s.label).setView(form.view)
                .setPositiveButton("OK") { _, _ ->
                    val t = field.text.toString()
                    if (t.any { it.code !in 32..126 }) return@setPositiveButton toast("Use plain ASCII characters.")
                    if (t != read(image.memory)) { write(image.memory, t); changed() }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        val current = s.get(image.memory) ?: -1
        AlertDialog.Builder(this).setTitle(s.label)
            .setSingleChoiceItems(s.options.toTypedArray(), current) { d, which ->
                if (which != current) { s.set(image.memory, which); changed() }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ---- save / upload / leave ----

    private fun changed() {
        modified = true
        val keep = filter.selectedItemPosition
        updateFilterChoices(keep)                   // list names may have changed
        val first = list.firstVisiblePosition
        refresh()
        list.setSelection(first)
    }

    private fun save() {
        val choices = mutableListOf<String>()
        if (fileUri != null) choices += "Overwrite $fileName"
        choices += "Save as a new file in Downloads/CHIRP"
        AlertDialog.Builder(this).setTitle("Save")
            .setItems(choices.toTypedArray()) { _, which ->
                if (fileUri != null && which == 0) overwrite(fileUri!!) else saveCopy()
            }.show()
    }

    private fun overwrite(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(image.toBytes()) }
            modified = false; refresh()
            toast("Saved $fileName")
        } catch (e: Exception) {
            toast("Cannot overwrite this file (${e.message}). Save it as a new file instead.")
        }
    }

    private fun saveCopy() {
        val base = fileName.removeSuffix(".img").substringBefore("-edit")
        val name = "$base-edit-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}.img"
        FileSaver(this).save(name, image.toBytes(), { fileName = name; fileUri = null; modified = false; refresh() }, { }, "CHIRP")
    }

    private fun uploadEdited() {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_IMAGE, image.toBytes())
            .putExtra(EXTRA_NAME, fileName + if (modified) " (edited)" else ""))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!modified) {
            @Suppress("DEPRECATION") super.onBackPressed()
            return
        }
        AlertDialog.Builder(this).setMessage("Discard the changes to $fileName?")
            .setPositiveButton("Discard") { _, _ -> finish() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun mhz(text: String): Long? =
        text.trim().replace(',', '.').toBigDecimalOrNull()?.movePointRight(6)?.toLong()

    companion object {
        const val EXTRA_IMAGE = "image"
        const val EXTRA_NAME = "name"
        private const val STATE_MODIFIED = "modified"
        private const val DECIMAL = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        fun intent(context: Context, image: ByteArray, name: String, uri: Uri?) =
            Intent(context, ImageEditorActivity::class.java).setData(uri)
                .putExtra(EXTRA_IMAGE, image).putExtra(EXTRA_NAME, name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}

/** A scrolling form of labelled fields, for the edit dialogs. */
private class Form(private val context: Context) {
    private val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val view = ScrollView(context).apply {
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(box)
    }

    private fun label(text: String) = box.addView(TextView(context).apply { this.text = text; alpha = 0.7f; setPadding(0, 16, 0, 0) })

    fun text(label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT, maxLength: Int = 0): EditText {
        label(label)
        return EditText(context).apply {
            inputType = type; setText(value); isSingleLine = true
            if (maxLength > 0) filters = arrayOf(InputFilter.LengthFilter(maxLength))
            box.addView(this)
        }
    }

    fun choice(label: String, items: List<String>, selected: Int): Spinner {
        label(label)
        return Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected.coerceIn(0, items.size - 1))
            box.addView(this)
        }
    }

    fun check(label: String, checked: Boolean) = CheckBox(context).apply {
        text = label; isChecked = checked; box.addView(this)
    }

    /** A tone: its type, then the CTCSS tone or DCS code list that type uses. Returns a reader. */
    fun tone(label: String, tone: Tone): () -> Tone {
        val type = choice(label, Tone.TYPES, tone.type)
        val value = Spinner(context).also { box.addView(it) }
        fun fill(t: Int, index: Int) {
            val items = when (t) {
                Tone.CTCSS -> CTCSS_TONES.map { "%.1f Hz".format(it) }
                Tone.DCS, Tone.DCS_R -> DCS_CODES.map { "D%03d".format(it) }
                else -> listOf("—")
            }
            value.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
            value.setSelection(index.coerceIn(0, items.size - 1))
            value.isEnabled = t != Tone.NONE
        }
        fill(tone.type, tone.index)
        var lastType = tone.type
        type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == lastType) return
                // CTCSS and DCS lists differ; DCS normal/inverted share theirs.
                val keep = if ((pos >= Tone.DCS) == (lastType >= Tone.DCS) && pos != Tone.NONE) value.selectedItemPosition else 0
                lastType = pos; fill(pos, keep)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        return { Tone(type.selectedItemPosition, if (type.selectedItemPosition == Tone.NONE) 0 else value.selectedItemPosition) }
    }
}
