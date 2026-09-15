package com.tasklane.bench

import com.tasklane.data.store.TasksCodec
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.text.ImageRefParser
import com.intellij.openapi.util.JDOMUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * El generador del banco, comprobado contra la especificación que dice cumplir.
 *
 * No es ceremonia: si el corpus no lleva los diez enlaces o las cinco anclas, todas
 * las cifras de la Fase 0 miden otra cosa y las puertas de las seis fases siguientes
 * se cierran sobre un número inventado. Este test es lo que hace que el banco valga
 * algo.
 */
class SyntheticCorpusTest {

    @Test
    fun `una tarea cumple la especificacion del plan`() {
        val task = SyntheticCorpus.task(0)

        assertEquals("diez enlaces", SyntheticCorpus.LINKS, task.links.size)
        assertEquals("diez imagenes", SyntheticCorpus.IMAGES, task.attachments.size)
        assertEquals("cinco anclas", SyntheticCorpus.ANCHORS, task.anchors.size)
        assertEquals("ocho etiquetas", SyntheticCorpus.TAGS, task.tags.size)

        // El §1.1 estima ~2.000 caracteres de cuerpo. Se admite un margen porque el
        // texto se compone por frases enteras: cortar a 500 exactos daría palabras
        // partidas, que es justo lo que LinkExtractor y el envoltorio del título no
        // se encuentran nunca en la vida real.
        assertTrue(
            "el cuerpo mide ~2.000 caracteres, y midio ${task.body.length}",
            task.body.length in 1_600..2_600,
        )
        // Las diez referencias son 780 caracteres de los 2.000.
        assertEquals(780, task.body.length - ImageRefParser.strip(task.body).length)

        assertTrue("el titulo dice algo", task.title.isNotBlank())
        assertTrue("no hay fechas en el futuro", !task.updatedAt.isAfter(SyntheticCorpus.REFERENCE_NOW))
        assertTrue("actualizada despues de creada", !task.updatedAt.isBefore(task.createdAt))
    }

    /**
     * Lo que hace comparable a dos ejecuciones del banco. Se comprueba además que la
     * tarea número 7 es la misma pidiéndola suelta que sacándola de un corpus de mil:
     * ése es el punto de derivar la semilla del índice y no arrastrarla.
     */
    @Test
    fun `el corpus es reproducible y no depende del tamano`() {
        assertEquals(SyntheticCorpus.task(7).body, SyntheticCorpus.task(7).body)
        // `blobPool` SÍ forma parte de la identidad del corpus —es de cuántos blobs
        // distintos tira—, así que se pasa el mismo para comparar lo comparable. Lo
        // que no debe depender del tamaño es todo lo demás: pedir la tarea 7 suelta y
        // sacarla de un corpus de mil tiene que dar la misma tarea.
        assertEquals(SyntheticCorpus.task(7, blobPool = 1_000).body, SyntheticCorpus.tasks(1_000)[7].body)
        assertEquals(SyntheticCorpus.tasks(10).map { it.id }, SyntheticCorpus.tasks(1_000).take(10).map { it.id })
        assertEquals(
            "y el texto tampoco, quitando las referencias",
            ImageRefParser.strip(SyntheticCorpus.task(7).body),
            ImageRefParser.strip(SyntheticCorpus.tasks(1_000)[7].body),
        )
    }

    /**
     * La perilla de deduplicación del §0.2, en sus dos extremos. Con `blobPool` igual
     * al número de tareas, diez mil referencias apuntan a mil blobs: 10:1, que es el
     * escenario con el que el §1.6 baja los 4 TB a 400 GB.
     */
    @Test
    fun `blobPool controla la deduplicacion`() {
        val sinDedup = SyntheticCorpus.tasks(100, blobPool = 100 * SyntheticCorpus.IMAGES)
        val conDedup = SyntheticCorpus.tasks(100, blobPool = 100)

        val distintosSin = sinDedup.flatMap { it.attachments }.map { it.id }.distinct().size
        val distintosCon = conDedup.flatMap { it.attachments }.map { it.id }.distinct().size

        assertEquals("con dedup 10:1 no puede haber mas blobs que tareas", 100, distintosCon)
        assertTrue(
            "sin dedup tiene que haber muchos mas blobs distintos ($distintosSin vs $distintosCon)",
            distintosSin > distintosCon * 5,
        )
    }

