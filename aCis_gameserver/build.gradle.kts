import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.api.file.DuplicatesStrategy
import java.time.Instant

// 1. Plugins
plugins {
    id("java-library")
    kotlin("jvm") version "2.1.0"
}

// 2. Repositórios
repositories {
    mavenCentral()
    mavenLocal()
}

// 3. Configuração de Fontes (MODERNO)
sourceSets {
    main {
        // Define onde está o Java
        java.srcDirs("java")
    }
}

// Configurações do Kotlin (Toolchain e SourceSets)
kotlin {
    jvmToolchain(25) // Java 25

    // Define onde está o Kotlin (Sem usar withConvention)
    sourceSets {
        main {
            kotlin.srcDirs("kotlin")
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// 4. Compilação (A Mágica acontece aqui)

// Configura o Kotlin
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xno-call-assertions", "-Xno-param-assertions", "-java-parameters", "-Xjvm-default=all")
    }
}

// Configura o Java
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    
    // O Java TEM que esperar o Kotlin
    dependsOn("compileKotlin")
    
    // CORREÇÃO CRÍTICA: Pega a pasta de saída do Kotlin direto da tarefa
    // Isso resolve o erro 'classesDirectory' e 'withConvention'
    val kotlinOutput = tasks.getByName<KotlinCompile>("compileKotlin").destinationDirectory
    classpath += files(kotlinOutput)
}

// 5. Dependências
dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.9.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.4.0")
    
    // Libs locais
    implementation(fileTree("libs") { 
        include("*.jar") 
        exclude("server.jar")
    })
}

// 6. Configuração do JAR
tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE 
    
    manifest {
        attributes(
            "Main-Class" to "net.sf.l2j.gameserver.GameServer", 
            "Build-Date" to Instant.now().toString()
        )
    }
    
    // Inclui classes compiladas (Java + Kotlin)
    from(sourceSets.main.get().output)
    
    // Fat Jar (Libs dentro do jar)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")

    archiveBaseName.set("server")
    destinationDirectory.set(file("build/libs")) 
}

// 7. Deploy Automático
tasks.register("deployToVerge") {
    group = "distribution"
    dependsOn(tasks.jar)
    doLast {
        val jarFile = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val destinations = listOf(
            file("C:/Users/Victor Hugo/Desktop/Verge/gameserver/libs"),
            file("C:/Users/Victor Hugo/Desktop/Verge/login/libs")
        )
        if (jarFile.exists()) {
            println(">>> DEPLOY SUCESSO: ${jarFile.name} (${jarFile.length() / 1024} KB) <<<")
            destinations.forEach { 
                if (!it.exists()) it.mkdirs()
                copy { from(jarFile); into(it) }
                println(" [OK] -> $it")
            }
        }
    }
}

tasks.build { finalizedBy("deployToVerge") }