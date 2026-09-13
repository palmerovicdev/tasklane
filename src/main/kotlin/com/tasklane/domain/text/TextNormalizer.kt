package com.tasklane.domain.text

import java.text.Normalizer
import java.util.Locale

/**
 * Pasa un texto a la forma con la que se compara al buscar: minúsculas y sin
 * diacríticos, de modo que *autenticación* encuentre *autenticacion* y al revés.
 *
 * Se descompone con [Normalizer.Form.NFD] y se tiran las marcas combinantes. Es la
 * única forma de quitar el acento sin mantener una tabla a mano: `á` decompone en
 * `a` + U+0301, y basta con no copiar el segundo.
 *
 * La ruta ASCII no pasa por el normalizador. No es micro-optimización gratuita: el
 * criterio de la Fase 4 es buscar sobre 5.000 tareas sin lag, esto se ejecuta una
 * vez por tarea al cachear su documento, y [Normalizer.normalize] asigna memoria
 * incluso cuando no hay nada que descomponer.
 */
object TextNormalizer {

    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        if (isAscii(text)) return text.lowercase(Locale.ROOT)

        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            sb.append(ch)
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    private fun isAscii(text: String): Boolean {
        for (ch in text) if (ch.code >= 0x80) return false
        return true
    }
}
