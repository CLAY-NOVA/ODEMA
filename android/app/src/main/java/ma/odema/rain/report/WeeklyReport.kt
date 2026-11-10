package ma.odema.rain.report

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ma.odema.core.farm.Campaigns
import ma.odema.core.farm.FarmData
import ma.odema.core.farm.RainHistory
import ma.odema.core.ops.OpsAnalytics
import ma.odema.core.ops.OpsData
import ma.odema.core.ops.WeekReport
import ma.odema.core.ops.Work
import ma.odema.rain.R
import ma.odema.rain.data.FarmState
import ma.odema.rain.ui.odema.Labels
import ma.odema.rain.ui.odema.OdFmt
import ma.odema.rain.ui.odema.madText
import ma.odema.rain.ui.odema.qtyText
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/** The weekly C-R (compte-rendu): built from the shared records, as a PDF or a WhatsApp text. */
object WeeklyReport {
    fun weekOf(day: LocalDate): Pair<Long, Long> {
        val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return monday.toEpochDay() to monday.plusDays(6).toEpochDay()
    }

    fun build(farm: FarmState, ops: OpsData, from: Long, to: Long): WeekReport =
        OpsAnalytics.weekReport(ops, farm.data, from, to, LocalDate.now().toEpochDay()) { parcelId ->
            val p = farm.parcel(parcelId) ?: return@weekReport null
            val h = RainHistory(farm.seriesFor(p), farm.data.gauges.filter { it.parcelId == parcelId })
            if (h.hasData) h.sum(from, to).mm else null
        }

    private fun campaignRain(farm: FarmState, parcelName: String, to: Long): Double? {
        val p = farm.data.parcels.firstOrNull { it.name == parcelName } ?: return null
        val h = RainHistory(farm.seriesFor(p), farm.data.gauges.filter { it.parcelId == p.id })
        return if (h.hasData) h.sum(Campaigns.start(Campaigns.ofDay(to)).toEpochDay(), to).mm else null
    }

    private fun workLine(c: Context, w: Work, farm: FarmData, ops: OpsData): List<String> = listOf(
        OdFmt.shortDate(c, w.endDate ?: w.date),
        w.block.ifBlank { farm.parcels.firstOrNull { it.id == w.parcelId }?.name ?: c.getString(R.string.ops_whole_farm) },
        "${w.workType.emoji} " + w.title.ifBlank { Labels.work(c, w.workType) },
        w.inputs.mapNotNull { u -> ops.product(u.productId)?.let { "${it.name} ${qtyText(c, u.qty, it.unit)}" } }.joinToString(", "),
        listOfNotNull(w.workers?.let { c.getString(R.string.report_workers, it) }, w.responsible.takeIf { it.isNotBlank() }).joinToString(" · "),
    )

    fun title(c: Context, r: WeekReport): String {
        val week = LocalDate.ofEpochDay(r.from).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        return c.getString(R.string.report_title_week, week, OdFmt.shortDate(c, r.from), OdFmt.date(c, r.to))
    }

    /** Short text for the team's WhatsApp group. */
    fun text(c: Context, farm: FarmState, ops: OpsData, r: WeekReport): String = buildString {
        appendLine("*ODEMA — ${title(c, r)}*")
        val rain = r.rainMm.mapNotNull { (n, mm) -> mm?.let { n to it } }
        if (rain.isNotEmpty()) appendLine("🌧️ " + rain.joinToString(", ") { (n, mm) -> "$n ${OdFmt.mm(c, mm)}" })
        appendLine()
        appendLine("✅ " + c.getString(R.string.report_works_done) + " (${r.worksDone.size})")
        r.worksDone.forEach { appendLine("• " + workLine(c, it, farm.data, ops).filter { s -> s.isNotBlank() }.joinToString(" · ")) }
        if (r.worksOverdue.isNotEmpty()) {
            appendLine(); appendLine("⏰ " + c.getString(R.string.report_works_late) + " (${r.worksOverdue.size})")
            r.worksOverdue.forEach { appendLine("• " + workLine(c, it, farm.data, ops).take(3).joinToString(" · ")) }
        }
        if (r.worksNext.isNotEmpty()) {
            appendLine(); appendLine("📅 " + c.getString(R.string.report_works_next) + " (${r.worksNext.size})")
            r.worksNext.forEach { appendLine("• " + workLine(c, it, farm.data, ops).take(3).joinToString(" · ")) }
        }
        appendLine(); appendLine("⚠️ " + c.getString(R.string.report_incidents_open) + " (${r.incidentsStillOpen.size})")
        r.incidentsStillOpen.forEach { i -> appendLine("• " + listOfNotNull(if (i.urgent) "🔴" else null, i.title, ops.asset(i.assetId)?.name).joinToString(" · ")) }
        if (r.incidentsResolved.isNotEmpty()) appendLine("✔️ " + c.getString(R.string.report_incidents_resolved) + " : " + r.incidentsResolved.joinToString(", ") { it.title })
        if (r.lowStock.isNotEmpty()) {
            appendLine(); appendLine("📦 " + c.getString(R.string.report_low_stock) + " : " + r.lowStock.joinToString(", ") { (p, q) -> "${p.name} ${qtyText(c, q, p.unit)}" })
        }
        if (r.labour.sheets > 0) {
            appendLine(); appendLine("👷 " + c.getString(R.string.report_labour_line, r.labour.daysByStaff.values.sum(), r.labour.casualDays, madText(c, r.labour.cost)))
        }
    }.trim()

