package com.tasklane.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.MonthGrid
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Elegir una fecha concreta de vencimiento, cuando ningún preajuste sirve.
 *
 * **Calendario propio y no el `DatePicker` de microba** que la plataforma empaqueta.
 * Se evaluó, que era lo que pedía el plan, y no compensa: microba no está en el
 * classpath de compilación de un plugin —hay que apuntar a un jar de dentro de la
 * instalación del IDE, cuyo nombre y sitio pueden cambiar de versión a versión— y
 * encima es Swing antiguo que no sigue el tema. Pintar seis filas de siete botones
 * cuesta menos que eso, sale con los colores del IDE, y la aritmética —que es donde
 * se falla— vive en [MonthGrid] y tiene tests.
 */
internal class DueDateDialog(
    project: Project,
    initial: LocalDate,
    private val firstDayOfWeek: DayOfWeek,
) : DialogWrapper(project) {

    private var month: YearMonth = YearMonth.from(initial)
    private var selected: LocalDate = initial
    private val today = LocalDate.now()

    private val title = JBLabel("", SwingConstants.CENTER).apply {
        font = UIUtil.getFont(UIUtil.FontSize.NORMAL, font).deriveFont(java.awt.Font.BOLD)
    }
    private val grid = JPanel(GridLayout(MonthGrid.WEEKS + 1, MonthGrid.DAYS, JBUI.scale(2), JBUI.scale(2)))

    /** La fecha elegida. Sólo tiene sentido si el diálogo se aceptó. */
    val date: LocalDate get() = selected

    init {
        setTitle(TasklaneBundle.message("dialog.due.title"))
        init()
        render()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(GAP))).apply {
        add(header(), BorderLayout.NORTH)
        add(grid, BorderLayout.CENTER)
    }

    private fun header(): JComponent = JPanel(BorderLayout()).apply {
        add(arrow(AllIcons.Actions.Play_back) { step(-1) }, BorderLayout.WEST)
        add(title, BorderLayout.CENTER)
        add(arrow(AllIcons.Actions.Play_forward) { step(1) }, BorderLayout.EAST)
    }

    private fun arrow(icon: javax.swing.Icon, run: () -> Unit) = JBLabel(icon).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(2, 6)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) run()
            }
        })
    }

    private fun step(months: Long) {
        month = month.plusMonths(months)
        render()
    }

    /**
     * Repinta la rejilla entera. Son 49 componentes ligeros y sólo cambia al pasar de
     * mes o elegir día, así que no hay nada que optimizar aquí.
     */
    private fun render() {
        title.text = month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) } + " " + month.year

        grid.removeAll()
        for (day in MonthGrid.headers(firstDayOfWeek)) {
            grid.add(
                JBLabel(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()), SwingConstants.CENTER).apply {
                    font = UIUtil.getFont(UIUtil.FontSize.SMALL, font)
                    foreground = UIUtil.getContextHelpForeground()
                },
            )
        }
        for (week in MonthGrid.weeks(month, firstDayOfWeek)) {
            week.forEach { grid.add(Day(it)) }
        }
        grid.revalidate()
        grid.repaint()
    }

    /** Un día. Los del mes vecino se pintan apagados pero se pueden elegir igual. */
    private inner class Day(private val date: LocalDate) : JBLabel(
        date.dayOfMonth.toString(),
        SwingConstants.CENTER,
    ) {
        private var hovered = false

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = JBUI.size(CELL, CELL)
            foreground = when {
                YearMonth.from(date) != month -> UIUtil.getContextHelpForeground()
                date == selected -> UIUtil.getListSelectionForeground(true)
                else -> UIUtil.getLabelForeground()
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }

                override fun mousePressed(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1) return
                    selected = date
                    month = YearMonth.from(date)
                    render()
                    // Doble clic elige y cierra: es el gesto de «esta y ya está».
                    if (e.clickCount >= 2) doOKAction()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                val arc = JBUI.scale(ARC)
                when {
                    date == selected -> {
                        g2.color = UIUtil.getListSelectionBackground(true)
                        g2.fillRoundRect(0, 0, width, height, arc, arc)
                    }

                    hovered -> {
                        g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                        g2.fillRoundRect(0, 0, width, height, arc, arc)
                    }

                    date == today -> {
                        g2.color = JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
                        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                    }
                }
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }

        override fun getMinimumSize(): Dimension = preferredSize
    }

    private companion object {
        const val GAP = 8
        const val CELL = 30
        const val ARC = 8
    }
}
