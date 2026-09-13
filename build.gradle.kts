import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import kotlin.text.isNotBlank

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// El toolchain apunta al JBR que ya trae la IDEA instalada (ver
// org.gradle.java.installations.paths en gradle.properties), asi no hay que
// descargar ningun JDK. El bytecode se emite para 21, que es lo que ejecuta
// la plataforma 2025.2 -> el minimo que soportamos.
kotlin {
    jvmToolchain(25)
    compilerOptions {
        // 21 y no 25: es lo que ejecuta la plataforma 2025.2, nuestro minimo.
        // Bytecode 21 corre sin problema sobre el JBR 25 de un IDE 2026.x.
        jvmTarget = JvmTarget.JVM_21
    }
}

// NOTA sobre la version de Kotlin (settings.gradle.kts): debe ser >= la que usa
// el IDE contra el que se compila, porque el compilador tiene que poder LEER sus
// metadatos. IDEA 2026.2 trae metadatos 2.4.0, asi que 2.1.x fallaba con
// "Module was compiled with an incompatible version of Kotlin".
//
// Al reves no hay problema: un compilador nuevo lee metadatos viejos, asi que el
// mismo 2.4.20 sirve para el build de CI contra 2025.2.
//
// El stdlib NO se empaqueta (kotlin.stdlib.default.dependency=false), viene del
// IDE. Por eso el build de CI contra 2025.2 es el que detecta si usamos alguna
// API de stdlib mas nueva que la que trae el IDE minimo soportado.

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull?.takeIf(String::isNotBlank)
        if (localIde != null) {
            // Descarga cero: se compila contra el IDE ya instalado.
            local(localIde)
        } else {
            // Camino de CI y de release: se descarga la version MINIMA soportada,
            // para que el compilador rechace APIs posteriores a sinceBuild.
            create(
                providers.gradleProperty("platformType"),
                providers.gradleProperty("platformVersion"),
            )
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        id = providers.gradleProperty("pluginId")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Sin cota superior: sin esto IPGP fijaria until-build a la rama de
            // compilacion y el plugin dejaria de instalarse en versiones nuevas.
            untilBuild = provider { null }
        }
    }

    // Solo se resuelve al ejecutar `verifyPlugin`, que descarga IDEs completos.
    // Reservado para CI; en local no se invoca.
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }
}
