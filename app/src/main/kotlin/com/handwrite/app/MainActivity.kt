package com.handwrite.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.handwrite.app.render.PageView
import com.handwrite.app.render.PdfExporter
import com.handwrite.app.ui.Ui
import com.handwrite.core.font.GlyphSource
import com.handwrite.core.handwriting.AlphabetAligner
import com.handwrite.core.handwriting.GlyphAtlas
import com.handwrite.core.handwriting.GlyphRecognizer
import com.handwrite.core.handwriting.InkSegmenter
import com.handwrite.core.handwriting.LetterSplitter
import com.handwrite.core.layout.HandLayout
import com.handwrite.core.layout.LayoutResult
import com.handwrite.core.model.PaperSize
import com.handwrite.core.model.PaperStyle
import com.handwrite.core.model.TextStyle
import com.handwrite.core.model.WritingMode
import com.handwrite.core.pdf.PdfPageComposer
import kotlin.concurrent.thread

/**
 * HandWrite 0.0.1
 *
 *  - "Текст": type/paste text and see it written by hand on an A5 sheet;
 *  - "Мой почерк": photograph 1-5 pages of your own handwriting, the app segments the letters,
 *    recognises them with the on-device neural network and builds a personal font from them;
 *  - "Стиль": pen pressure, slant, spacing, paper, ink;
 *  - export: vector PDF (A5) generated on the device, no internet needed.
 */
class MainActivity : Activity() {

    private lateinit var preview: PageView
    private lateinit var input: EditText
    private lateinit var tabWrite: TextView
    private lateinit var tabLearn: TextView
    private lateinit var tabStyle: TextView
    private lateinit var settingsHost: LinearLayout
    private lateinit var pageLabel: TextView

    private var tab = 0
    private var style = TextStyle.defaultsFor(WritingMode.RUSSIAN_CURSIVE)
    private var sourceOverride: GlyphSource? = null
    private var atlas: GlyphAtlas? = null
    private var result: LayoutResult? = null
    private var lastExport: java.io.File? = null
    private var learningInProgress = false
    private val handler = Handler(Looper.getMainLooper())

    private val pickPhotos = 1001
    private val pickOnePhoto = 1002