    /** Writes the PDF in the shared cache folder and returns it. */
    fun pdf(c: Context, farm: FarmState, ops: OpsData, r: WeekReport, author: String): File {
        val w = PdfWriter(c)
        w.header(title(c, r), c.getString(R.string.report_by, author.ifBlank { "—" }, OdFmt.date(c, LocalDate.now().toEpochDay())))

        w.heading(c.getString(R.string.report_summary))
        w.para(c.getString(R.string.report_summary_line, r.worksDone.size, r.worksOverdue.size, r.worksNext.size, r.incidentsStillOpen.size, r.incidentsStillOpen.count { it.urgent }))

        val rain = r.rainMm.filter { it.second != null }
        if (rain.isNotEmpty()) {
            w.heading(c.getString(R.string.report_rain))
            w.table(
                listOf(c.getString(R.string.ops_parcel), c.getString(R.string.report_rain_week), c.getString(R.string.report_rain_campaign)),
                rain.map { (n, mm) -> listOf(n, OdFmt.mm(c, mm), OdFmt.mm(c, campaignRain(farm, n, r.to))) },
                floatArrayOf(2f, 1f, 1f),
            )
        }
        val workHeaders = listOf(c.getString(R.string.works_date), c.getString(R.string.ops_block), c.getString(R.string.works_type), c.getString(R.string.works_products), c.getString(R.string.report_team))
        val workWidths = floatArrayOf(0.8f, 1.1f, 1.6f, 1.8f, 1.2f)
        w.heading(c.getString(R.string.report_works_done) + " (${r.worksDone.size})")
        if (r.worksDone.isEmpty()) w.para("—") else w.table(workHeaders, r.worksDone.map { workLine(c, it, farm.data, ops) }, workWidths)
        if (r.worksOverdue.isNotEmpty()) {
            w.heading(c.getString(R.string.report_works_late) + " (${r.worksOverdue.size})")
            w.table(workHeaders, r.worksOverdue.map { workLine(c, it, farm.data, ops) }, workWidths)
        }
        w.heading(c.getString(R.string.report_works_next) + " (${r.worksNext.size})")
        if (r.worksNext.isEmpty()) w.para("—") else w.table(workHeaders, r.worksNext.map { workLine(c, it, farm.data, ops) }, workWidths)

        w.heading(c.getString(R.string.report_incidents))
        val incidents = (r.incidentsStillOpen + r.incidentsResolved).distinctBy { it.id }
        if (incidents.isEmpty()) w.para("—") else w.table(
            listOf(c.getString(R.string.inc_what), c.getString(R.string.inc_asset), c.getString(R.string.inc_status), c.getString(R.string.inc_reported_short)),
            incidents.map { i ->
                listOf((if (i.urgent && i.open) "🔴 " else "") + i.title + (i.resolution.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                    ops.asset(i.assetId)?.name.orEmpty(), Labels.incident(c, i.incidentStatus), listOf(i.reportedBy, OdFmt.shortDate(c, i.reportedAt / 86_400_000L)).filter { it.isNotBlank() }.joinToString(" · "))
            },
            floatArrayOf(2.4f, 1.1f, 0.9f, 1.2f),
        )

        if (r.moves.isNotEmpty() || r.lowStock.isNotEmpty()) {
            w.heading(c.getString(R.string.report_stock))
            if (r.moves.isNotEmpty()) w.table(
                listOf(c.getString(R.string.works_date), c.getString(R.string.stock_product), c.getString(R.string.stock_qty), c.getString(R.string.report_for)),
                r.moves.map { m ->
                    val p = ops.product(m.productId)
                    listOf(OdFmt.shortDate(c, m.date), p?.name.orEmpty(), (if (m.qty > 0) "+" else "") + qtyText(c, m.qty, p?.unit.orEmpty()),
                        listOfNotNull(farm.data.parcels.firstOrNull { it.id == m.parcelId }?.name, m.partner.takeIf { it.isNotBlank() }).joinToString(" · "))
                },
                floatArrayOf(0.8f, 1.8f, 1f, 1.6f),
            )
            if (r.lowStock.isNotEmpty()) w.para("⚠️ " + c.getString(R.string.report_low_stock) + " : " + r.lowStock.joinToString(", ") { (p, q) -> "${p.name} ${qtyText(c, q, p.unit)}" })
        }
        if (r.harvests.isNotEmpty()) {
            w.heading(c.getString(R.string.harvest_title))
            w.table(
                listOf(c.getString(R.string.works_date), c.getString(R.string.ops_block), c.getString(R.string.harvest_qty), c.getString(R.string.harvest_buyer)),
                r.harvests.map { h -> listOf(OdFmt.shortDate(c, h.date), h.block.ifBlank { farm.data.parcels.firstOrNull { it.id == h.parcelId }?.name.orEmpty() }, qtyText(c, h.quantityT, "t"), h.buyer) },
                floatArrayOf(0.8f, 1.4f, 1f, 1.6f),
            )
        }
        if (r.labour.sheets > 0) {
            w.heading(c.getString(R.string.report_labour))
            w.para(c.getString(R.string.report_labour_line, r.labour.daysByStaff.values.sum(), r.labour.casualDays, madText(c, r.labour.cost)))
        }
        val dir = File(c.cacheDir, "shared").apply { mkdirs() }
        val week = LocalDate.ofEpochDay(r.from).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val file = File(dir, "ODEMA-CR-${LocalDate.ofEpochDay(r.from).year}-S$week.pdf")
        w.finish(file)
        return file
    }

    fun share(c: Context, file: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareText(c: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        c.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Minimal A4 writer: headings, paragraphs and wrapped tables, with automatic page breaks. */
private class PdfWriter(private val c: Context) {
    private val doc = PdfDocument()
    private val pageW = 595
    private val pageH = 842
    private val margin = 36f
    private val green = Color.rgb(59, 107, 5)
    private var page: PdfDocument.Page? = null
    private var y = 0f
    private var pageNo = 0

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun layout(text: String, p: TextPaint, width: Float) =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt().coerceAtLeast(10)).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

    private fun newPage() {
        page?.let { doc.finishPage(it) }
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        y = margin
        val canvas = page!!.canvas
        val foot = paint(8f, color = Color.GRAY)
        canvas.drawText("ODEMA · $pageNo", pageW - margin - foot.measureText("ODEMA · $pageNo"), pageH - 18f, foot)
    }

    private fun ensure(h: Float) {
        if (page == null || y + h > pageH - margin) newPage()
    }

    fun header(title: String, sub: String) {
        ensure(80f)
        val canvas = page!!.canvas
        ContextCompat.getDrawable(c, R.drawable.ic_launcher_foreground)?.let { d ->
            d.setBounds(margin.toInt() - 14, y.toInt() - 14, margin.toInt() + 66, y.toInt() + 66)
            d.draw(canvas)
        }
        canvas.drawText("ODEMA", margin + 64, y + 26, paint(22f, true, green))
        val t = layout(title, paint(13f, true), pageW - 2 * margin - 64)
        canvas.save(); canvas.translate(margin + 64, y + 32); t.draw(canvas); canvas.restore()
        canvas.drawText(sub, margin + 64, y + 36 + t.height + 10, paint(9f, color = Color.DKGRAY))
        y += 72f + t.height
        canvas.drawLine(margin, y, pageW - margin, y, Paint().apply { color = green; strokeWidth = 1.5f })
        y += 10f
    }

    fun heading(text: String) {
        ensure(40f)
        y += 8f
        page!!.canvas.drawText(text, margin, y + 13, paint(12.5f, true, green))
        y += 20f
    }

    fun para(text: String) {
        val l = layout(text, paint(10f), pageW - 2 * margin)
        ensure(l.height + 6f)
        page!!.canvas.apply { save(); translate(margin, y); l.draw(this); restore() }
        y += l.height + 6f
    }

    fun table(headers: List<String>, rows: List<List<String>>, weights: FloatArray) {
        val total = weights.sum()
        val widths = weights.map { (pageW - 2 * margin) * it / total }
        val pad = 3f
        val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.6f }
        fun row(cells: List<String>, bold: Boolean, shade: Boolean) {
            val p = paint(8.6f, bold)
            val layouts = cells.mapIndexed { i, s -> layout(s, p, widths[i] - 2 * pad) }
            val h = (layouts.maxOfOrNull { it.height } ?: 10) + 2 * pad
            ensure(h)
            val canvas = page!!.canvas
            if (shade) canvas.drawRect(margin, y, pageW - margin, y + h, Paint().apply { color = Color.rgb(237, 244, 230) })
            var x = margin
            layouts.forEachIndexed { i, l ->
                canvas.save(); canvas.translate(x + pad, y + pad); l.draw(canvas); canvas.restore()
                x += widths[i]
            }
            y += h
            canvas.drawLine(margin, y, pageW - margin, y, line)
        }
        row(headers, bold = true, shade = true)
        rows.forEach { row(it, bold = false, shade = false) }
        y += 6f
    }

    fun finish(file: File) {
        if (page == null) newPage()
        doc.finishPage(page!!)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
