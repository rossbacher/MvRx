package com.airbnb.mvrx.minified

import androidx.fragment.app.FragmentActivity
import com.airbnb.mvrx.BaseTest
import com.android.tools.r8.ClassFileConsumer
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.origin.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.robolectric.Robolectric
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Shrinks Mavericks and the fixtures in [MinifiedHarness] with R8 in full mode, then runs the reflective Mavericks paths from the
 * shrunk classes. This checks the consumer keep rules the way an app's release build applies them.
 */
class MinifiedConsumerRulesTest : BaseTest() {

    private val consumerRules = File(System.getProperty("mavericks.consumerRules")!!)
    private val persistStateRules = File(System.getProperty("mavericks.persistStateConsumerRules")!!)

    @Test
    fun publishedRulesKeepEveryReflectivePath() {
        val results = runShrunk(consumerRules.readText(), persistStateRules.readText())

        assertEquals(EXPECTED, results)
    }

    @Test
    fun persistStateNeedsItsRules() {
        val results = runShrunk(consumerRules.readText())

        assertTrue(results.getValue("persist"), results.getValue("persist").startsWith("error: "))
        assertEquals(EXPECTED - "persist", results - "persist")
    }

    @Test
    fun persistStateRulesCanBeScopedToPersistedStates() {
        val narrowedRules = """
            -keepclassmembers class com.airbnb.mvrx.minified.PersistedState {
                synthetic *** copy${'$'}default(...);
                public *** component1();
                public *** component3();
            }
        """.trimIndent()
        val output = shrink(consumerRules.readText(), narrowedRules)

        assertEquals(EXPECTED, run(output))
        val plainStateMethods = methodNames(output, "com.airbnb.mvrx.minified.PlainState")
        assertFalse(plainStateMethods.toString(), plainStateMethods.any { it.startsWith("component") || it.startsWith("copy") })
    }

    @Test
    fun companionFactoryAndPersistStateNeedTheirAttributes() {
        val withoutAttributes = consumerRules.readLines().filterNot { it.startsWith("-keepattributes") }.joinToString("\n")

        val results = runShrunk(withoutAttributes, persistStateRules.readText())

        // Without the parameter annotations PersistStateKt finds nothing to persist, so the restored state keeps its defaults.
        assertEquals("PersistedState(count=0, label=initial, query=)", results["persist"])
        // Without InnerClasses the companion factory is not found, so its initialState() and create() are not used.
        assertTrue(results.getValue("factoryViewModel"), results["factoryViewModel"] != EXPECTED["factoryViewModel"])
    }

    private fun runShrunk(vararg rules: String): Map<String, String> = run(shrink(*rules))

    private fun run(output: Path): Map<String, String> {
        @Suppress("DEPRECATION")
        val activity = Robolectric.setupActivity(FragmentActivity::class.java)
        ChildFirstClassLoader(output, javaClass.classLoader!!).use { loader ->
            val harness = loader.loadClass(MinifiedHarness::class.java.name)
            @Suppress("UNCHECKED_CAST")
            return harness.getMethod("run", Any::class.java).invoke(null, activity) as Map<String, String>
        }
    }

    /** Shrinks the Mavericks classes and the fixtures with [rules] and returns the jar of shrunk classes. */
    private fun shrink(vararg rules: String): Path {
        val mavericksClasses = classesRoot("com/airbnb/mvrx/PersistState.class")
        val fixtureClasses = classesRoot("com/airbnb/mvrx/minified/MinifiedHarness.class")
        val program = Files.createTempFile("mavericks-program", ".jar")
        ZipOutputStream(Files.newOutputStream(program)).use { zip ->
            addClasses(zip, mavericksClasses) { !it.startsWith("com/airbnb/mvrx/minified/") }
            addClasses(zip, fixtureClasses) { it.startsWith("com/airbnb/mvrx/minified/") && !it.contains("Test") }
        }
        // The test classpath holds some classes twice (from different transforms), which R8 rejects, so merge it first-wins.
        val classpath = Files.createTempFile("mavericks-classpath", ".jar")
        ZipOutputStream(Files.newOutputStream(classpath)).use { zip ->
            val seen = HashSet<String>()
            System.getProperty("java.class.path").split(File.pathSeparator)
                .map(::File)
                .filter { it.exists() && it != mavericksClasses && it != fixtureClasses }
                .forEach { entry -> addClasses(zip, entry) { seen.add(it) } }
        }
        val output = Files.createTempFile("mavericks-shrunk", ".jar")
        val command = R8Command.builder()
            .addProgramFiles(program)
            .addLibraryFiles(System.getProperty("mavericks.androidJar")!!.split(File.pathSeparator).map { File(it).toPath() })
            .addClasspathFiles(classpath)
            .addProguardConfiguration(
                listOf(
                    "-keep class com.airbnb.mvrx.minified.MinifiedHarness { public static *** run(java.lang.Object); }",
                    "-ignorewarnings",
                ) + rules.toList(),
                Origin.unknown(),
            )
            .setMode(CompilationMode.RELEASE)
            .setProgramConsumer(ClassFileConsumer.ArchiveConsumer(output))
            .build()
        R8.run(command)
        return output
    }

    /** Returns the directory or jar on the test classpath that contains [classFile]. */
    private fun classesRoot(classFile: String): File {
        val url = javaClass.classLoader!!.getResource(classFile)!!
        return when (url.protocol) {
            "jar" -> File(URL(url.path.substringBefore("!/")).toURI())
            else -> File(File(url.toURI()).path.removeSuffix(classFile))
        }
    }

    private fun addClasses(zip: ZipOutputStream, root: File, include: (String) -> Boolean) {
        fun add(name: String, bytes: () -> ByteArray) {
            if (name.endsWith(".class") && include(name)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes())
                zip.closeEntry()
            }
        }
        if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile }.forEach { file -> add(file.relativeTo(root).invariantSeparatorsPath) { file.readBytes() } }
        } else {
            ZipFile(root).use { jar ->
                jar.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    add(entry.name) { jar.getInputStream(entry).use { it.readBytes() } }
                }
            }
        }
    }

    private fun methodNames(jar: Path, className: String): List<String> {
        ZipFile(jar.toFile()).use { zip ->
            val entry = zip.getEntry(className.replace('.', '/') + ".class") ?: return emptyList()
            val names = mutableListOf<String>()
            ClassReader(zip.getInputStream(entry).readBytes()).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        desc: String?,
                        signature: String?,
                        exceptions: Array<out String>?
                    ): MethodVisitor? {
                        names += name
                        return null
                    }
                },
                ClassReader.SKIP_CODE,
            )
            return names
        }
    }

    /** Loads the shrunk classes before the unshrunk copies on the test classpath; everything else comes from [parent]. */
    private class ChildFirstClassLoader(jar: Path, parent: ClassLoader) : URLClassLoader(arrayOf<URL>(jar.toUri().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            findLoadedClass(name)
                ?: (if (findResource(name.replace('.', '/') + ".class") != null) findClass(name) else null)
                ?: super.loadClass(name, resolve)
        }
    }

    private companion object {
        val EXPECTED = mapOf(
            "persist" to "PersistedState(count=7, label=initial, query=beach)",
            "persistNothing" to "0",
            "defaultViewModel" to "PlainState(count=0, label=initial)",
            "argsViewModel" to "ArgsState(id=args:42)",
            "factoryViewModel" to "factory PlainState(count=0, label=factory initial state)",
            "jvmStaticFactoryViewModel" to "jvm static factory PlainState(count=0, label=jvm static initial state)",
        )
    }
}