    /**
     * El XML que escribe el generador tiene que ser **el mismo** que lee el plugin. Si
     * no, el banco mediría la carga de un fichero que nadie escribe.
     */
    @Test
    fun `el XML generado lo lee TasksCodec sin perder nada`() {
        val dir = Files.createTempDirectory("tasklane-corpus")
        try {
            val file = dir.resolve("tasks.xml")
            SyntheticCorpus.writeTasksXml(file, count = 50)

            val decoded = TasksCodec.decode(JDOMUtil.load(file), RepoKey.ROOT)
            assertEquals(1, decoded.version)
            assertEquals(50, decoded.tasks.size)

            val original = SyntheticCorpus.tasks(50)
            for ((esperada, leida) in original.zip(decoded.tasks)) {
                assertEquals(esperada.id, leida.id)
                assertEquals("el cuerpo sobrevive al escapado XML", esperada.body, leida.body)
                assertEquals(esperada.tags, leida.tags)
                assertEquals(esperada.anchors, leida.anchors)
                assertEquals(esperada.stateId, leida.stateId)
                assertEquals(esperada.priorityId, leida.priorityId)
                assertEquals(esperada.order, leida.order)
                assertEquals(esperada.createdAt, leida.createdAt)
                assertEquals(esperada.updatedAt, leida.updatedAt)
                assertEquals(esperada.completedAt, leida.completedAt)
                assertEquals(esperada.dueDate, leida.dueDate)
                assertEquals(esperada.bookmarked, leida.bookmarked)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * La estimación de 2,9 KB por tarea del §1.1, contra la báscula. Es la cifra de la
     * que salen los 2,9 GB del fichero de un millón, así que conviene que sea cierta.
     */
    @Test
    fun `el fichero pesa lo que el plan estima`() {
        val dir = Files.createTempDirectory("tasklane-corpus")
        try {
            val file = dir.resolve("tasks.xml")
            val bytes = SyntheticCorpus.writeTasksXml(file, count = 1_000)
            val perTask = bytes / 1_000.0

            println("Corpus de 1.000 tareas: ${bytes / 1024} KB  ->  %.0f B por tarea".format(perTask))
            assertTrue(
                "el §1.1 estima ~2.900 B por tarea, y midio %.0f".format(perTask),
                perTask in 2_000.0..4_000.0,
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Los blobs del §0.2 tienen que pesar como una captura. El §1.6 razona sobre
     * ~400 KB a 1600 px; a los 400 px que el usuario fijó para la Fase 4, lo que
     * importa es que la proporción se mantenga —el área baja 16 veces— porque de ahí
     * sale el presupuesto de disco.
     */
    @Test
    fun `un blob sintetico pesa como una captura`() {
        val small = SyntheticBlobs.png(0, SyntheticBlobs.DEFAULT_SIZE).size
        val legacy = SyntheticBlobs.png(0, SyntheticBlobs.LEGACY_SIZE).size

        println("PNG sintetico: ${SyntheticBlobs.DEFAULT_SIZE}px -> ${small / 1024} KB, " +
            "${SyntheticBlobs.LEGACY_SIZE}px -> ${legacy / 1024} KB")

        // El §1.6 razona con ~400 KB por captura a 1600 px. El margen es ancho a
        // propósito: lo que no puede pasar es que el blob sea un color plano de 5 KB
        // ni ruido incompresible de 7 MB, que son los dos errores que medirían un
        // disco que nadie tiene.
        assertTrue("a 1600 px una captura ronda los 400 KB, y midio $legacy B", legacy in 250_000..700_000)
        // El área baja 16 veces al pasar de 1600 a 400 px, y con ella el peso. Es de
        // donde sale que el tope de la Fase 4 divide el problema del disco por ~12.
        assertTrue("a 400 px tiene que ser mucho mas ligera, y midio $small B", small < legacy / 8)
        assertTrue("pero no un color plano, y midio $small B", small > 10_000)
    }

    /** El ID es un SHA-256 de verdad y es estable: el corpus lo referencia por él. */
    @Test
    fun `los identificadores de blob son estables y bien formados`() {
        assertEquals(SyntheticBlobs.id(42), SyntheticBlobs.id(42))
        assertTrue(SyntheticBlobs.id(42) != SyntheticBlobs.id(43))
        assertTrue(SyntheticBlobs.id(42).value.matches(Regex("[0-9a-f]{64}")))
        // Y el parser de producción lo acepta, que es lo único que de verdad importa.
        assertEquals(
            listOf(SyntheticBlobs.id(42)),
            ImageRefParser.ids(ImageRefParser.reference(SyntheticBlobs.id(42))).toList(),
        )
    }
}