    private val sampleRu = """
        Дорогой друг!
        Сегодня я хочу рассказать тебе о том, как важно беречь время.
        Каждое утро начинается с маленького шага: чашка чая, тёплый свет
        из окна и несколько строк в тетради.

        Мы часто откладываем важное на потом, но именно сейчас
        самое лучшее время начать. Попробуй написать письмо от руки,
        и ты почувствуешь, как оживает бумага.

        С уважением, твой друг.
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        atlas = HandAssets.loadAtlas(this)
        input = EditText(this).apply {
            setText(sampleRu)
            setTextSize(15f)
            setTextColor(Ui.TEXT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            maxLines = 8
            setPadding(Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 10f),
                Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 10f))
            background = Ui.rounded(Color.WHITE, Ui.dp(this@MainActivity, 14f).toFloat(), Ui.DIVIDER, 1)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) {
                    scheduleRender()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }
        setContentView(buildRoot())
        refreshAtlasBadge()
        render()
    }

    /* ------------------------------------------------------------------ */
    /* layout                                                              */
    /* ------------------------------------------------------------------ */

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
        }
        root.addView(buildHeader())
        root.addView(buildTabs())

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        buildPreviewCard(content)
        buildEditorCard(content)
        buildExportRow(content)
        settingsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(settingsHost)

        showTab(0)
        return root
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MainActivity, 18f), Ui.dp(this@MainActivity, 14f),
                Ui.dp(this@MainActivity, 18f), Ui.dp(this@MainActivity, 6f))
        }
        val title = Ui.text(this, "HandWrite", 22f, Ui.INK, true).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            letterSpacing = 0.01f
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val badge = Ui.text(this, "v0.0.1", 12f, Ui.TEXT2, false)
        row.addView(badge)
        return row
    }

    private fun buildTabs(): View {
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 4f),
                Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 8f))
        }
        fun mk(label: String): TextView {
            val t = Ui.text(this, label, 14f, Ui.TEXT2, true)
            t.gravity = Gravity.CENTER
            t.setPadding(0, Ui.dp(this@MainActivity, 9f), 0, Ui.dp(this@MainActivity, 9f))
            t.isClickable = true
            return t
        }
        tabWrite = mk("Текст")
        tabLearn = mk("Мой почерк")
        tabStyle = mk("Стиль")
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginEnd = Ui.dp(this@MainActivity, 4f)
        host.addView(tabWrite, LinearLayout.LayoutParams(lp))
        host.addView(tabLearn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = lp.marginEnd })
        host.addView(tabStyle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabWrite.setOnClickListener { showTab(0) }
        tabLearn.setOnClickListener { showTab(1) }
        tabStyle.setOnClickListener { showTab(2) }
        return host
    }

    private fun paintTabs() {
        val tabs = listOf(tabWrite to 0, tabLearn to 1, tabStyle to 2)
        for ((v, i) in tabs) {
            val selected = i == tab
            v.setTextColor(if (selected) Color.WHITE else Ui.TEXT)
            v.background = Ui.rounded(
                if (selected) Ui.INK else Color.parseColor("#00000000"),
                Ui.dp(this, 12f).toFloat()
            )
        }
    }

    private var previewCard: LinearLayout? = null
    private var editorCard: LinearLayout? = null

    private fun buildPreviewCard(content: LinearLayout) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.setPadding(Ui.dp(this, 14f), Ui.dp(this, 14f), Ui.dp(this, 14f), Ui.dp(this, 8f))
        card.background = Ui.rounded(Ui.CARD, Ui.dp(this, 20f).toFloat(), Ui.DIVIDER, Ui.dp(this, 1f))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(Ui.dp(this, 14f), Ui.dp(this, 0f), Ui.dp(this, 14f), Ui.dp(this, 10f))
        card.layoutParams = lp

        val hint = Ui.text(
            this,
            "Это пример встроенного шрифта. Чтобы писать своим почерком, откройте «Мой почерк» " +
                "и выберите 1–5 фото своих записей — приложение соберёт шрифт из них.",
            12.5f, Ui.TEXT2
        )
        hint.visibility = View.GONE
        card.addView(hint)
        emptyHint = hint

        preview = PageView(this)
        val ph = resources.displayMetrics.heightPixels * 0.44f
        card.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ph.toInt()))
        preview.onPageChanged = { index, count ->
            pageLabel.text = if (count <= 1) "Страница 1" else "Страница ${index + 1} из $count"
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(this@MainActivity, 6f), 0, 0)
        }
        val prev = Ui.button(this, "‹", false) { shiftPage(-1) }
        val next = Ui.button(this, "›", false) { shiftPage(1) }
        pageLabel = Ui.text(this, "Страница 1", 12.5f, Ui.TEXT2)
        pageLabel.gravity = Gravity.CENTER
        bar.addView(prev, LinearLayout.LayoutParams(Ui.dp(this, 44f), ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(pageLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(next, LinearLayout.LayoutParams(Ui.dp(this, 44f), ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(bar)
        previewCard = card
        content.addView(card)
    }

    private var currentPage = 0

    private fun shiftPage(delta: Int) {
        val n = preview.pageCount()
        if (n <= 0) return
        currentPage = (currentPage + delta).coerceIn(0, n - 1)
        preview.setPage(currentPage)
    }

    private fun buildEditorCard(content: LinearLayout) {
        val card = Ui.card(this, 12)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(Ui.dp(this, 14f), 0, Ui.dp(this, 14f), Ui.dp(this, 10f))
        card.layoutParams = lp
        card.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val row = Ui.row(this)
        row.setPadding(0, Ui.dp(this, 8f), 0, 0)
        val paste = Ui.chip(this, "Вставить", false) { pasteFromClipboard() }
        val clear = Ui.chip(this, "Очистить", false) { input.setText("") }
        val sample = Ui.chip(this, "Пример", false) { input.setText(sampleRu) }
        row.addView(paste)
        row.addView(View(this).also { it.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 6f), 1) })
        row.addView(clear)
        row.addView(View(this).also { it.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 6f), 1) })
        row.addView(sample)
        card.addView(row)
        editorCard = card
        content.addView(card)
    }

    private fun buildExportRow(content: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MainActivity, 14f), 0, Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 6f))
        }
        val export = Ui.button(this, "Экспорт PDF (A5)", true) { exportPdf() }
        val share = Ui.button(this, "Поделиться", false) { shareLast() }
        row.addView(export, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).also { it.marginEnd = Ui.dp(this@MainActivity, 8f) })
        row.addView(share, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row)
    }

    /* ------------------------------------------------------------------ */
    /* tabs                                                               */
    /* ------------------------------------------------------------------ */

    private fun showTab(index: Int) {
        tab = index
        paintTabs()
        settingsHost.removeAllViews()
        when (index) {
            0 -> buildWriteSettings()
            1 -> buildLearnTab()
            2 -> buildStyleSettings()
        }
    }

    private fun buildWriteSettings() {
        val card = Ui.card(this, 14)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(Ui.dp(this, 14f), Ui.dp(this, 4f), Ui.dp(this, 14f), 0)
        card.layoutParams = lp
        card.addView(Ui.sectionTitle(this, "Режим письма"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val modes = WritingMode.entries
        val chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in modes) {
            val chip = Ui.chip(this, shortModeName(m), style.mode == m) {
                applyMode(m)
            }
            chipsRow.addView(chip)
            chipsRow.addView(View(this).also { it.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 6f), 1) })
        }
        val hscroll = HorizontalScrollView(this)
        hscroll.isHorizontalScrollBarEnabled = false
        hscroll.addView(chipsRow)
        row.addView(hscroll)
        card.addView(row)

        card.addView(Ui.sectionTitle(this, "Шрифт"))
        card.addView(fontChips())
        settingsHost.addView(card)

        val info = Ui.card(this, 14)
        val lp2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp2.setMargins(Ui.dp(this, 14f), Ui.dp(this, 10f), Ui.dp(this, 14f), 0)
        info.layoutParams = lp2
        info.addView(
            Ui.text(
                this,
                "Русский прописной пишется слитно: буквы соединяются лигатурами шрифта " +
                    "и дополнительными перьевыми штрихами. Английский — печатными буквами без соединений. " +
                    "Всё считается на устройстве, интернет не нужен.",
                13f, Ui.TEXT2
            )
        )
        settingsHost.addView(info)
    }

    private fun shortModeName(m: WritingMode) = when (m) {
        WritingMode.RUSSIAN_CURSIVE -> "RU прописной"
        WritingMode.ENGLISH_PRINT -> "EN print"
        WritingMode.MIXED -> "Смешанный"
    }

    private fun fontChips(): View {
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (f in HandAssets.FONTS) {
            if (style.mode !in f.modes) continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(this@MainActivity, 8f), 0, Ui.dp(this@MainActivity, 8f))
            }
            val selected = style.fontAsset == f.asset
            val radio = TextView(this).apply {
                text = if (selected) "●" else "○"
                setTextColor(if (selected) Ui.ACCENT else Ui.TEXT2)
                textSize = 18f
                setPadding(0, 0, Ui.dp(this@MainActivity, 10f), 0)
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val name = Ui.text(this, f.label, 15f, Ui.TEXT, true)
            val desc = Ui.text(this, f.description, 12f, Ui.TEXT2)
            col.addView(name)
            col.addView(desc)
            row.addView(radio)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sample = DemoView(this, f.asset)
            row.addView(sample, LinearLayout.LayoutParams(Ui.dp(this, 96f), Ui.dp(this, 40f)))
            row.isClickable = true
            row.setOnClickListener {
                style = style.copy(fontAsset = f.asset, connectLetters = f.connects || style.mode != WritingMode.ENGLISH_PRINT)
                sourceOverride = null
                showTab(0)
                scheduleRender()
            }
            host.addView(row)
            host.addView(Ui.divider(this))
        }
        if (atlas != null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(this@MainActivity, 8f), 0, Ui.dp(this@MainActivity, 8f))
            }
            row.addView(Ui.text(this, "★  Мой почерк (по фото)", 15f, Ui.ACCENT, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.text(this, "${atlas!!.sampleCount()} букв", 12f, Ui.TEXT2))
            row.isClickable = true
            row.setOnClickListener { useAtlas() }
            host.addView(row)
        }
        return host
    }

    /** tiny preview of a font using the real layout engine */
    private inner class DemoView(
        context: android.content.Context,
        val asset: String
    ) : android.view.View(context) {
        private var built = false

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (!built) {
                built = true
                val src = HandAssets.source(this@MainActivity, asset) ?: return
                val st = style.copy(fontAsset = asset, sizePt = 15f, paperStyle = PaperStyle.BLANK,
                    connectLetters = HandAssets.FONTS.first { it.asset == asset }.connects,
                    paragraphIndentEm = 0f, marginLeftMm = 0f, marginRightMm = 0f, marginTopMm = 0f, marginBottomMm = 0f)
                val res = HandLayout.layout("Прописи", st, src)
                val page = res.pages.firstOrNull() ?: return
                val glyphs = page.lines.firstOrNull()?.glyphs ?: return
                if (glyphs.isEmpty()) return
                val minX = glyphs.minOf { g -> minOf(g.transform.applyX(g.x0, g.y0), g.transform.applyX(g.x1, g.y1)) }
                val maxX = glyphs.maxOf { g -> maxOf(g.transform.applyX(g.x0, g.y0), g.transform.applyX(g.x1, g.y1)) }
                val minY = glyphs.minOf { g -> minOf(g.transform.applyY(g.x0, g.y0), g.transform.applyY(g.x1, g.y1)) }
                val maxY = glyphs.maxOf { g -> maxOf(g.transform.applyY(g.x0, g.y0), g.transform.applyY(g.x1, g.y1)) }
                val s = minOf(width / (maxX - minX + 1e-3f), height / (maxY - minY + 1e-3f)) * 0.9f
                canvas.save()
                canvas.translate((width - (maxX - minX) * s) / 2f, (height - (maxY - minY) * s) / 2f)
                canvas.scale(s, s)
                canvas.translate(-minX, -minY)
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                p.color = Ui.INK
                p.style = android.graphics.Paint.Style.FILL
                for (g in glyphs) {
                    val path = android.graphics.Path()
                    for (seg in g.path.segs) {
                        when (seg) {
                            is com.handwrite.core.geom.Seg.Move -> path.moveTo(g.transform.applyX(seg.x, seg.y), g.transform.applyY(seg.x, seg.y))
                            is com.handwrite.core.geom.Seg.Line -> path.lineTo(g.transform.applyX(seg.x, seg.y), g.transform.applyY(seg.x, seg.y))
                            is com.handwrite.core.geom.Seg.Quad -> path.quadTo(
                                g.transform.applyX(seg.cx, seg.cy), g.transform.applyY(seg.cx, seg.cy),
                                g.transform.applyX(seg.x, seg.y), g.transform.applyY(seg.x, seg.y))
                            is com.handwrite.core.geom.Seg.Cubic -> path.cubicTo(
                                g.transform.applyX(seg.c1x, seg.c1y), g.transform.applyY(seg.c1x, seg.c1y),
                                g.transform.applyX(seg.c2x, seg.c2y), g.transform.applyY(seg.c2x, seg.c2y),
                                g.transform.applyX(seg.x, seg.y), g.transform.applyY(seg.x, seg.y))
                        }
                    }
                    path.close()
                    canvas.drawPath(path, p)
                }
                canvas.restore()
            }
        }
    }

    private fun applyMode(mode: WritingMode) {
        val defaults = TextStyle.defaultsFor(mode)
        style = defaults.copy(
            pressure = style.pressure,
            pressureVariation = style.pressureVariation,
            inkColor = style.inkColor,
            paperColor = style.paperColor,
            paperStyle = style.paperStyle,
            paperSize = style.paperSize,
            marginLeftMm = style.marginLeftMm,
            marginRightMm = style.marginRightMm,
            marginTopMm = style.marginTopMm,
            marginBottomMm = style.marginBottomMm,
            // печатные стили не наклоняются: «Наклон» обнуляется, чтобы буквы не шли боком
            slantDeg = if (mode == WritingMode.ENGLISH_PRINT) 0f else defaults.slantDeg,
            connectLetters = defaults.connectLetters,
            fontAsset = defaults.fontAsset,
            useFontLigatures = mode != WritingMode.ENGLISH_PRINT,
            useContextualAlternates = mode != WritingMode.ENGLISH_PRINT,
            seed = style.seed
        )
        sourceOverride = null
        val keepTab = tab
        showTab(keepTab)
        scheduleRender()
    }

    /* ------------------------------------------------------------------ */
    /* "Мой почерк"                                                       */
    /* ------------------------------------------------------------------ */

    private fun buildLearnTab() {
        val card = Ui.card(this, 14)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(Ui.dp(this, 14f), Ui.dp(this, 4f), Ui.dp(this, 14f), 0)
        card.layoutParams = lp
        card.addView(Ui.sectionTitle(this, "Шаг 1 · фотографии"))
        card.addView(
            Ui.text(
                this,
                "Сфотографируйте 1–5 страниц своих записей — конспект, список, письмо. " +
                    "Чем крупнее почерк и контрастнее ручка, тем лучше распознавание. " +
                    "Буквы находит и распознаёт нейросеть прямо на телефоне.",
                13f, Ui.TEXT2
            )
        )
        val row = Ui.row(this)
        row.setPadding(0, Ui.dp(this, 10f), 0, 0)
        val addBtn = Ui.button(this, "Выбрать фото (1–5)", true) { pickPhotosForLearning() }
        row.addView(addBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)

        card.addView(Ui.sectionTitle(this, "Шаг 2 · обучение"))
        val progress = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = 0
        }
        val status = Ui.text(this, "", 13f, Ui.TEXT2)
        progress.addView(bar)
        progress.addView(status)
        card.addView(progress)

        val learnRow = Ui.row(this)
        val learnBtn = Ui.button(this, "Собрать шрифт из фотографий", true) {
            pickPhotosForLearning()
        }
        learnRow.addView(learnBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(learnRow)
        card.addView(
            Ui.text(
                this,
                "Обычно из одной страницы получается 30–45 разных букв. Всё, что оказалось не " +
                    "распознано, попадёт в «Уточнить» — там буквы размечаются одним нажатием.",
                12.5f, Ui.TEXT2
            )
        )

        card.addView(Ui.sectionTitle(this, "Шаг 3 · проверка"))
        card.addView(
            Ui.text(
                this,
                "Проверьте буквы: неверные можно поправить, нажав на букву. " +
                    "Чем больше вариантов одной буквы, тем живее выглядит текст.",
                13f, Ui.TEXT2
            )
        )
        val galleryRow = Ui.row(this)
        galleryRow.setPadding(0, Ui.dp(this, 10f), 0, 0)
        val galleryBtn = Ui.button(this, "Показать буквы", false) { showGallery() }
        val clearBtn = Ui.button(this, "Удалить шрифт", false) {
            HandAssets.deleteAtlas(this)
            atlas = null
            Toast.makeText(this, "Свой шрифт удалён", Toast.LENGTH_SHORT).show()
            showTab(1)
            scheduleRender()
        }
        galleryRow.addView(galleryBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        galleryRow.addView(View(this).also { it.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 8f), 1) })
        galleryRow.addView(clearBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(galleryRow)
        settingsHost.addView(card)

        val help = Ui.card(this, 14)
        val lp2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp2.setMargins(Ui.dp(this, 14f), Ui.dp(this, 10f), Ui.dp(this, 14f), 0)
        help.layoutParams = lp2
        help.addView(Ui.sectionTitle(this, "Если нужен полный алфавит"))
        help.addView(
            Ui.text(
                this,
                "Необязательно. Напишите алфавит и цифры по одной букве в клетке — приложение " +
                    "подпишет буквы по порядку, и в шрифте сразу окажутся все 33 буквы. " +
                    "Удобно, если в обычных записях каких-то букв не хватает.",
                13f, Ui.TEXT2
            )
        )
        val sheetBtn = Ui.button(this, "Открыть лист-шаблон для печати", false) { showTemplateSheet() }
        sheetBtn.setPadding(0, Ui.dp(this, 10f), 0, 0)
        help.addView(sheetBtn)
        settingsHost.addView(help)

        learningStateProgress = progress
        learningStateBar = bar
        learningStateStatus = status
    }

    /**
     * Crops the network was not sure about (low score or a tiny margin to the runner-up). They are
     * not thrown away: the user can label them in one tap, which is far cheaper than writing the
     * whole alphabet again, and they are exactly the letters that would otherwise be missing from
     * the font.
     */
    private val unsureQueue = ArrayList<GlyphAtlas.Sample>()

    private var emptyHint: TextView? = null

    private var learningStateProgress: LinearLayout? = null
    private var learningStateBar: ProgressBar? = null
    private var learningStateStatus: TextView? = null

    private fun showTemplateSheet() {
        val src = HandAssets.source(this, style.fontAsset)
        val builder = StringBuilder()
        builder.append("абвгдеёжзийклмнопрстуфхцчшщъыьэюя\n")
        builder.append("АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ\n")
        builder.append("abcdefghijklmnopqrstuvwxyz\n")
        builder.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ\n")
        builder.append("0123456789 .,!?;:-—()\"'\n")
        val dlg = AlertDialog.Builder(this)
            .setTitle("Лист-шаблон")
            .setMessage(
                "Перепишите эти строки от руки, по одной букве в клетке, и сфотографируйте лист.\n\n" +
                    builder.toString()
            )
            .setPositiveButton("Скопировать текст") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("template", builder.toString()))
                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .create()
        @Suppress("UNUSED_VARIABLE") val s = src
        dlg.show()
    }

    private fun pickPhotosForLearning() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.type = "image/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        try {
            startActivityForResult(i, pickPhotos)
        } catch (e: Exception) {
            Toast.makeText(this, "Нет приложения для выбора фото", Toast.LENGTH_LONG).show()
        }
    }

    private fun onPhotosPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (learningInProgress) {
            Toast.makeText(this, "Распознавание уже идёт", Toast.LENGTH_SHORT).show()
            return
        }
        val recognizer = HandAssets.recognizer(this)
        if (recognizer == null) {
            Toast.makeText(this, "Не удалось загрузить нейросеть", Toast.LENGTH_LONG).show()
            return
        }
        learningInProgress = true
        unsureQueue.clear()
        // recognise only the alphabet of the chosen language: otherwise "к" comes back as "K"
        // and the atlas fills up with Latin look-alikes instead of Russian letters
        HandAssets.setRecognizerLanguage(recognizer, style.mode)
        val progress = learningStateProgress
        val bar = learningStateBar
        val status = learningStateStatus
        progress?.visibility = View.VISIBLE
        bar?.progress = 2
        status?.text = "Загрузка фотографий…"
        thread {
            val newAtlas = GlyphAtlas("Мой почерк")
            val report = StringBuilder()
            var sheetName: String? = null
            var sheetFailedRows: List<Int>? = null
            var templateCandidates: List<InkSegmenter.Page>? = null
            var rowsChecked = false
            try {
                for ((pageIdx, uri) in uris.withIndex()) {
                    val gray = PhotoLoader.load(this, uri) ?: continue
                    handler.post {
                        bar?.progress = 5 + pageIdx * 60 / uris.size
                        status?.text = "Страница ${pageIdx + 1}: поиск букв…"
                    }
                    var page = InkSegmenter.segment(gray.data, gray.width, gray.height, preMask = gray.ink)
                    val pageGuesses = classifyAll(recognizer, page, bar, pageIdx, uris.size)
                    // A page that looks like a printed template sheet is re-segmented as a grid:
                    // there the labels are known from the position, which beats OCR hands down.
                    if (!rowsChecked) {
                        rowsChecked = true
                        val counts = page.rows.map { it.size }
                        val sheetPage = InkSegmenter.segment(gray.data, gray.width, gray.height, template = true, preMask = gray.ink)
                        val sheetGuesses = classifyAll(recognizer, sheetPage, bar, pageIdx, uris.size)
                        val indexOfCrop = HashMap<InkSegmenter.Crop, Int>()
                        for ((ci, crop) in sheetPage.crops.withIndex()) indexOfCrop[crop] = ci
                        val detection = AlphabetAligner.detect(
                            sheetPage.rows.map { row -> row.map { c -> sheetGuesses.getOrElse(indexOfCrop[c] ?: -1) { emptyList() } } },
                            sheetPage.rows.map { it.size }
                        )
                        if (detection != null && detection.quality > 0.55f) {
                            sheetName = detection.sheet.name
                            templateCandidates = listOf(sheetPage)
                            val accepted = addSamples(newAtlas, sheetPage, detection.labels, pageIdx)
                            report.append(
                                "Лист «${detection.sheet.name}»: ${detection.matched} из ${detection.expected} букв, " +
                                    "добавлено $accepted\n"
                            )
                            continue
                        }
                        sheetFailedRows = counts
                    }
                    // plain page: keep only crops the network is confident about
                    val full: List<List<GlyphRecognizer.Guess>> = resolveScripts(pageGuesses, page)
                    var accepted = 0
                    val splitPairs = ArrayList<LetterSplitter.Split>()
                    for ((i, crop) in page.crops.withIndex()) {
                        if (i % 8 == 0) {
                            val frac = i.toFloat() / maxOf(1, page.crops.size)
                            handler.post {
                                bar?.progress = (5 + pageIdx * 60 / uris.size + (frac * 55 / uris.size)).toInt()
                            }
                        }
                        val gs = full[i]
                        val best = gs.firstOrNull() ?: continue
                        // A wide crop is often two letters the writer joined. Learning it as one
                        // letter would put a two-letter blob into the atlas, so it is cut first; the
                        // halves are learned as letters, and the pair is remembered as a ligature
                        // because that crop is literally a photograph of that join.
                        if (crop.w > page.lineHeight * LetterSplitter.MIN_ASPECT) {
                            val split = LetterSplitter.split(crop, page, page.ink, page.width, page.height) { tile ->
                                val gg = recognizer.classifyTile(tile).firstOrNull()
                                if (gg == null) 0 to 0f else gg.codePoint to gg.score
                            }
                            if (split != null) {
                                val labelA = recognizer.classifyTile(split.first.tile).firstOrNull()
                                val labelB = recognizer.classifyTile(split.second.tile).firstOrNull()
                                val ea = HandAssets.embedder(this)?.embed(split.first.tile)
                                val eb = HandAssets.embedder(this)?.embed(split.second.tile)
                                if (labelA != null && labelB != null && ea != null && eb != null) {
                                    for ((half, lab, emb) in listOf(
                                        Triple(split.first, labelA, ea),
                                        Triple(split.second, labelB, eb)
                                    )) {
                                        newAtlas.add(
                                            GlyphAtlas.Sample(
                                                lab.codePoint, half.tile, emb, lab.score, half.aspect,
                                                pageIdx, half.x.toFloat(), half.y.toFloat(),
                                                crop.sizeRatio, crop.centerRatio
                                            )
                                        )
                                        accepted++
                                    }
                                    splitPairs.add(split)
                                    continue
                                }
                            }
                        }
                        val second = gs.getOrNull(1)
                        val margin = if (second == null) 1f else best.score - second.score
                        val emb = HandAssets.embedder(this)?.embed(crop.tile) ?: continue
                        // A wrong label in the atlas is much worse than a missing one: it makes the
                        // rendered text show the wrong letter, which the user reads as "почерк
                        // создан с ошибкой". So only confident letters are learned, everything else
                        // goes to the "Уточнить" review where one tap fixes it.
                        val unsure = best.score < 0.62f || margin < 0.14f
                        if (best.score < 0.45f) {
                            // keep it for a one-tap correction, but do not pollute the font with it
                            if (unsureQueue.size < 60) {
                                unsureQueue.add(
                                    GlyphAtlas.Sample(
                                        best.codePoint, crop.tile, emb, 0.2f, crop.aspect, pageIdx,
                                        crop.x.toFloat(), crop.y.toFloat(), crop.sizeRatio, crop.centerRatio
                                    )
                                )
                            }
                            continue
                        }
                        if (unsure && unsureQueue.size < 80) {
                            unsureQueue.add(
                                GlyphAtlas.Sample(
                                    best.codePoint, crop.tile, emb, 0.25f, crop.aspect, pageIdx,
                                    crop.x.toFloat(), crop.y.toFloat(), crop.sizeRatio, crop.centerRatio
                                )
                            )
                        }
                        val sample = GlyphAtlas.Sample(
                            codePoint = best.codePoint,
                            tile = crop.tile,
                            embedding = emb,
                            confidence = best.score + margin * 0.3f,
                            aspect = crop.aspect,
                            sourcePage = pageIdx,
                            pageX = crop.x.toFloat(),
                            pageY = crop.y.toFloat(),
                            sizeRatio = crop.sizeRatio,
                            centerRatio = crop.centerRatio
                        )
                        if (newAtlas.add(sample)) accepted++
                    }
                    // The joins the writer actually made: the pairs that were cut out of one crop were
                    // written without lifting the pen, so the pair itself becomes a ligature glyph.
                    var ligatures = 0
                    run {
                        val embedder = HandAssets.embedder(this) ?: null
                        for (split in splitPairs) {
                            val la = recognizer.classifyTile(split.first.tile).firstOrNull() ?: continue
                            val lb = recognizer.classifyTile(split.second.tile).firstOrNull() ?: continue
                            if (split.score < 0.6f) continue
                            val x0 = minOf(split.first.x, split.second.x)
                            val x1 = maxOf(split.first.x + split.first.w, split.second.x + split.second.w)
                            val y0 = minOf(split.first.y, split.second.y)
                            val y1 = maxOf(split.first.y + split.first.h, split.second.y + split.second.h)
                            val box = InkSegmenter.Box(x0, y0, x1, y1)
                            val tile = InkSegmenter.cropTile(page.ink, page.width, page.height, box)
                            val e = embedder?.embed(tile) ?: continue
                            val pair = String(Character.toChars(la.codePoint)) + String(Character.toChars(lb.codePoint))
                            val aspect = (x1 - x0).toFloat() / kotlin.math.max(1f, (y1 - y0).toFloat())
                            if (newAtlas.addLigature(pair, tile, e, split.score, aspect, pageIdx, split.first.sizeRatio, split.first.centerRatio)) {
                                ligatures++
                            }
                        }
                    }
                    report.append(
                        "Страница ${pageIdx + 1}: найдено ${page.crops.size}, принято $accepted букв" +
                            (if (ligatures > 0) ", лигатур $ligatures" else "") + "\n"
                    )
                }
                handler.post {
                    bar?.progress = 92
                    status?.text = "Сохранение…"
                }
                if (newAtlas.sampleCount() > 0) {
                    newAtlas.deriveCapitals()
                    HandAssets.saveAtlas(this, newAtlas)
                    atlas = newAtlas
                }
                handler.post {
                    bar?.progress = 100
                    learningInProgress = false
                    status?.text = "Готово: ${newAtlas.letterCount()} разных букв, ${newAtlas.sampleCount()} образцов"
                    if (sheetName != null) report.insert(0, "Распознан лист-шаблон «$sheetName» 🙂\n\n")
                    report.append(
                        "\nИтог: ${newAtlas.letterCount()} букв, ${newAtlas.sampleCount()} образцов, " +
                            "средняя уверенность ${(newAtlas.avgConfidence() * 100).toInt()}%"
                    )
                    refreshAtlasBadge()
                    val hint = when {
                        newAtlas.letterCount() >= 30 -> ""
                        sheetFailedRows != null && newAtlas.letterCount() < 20 ->
                            "\n\nПодсказка: буквы лучше распознаются на крупном почерке и при хорошем свете. " +
                                "Если нужен сразу весь алфавит — распечатайте лист-шаблон внизу экрана " +
                                "«Мой почерк» и сфотографируйте заполненный лист."
                        else -> ""
                    }
                    AlertDialog.Builder(this)
                        .setTitle(if (newAtlas.letterCount() == 0) "Буквы не найдены" else "Мой шрифт готов")
                        .setMessage(report.toString() + hint)
                        .setPositiveButton("Использовать") { _, _ ->
                            if (newAtlas.letterCount() > 0) {
                                useAtlas()
                                showTab(0)
                            }
                        }
                        .setNeutralButton(if (unsureQueue.isEmpty()) null else "Уточнить ${unsureQueue.size}") { _, _ ->
                            showUnsure()
                        }
                        .setNegativeButton("Проверить буквы") { _, _ -> showGallery() }
                        .show()
                }
            } catch (e: Exception) {
                handler.post {
                    learningInProgress = false
                    bar?.progress = 0
                    status?.text = "Ошибка: ${e.message}"
                    Toast.makeText(this, "Не удалось обработать фото: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** classifies every crop of a page, posting progress to the UI thread */
    private fun classifyAll(
        recognizer: GlyphRecognizer,
        page: InkSegmenter.Page,
        bar: ProgressBar?,
        pageIdx: Int,
        pageCount: Int
    ): List<List<GlyphRecognizer.Guess>> {
        val out = ArrayList<List<GlyphRecognizer.Guess>>(page.crops.size)
        for ((i, crop) in page.crops.withIndex()) {
            // A handwritten page is often mixed even when the style says otherwise: English words
            // between Russian ones, or a whole English page learned while the style is "RU".
            // Asking the recogniser for both scripts lets us keep them all; the language is then
            // decided per word (see resolveScripts).
            out.add(recognizer.classify(crop, page, bothScripts = true))
            if (i % 8 == 0) {
                val frac = i.toFloat() / maxOf(1, page.crops.size)
                handler.post { bar?.progress = (5 + pageIdx * 60 / pageCount + (frac * 30 / pageCount)).toInt() }
            }
        }
        return out
    }

    /**
     * Keeps one script per word.
     *
     * Cyrillic and Latin share most letterforms ("о/o", "а/a", "е/e", "р/p", "с/c", "х/x"),
     * so a Russian page can come back with a few English letters and an English page with a few
     * Russian ones. Words, however, are written in one script. For every run of letters we add up
     * the confidence the best guess would have in each script and keep the winner for the whole
     * run: an English word is then learned as English (not dropped as "not in the RU charset"),
     * and Latin look-alikes no longer sneak into a Russian word.
     */
    private fun resolveScripts(
        guesses: List<List<GlyphRecognizer.Guess>>,
        page: InkSegmenter.Page
    ): List<List<GlyphRecognizer.Guess>> {
        val out = ArrayList<List<GlyphRecognizer.Guess>>(guesses.size)
        var run = ArrayList<Int>()          // crop indices of the current word
        var cyr = 0f
        var lat = 0f
        fun flush() {
            val preferLatin = lat > cyr * 1.05f
            for (idx in run) {
                val gs = guesses[idx]
                val keep = if (preferLatin) gs.filter { GlyphRecognizer.scriptOf(it.codePoint) == 2 }
                else gs.filter { GlyphRecognizer.scriptOf(it.codePoint) == 1 }
                out[idx] = if (keep.isNotEmpty()) keep else gs
            }
            run = ArrayList()
            cyr = 0f
            lat = 0f
        }
        for (i in guesses.indices) {
            val gs = guesses[i]
            val best = gs.firstOrNull()
            if (best == null || GlyphRecognizer.scriptOf(best.codePoint) == 0) {
                flush()
                out.add(gs)
                continue
            }
            out.add(gs)
            run.add(i)
            val bestCyr = gs.firstOrNull { GlyphRecognizer.scriptOf(it.codePoint) == 1 }?.score ?: 0f
            val bestLat = gs.firstOrNull { GlyphRecognizer.scriptOf(it.codePoint) == 2 }?.score ?: 0f
            cyr += bestCyr
            lat += bestLat
            // a word never spans a whole line: stop at a wide gap (the segmenter keeps x order)
            val next = guesses.getOrNull(i + 1)
            val crop = page.crops.getOrNull(i)
            val nextCrop = page.crops.getOrNull(i + 1)
            val gapTooWide = crop != null && nextCrop != null &&
                (nextCrop.x - (crop.x + crop.w)).toFloat() > page.lineHeight * 0.55f
            if (next == null || next.isEmpty() || gapTooWide) flush()
        }
        flush()
        return out
    }

    private fun addSamples(atlas: GlyphAtlas, page: InkSegmenter.Page, labels: IntArray, pageIdx: Int): Int {
        var accepted = 0
        for ((i, crop) in page.crops.withIndex()) {
            val cp = labels.getOrElse(i) { 0 }
            if (cp == 0) continue
            val emb = HandAssets.embedder(this)?.embed(crop.tile) ?: continue
            val sample = GlyphAtlas.Sample(
                codePoint = cp,
                tile = crop.tile,
                embedding = emb,
                confidence = 0.95f,
                aspect = crop.aspect,
                sourcePage = pageIdx,
                pageX = crop.x.toFloat(),
                pageY = crop.y.toFloat(),
                sizeRatio = crop.sizeRatio,
                centerRatio = crop.centerRatio
            )
            if (atlas.add(sample)) accepted++
        }
        return accepted
    }

    private fun useAtlas() {
        val a = atlas ?: return
        // letters the user did not write come from the selected bundled font, so mixed text
        // (Russian learned + English words, or missing letters) never renders as empty space
        sourceOverride = HandAssets.atlasSource(a, this, style.fontAsset)
        // a learned cursive atlas joins letters, so switch connections on unless the user writes
        // in print mode
        if (style.mode != WritingMode.ENGLISH_PRINT && !style.connectLetters) {
            style = style.copy(connectLetters = true)
        }
        Toast.makeText(this, "Пишем вашим почерком", Toast.LENGTH_SHORT).show()
        scheduleRender()
    }

    private fun refreshAtlasBadge() {
        // the badge is part of the header text, which is rebuilt by the tab switcher
        emptyHint?.visibility = if (atlas == null) View.VISIBLE else View.GONE
    }

    /**
     * One-tap labelling of the crops the network was unsure about ("Уточнить N").
     * The guessed letter is shown above every crop; tapping assigns the guess, long-pressing opens
     * the full character list.
     */
    private fun showUnsure() {
        if (unsureQueue.isEmpty()) {
            Toast.makeText(this, "Все буквы распознаны уверенно", Toast.LENGTH_SHORT).show()
            return
        }
        val a = atlas ?: return
        val scroll = ScrollView(this)
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 8f),
                Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 8f))
        }
        host.addView(
            Ui.text(
                this,
                "Нажмите на букву, если догадка верна, или удерживайте, чтобы выбрать другую. " +
                    "Так шрифт становится полнее за пару секунд.",
                13f, Ui.TEXT2
            )
        )
        var pending = unsureQueue.toList()
        fun rebuild() {
            host.removeAllViews()
            host.addView(Ui.text(this, "Осталось: ${pending.size}", 13f, Ui.TEXT2))
            val row = Ui.row(this)
            row.setPadding(0, Ui.dp(this, 8f), 0, 0)
            for (s in pending.take(6)) {
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val img = ImageView(this)
                img.setImageBitmap(tileToBitmap(s.tile, 72))
                img.setBackgroundColor(Color.WHITE)
                img.setOnClickListener {
                    a.add(s)
                    pending = pending - s
                    rebuild()
                }
                img.setOnLongClickListener {
                    pickCharacter(s, a) { pending = pending - s; rebuild() }
                    true
                }
                col.addView(img, LinearLayout.LayoutParams(Ui.dp(this, 64f), Ui.dp(this, 64f)))
                col.addView(
                    Ui.text(this, String(Character.toChars(s.codePoint)), 13f, Ui.INK, true)
                )
                row.addView(col, LinearLayout.LayoutParams(Ui.dp(this, 68f), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            host.addView(row)
            if (pending.isEmpty()) {
                refreshAtlasBadge()
                scheduleRender()
            }
        }
        rebuild()
        scroll.addView(host)
        AlertDialog.Builder(this)
            .setTitle("Уточнить буквы")
            .setView(scroll)
            .setPositiveButton("Готово") { _, _ ->
                HandAssets.saveAtlas(this, a)
                atlas = a
                unsureQueue.clear()
                scheduleRender()
            }
            .show()
    }

    /** asks the user which character a crop really is, then adds it to the atlas */
    private fun pickCharacter(sample: GlyphAtlas.Sample, a: GlyphAtlas, done: () -> Unit) {
        val letters = (AlphabetAligner.RU + AlphabetAligner.RU_UPPER + AlphabetAligner.EN +
            AlphabetAligner.EN_UPPER + AlphabetAligner.DIGITS + ".,!?;:-—()\"'")
            .map { it.toString() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Какая это буква?")
            .setItems(letters) { _, which ->
                val ch = letters[which]
                a.add(
                    GlyphAtlas.Sample(
                        ch.codePointAt(0), sample.tile, sample.embedding, 0.9f, sample.aspect,
                        sample.sourcePage, sample.pageX, sample.pageY, sample.sizeRatio, sample.centerRatio
                    )
                )
                done()
            }
            .setNegativeButton("Пропустить") { _, _ -> done() }
            .show()
    }

    private fun showGallery() {
        val a = atlas ?: run {
            Toast.makeText(this, "Сначала обучите шрифт по фото", Toast.LENGTH_SHORT).show()
            return
        }
        val scroll = ScrollView(this)
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 16f), Ui.dp(this@MainActivity, 8f),
                Ui.dp(this@MainActivity, 16f), Ui.dp(this@MainActivity, 16f))
        }
        val letters = a.samples.map { it.codePoint }.distinct().sorted()
        host.addView(Ui.text(this, "Найдено букв: ${letters.size}, образцов: ${a.sampleCount()}", 13f, Ui.TEXT2))
        for (cp in letters) {
            val row = Ui.row(this)
            row.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
            val label = Ui.text(this, String(Character.toChars(cp)), 22f, Ui.INK, true)
            label.width = Ui.dp(this, 46f)
            row.addView(label)
            val thumbs = Ui.row(this)
            for (s in a.variants(cp)) {
                val img = ImageView(this)
                val bmp = tileToBitmap(s.tile, 64)
                img.setImageBitmap(bmp)
                img.setBackgroundColor(Color.WHITE)
                img.setPadding(2, 2, 2, 2)
                thumbs.addView(img, LinearLayout.LayoutParams(Ui.dp(this, 40f), Ui.dp(this, 40f)).also { it.marginEnd = Ui.dp(this@MainActivity, 4f) })
            }
            row.addView(thumbs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.isClickable = true
            row.setOnClickListener { editLetter(a, cp) }
            host.addView(row)
            host.addView(Ui.divider(this))
        }
        scroll.addView(host)
        AlertDialog.Builder(this)
            .setTitle("Проверка букв")
            .setView(scroll)
            .setPositiveButton("Готово", null)
            .show()
    }

    private fun editLetter(a: GlyphAtlas, cp: Int) {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Буква «${String(Character.toChars(cp))}»")
            .setMessage("Что делать с этой буквой?")
            .setPositiveButton("Переклассифицировать") { _, _ ->
                val options = (AlphabetAligner.RU + AlphabetAligner.RU_UPPER + AlphabetAligner.EN +
                    AlphabetAligner.EN_UPPER + AlphabetAligner.DIGITS)
                    .map { it.toString() }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Введите настоящую букву")
                    .setItems(options) { _, which ->
                        val code = options[which].codePointAt(0)
                        for (s in a.variants(cp).toList()) {
                            a.retarget(s, code)
                        }
                        HandAssets.saveAtlas(this, a)
                        atlas = a
                        showGallery()
                    }
                    .show()
            }
            .setNegativeButton("Закрыть", null)
            .create()
        @Suppress("UNUSED_VARIABLE") val d = dlg
        dlg.show()
    }

    private fun tileToBitmap(tile: FloatArray, size: Int): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val px = IntArray(size * size)
        for (i in px.indices) {
            val v = (255 * (1f - tile[i].coerceIn(0f, 1f))).toInt()
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }

    /* ------------------------------------------------------------------ */
    /* style tab                                                          */
    /* ------------------------------------------------------------------ */

    private fun buildStyleSettings() {
        val card = Ui.card(this, 14)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(Ui.dp(this, 14f), Ui.dp(this, 4f), Ui.dp(this, 14f), 0)
        card.layoutParams = lp

        card.addView(Ui.sectionTitle(this, "Нажим и толщина"))
        card.addView(slider("Сила нажима", 0f, 1f, style.pressure) { v ->
            style = style.copy(pressure = v)
            scheduleRender()
        })
        card.addView(slider("Неровность нажима", 0f, 1f, style.pressureVariation) { v ->
            style = style.copy(pressureVariation = v)
            scheduleRender()
        })
        card.addView(slider("Наклон, ° (вправо «+»)", -10f, 20f, style.slantDeg) { v ->
            style = style.copy(slantDeg = v)
            scheduleRender()
        })
        card.addView(slider("Размер, pt", 10f, 26f, style.sizePt) { v ->
            style = style.copy(sizePt = v)
            scheduleRender()
        })
        card.addView(slider("Межстрочный интервал", 1.15f, 2.4f, style.lineSpacing) { v ->
            style = style.copy(lineSpacing = v)
            scheduleRender()
        })
        card.addView(slider("Дрожание руки", 0f, 1f, style.jitterEm / 0.05f) { v ->
            style = style.copy(jitterEm = v * 0.05f, jitterRotDeg = v * 2.2f)
            scheduleRender()
        })
        card.addView(slider("Прозрачность чернил", 0.35f, 1f, style.inkDensity) { v ->
            style = style.copy(inkDensity = v)
            scheduleRender()
        })

        card.addView(Ui.sectionTitle(this, "Соединения букв"))
        val connRow = Ui.row(this)
        connRow.addView(Ui.chip(this, "Слитно", style.connectLetters) {
            style = style.copy(connectLetters = true, letterSpacingEm = 0.004f)
            showTab(2)
            scheduleRender()
        })
        connRow.addView(View(this).also { it.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 6f), 1) })
        connRow.addView(Ui.chip(this, "Печатно", !style.connectLetters) {
            style = style.copy(connectLetters = false, letterSpacingEm = 0.03f, slantDeg = 0f)
            showTab(2)
            scheduleRender()
        })
        card.addView(connRow)

        card.addView(Ui.sectionTitle(this, "Бумага"))
        val paperRow = Ui.row(this)
        for (p in PaperStyle.entries) {
            val chip = Ui.chip(this, p.label, style.paperStyle == p) {
                style = style.copy(paperStyle = p)
                showTab(2)
                scheduleRender()
            }
            paperRow.addView(chip)
            paperRow.addView(View(this).also { it.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 6f), 1) })
        }
        val paperScroll = HorizontalScrollView(this)
        paperScroll.isHorizontalScrollBarEnabled = false
        paperScroll.addView(paperRow)
        card.addView(paperScroll)

        card.addView(Ui.sectionTitle(this, "Формат страницы"))
        val sizeRow = Ui.row(this)
        for (p in PaperSize.entries) {
            val chip = Ui.chip(this, p.label, style.paperSize == p) {
                style = style.copy(paperSize = p)
                showTab(2)
                scheduleRender()
            }
            sizeRow.addView(chip)
            sizeRow.addView(View(this).also { it.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 6f), 1) })
        }
        card.addView(sizeRow)

        card.addView(Ui.sectionTitle(this, "Чернила"))
        val inkRow = Ui.row(this)
        for (c in INKS) {
            val sw = View(this)
            sw.background = Ui.rounded(c, Ui.dp(this, 20f).toFloat(),
                if (style.inkColor == c) Ui.ACCENT else Ui.DIVIDER, if (style.inkColor == c) 3 else 1)
            sw.isClickable = true
            sw.setOnClickListener {
                style = style.copy(inkColor = c)
                showTab(2)
                scheduleRender()
            }
            inkRow.addView(sw, LinearLayout.LayoutParams(Ui.dp(this, 34f), Ui.dp(this, 34f)).also { it.marginEnd = Ui.dp(this@MainActivity, 8f) })
        }
        card.addView(inkRow)

        val reseed = Ui.button(this, "Другое дрожание руки", false) {
            style = style.copy(seed = System.nanoTime())
            scheduleRender()
        }
        reseed.setPadding(0, Ui.dp(this, 12f), 0, Ui.dp(this, 4f))
        card.addView(reseed)
        settingsHost.addView(card)
    }

    private val INKS = intArrayOf(
        0xFF1B2A4A.toInt(),
        0xFF101010.toInt(),
        0xFF1F3D8C.toInt(),
        0xFF7A1F1F.toInt(),
        0xFF1E5A3A.toInt(),
        0xFF4B2C6B.toInt(),
        0xFF6B4A1E.toInt()
    )

    private fun slider(label: String, min: Float, max: Float, value: Float, onChange: (Float) -> Unit): View {
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = Ui.row(this)
        val name = Ui.text(this, label, 13.5f, Ui.TEXT)
        val valText = Ui.text(this, fmt(value), 13f, Ui.TEXT2)
        top.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(valText)
        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = (((value - min) / (max - min)) * 100).toInt().coerceIn(0, 100)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val v = min + (max - min) * p / 100f
                valText.text = fmt(v)
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
        host.addView(top)
        host.addView(seek)
        return host
    }

    private fun fmt(v: Float): String =
        if (kotlin.math.abs(v - v.toInt()) < 0.01f) v.toInt().toString() else String.format(java.util.Locale.US, "%.2f", v)

    /* ------------------------------------------------------------------ */
    /* rendering & export                                                 */
    /* ------------------------------------------------------------------ */

    private var renderScheduled = false

    private fun scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        handler.postDelayed({
            renderScheduled = false
            render()
        }, 220)
    }

    @Volatile
    private var renderGeneration = 0

    private fun render() {
        val text = input.text?.toString().orEmpty()
        val src = sourceOverride ?: HandAssets.source(this, style.fontAsset)
        if (src == null) {
            Toast.makeText(this, "Шрифт не найден", Toast.LENGTH_LONG).show()
            return
        }
        val mode = style
        val generation = ++renderGeneration
        // layout runs off the UI thread: a full page takes ~150-400 ms on a phone
        thread {
            try {
                val res = HandLayout.layout(if (text.isBlank()) " " else text, mode, src, 400)
                handler.post {
                    if (generation != renderGeneration) return@post
                    result = res
                    currentPage = currentPage.coerceIn(0, maxOf(0, res.pages.size - 1))
                    preview.show(res, src, currentPage)
                }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "Ошибка вёрстки: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun exportPdf() {
        val text = input.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Введите текст", Toast.LENGTH_SHORT).show()
            return
        }
        val src = sourceOverride ?: HandAssets.source(this, style.fontAsset) ?: return
        val mode = style
        Toast.makeText(this, "Готовим PDF…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val bytes = PdfPageComposer.compose(text, mode, src, 400)
                val f = PdfExporter.save(this, bytes, mode.mode.key + "-" + mode.fontAsset.substringBefore('.'))
                handler.post {
                    lastExport = f
                    AlertDialog.Builder(this)
                        .setTitle("PDF готов")
                        .setMessage("${f.name}\n${bytes.size / 1024} КБ · ${mode.paperSize.label}\n${f.absolutePath}")
                        .setPositiveButton("Открыть") { _, _ -> openPdf(f) }
                        .setNeutralButton("Поделиться") { _, _ -> sharePdf(f) }
                        .setNegativeButton("Закрыть", null)
                        .show()
                }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareLast() {
        val f = lastExport ?: run { exportPdf(); return }
        sharePdf(f)
    }

    private fun openPdf(f: java.io.File) {
        try {
            startActivity(PdfExporter.openIntent(f))
        } catch (e: Exception) {
            Toast.makeText(this, "Нет приложения для PDF — используем печать", Toast.LENGTH_LONG).show()
            PdfExporter.print(this, f, "HandWrite")
        }
    }

    private fun sharePdf(f: java.io.File) {
        try {
            startActivity(Intent.createChooser(PdfExporter.shareIntent(f), "Поделиться PDF"))
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось поделиться: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val t = clip.getItemAt(0).coerceToText(this)
            input.setText(t)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == pickPhotos || requestCode == pickOnePhoto) {
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until minOf(5, clip.itemCount)) uris.add(clip.getItemAt(i).uri)
            } else {
                data.data?.let { uris.add(it) }
            }
            onPhotosPicked(uris)
        }
    }
}
