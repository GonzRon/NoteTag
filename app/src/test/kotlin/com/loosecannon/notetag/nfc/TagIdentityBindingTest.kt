package com.loosecannon.notetag.nfc

import com.loosecannon.notetag.BuildConfig
import com.loosecannon.nfc.tagcore.TagIdentity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The C9 binding, JVM half (target §4.8 test 1). One set of Gradle vals produces the manifest
 * placeholder and the three `BuildConfig` fields, so this test is what fails if anyone puts a
 * second copy of this product's identity anywhere — including in the manifest, as a literal.
 *
 * It reads the manifest and the build script as *text* on purpose: the point is what the sources
 * say, not what the merger happened to produce. The *installed, merged* manifest is proved by
 * `TagIdentityDispatchTest` on the emulator, which no string comparison can do.
 *
 * Gradle runs JVM unit tests with the module directory as the working directory; an IDE run
 * configuration may use the repository root, so both are tried.
 */
class TagIdentityBindingTest {

    private val identity = TagIdentity(
        externalDomain = BuildConfig.NDEF_EXTERNAL_DOMAIN,
        typeName = BuildConfig.NDEF_TYPE_NAME,
        aarPackage = BuildConfig.NDEF_AAR_PACKAGE,
    )

    private fun moduleFile(relative: String): File =
        listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("cannot find $relative from ${File(".").absolutePath}")

    private val manifest: String by lazy { moduleFile("src/main/AndroidManifest.xml").readText() }
    private val buildScript: String by lazy { moduleFile("build.gradle.kts").readText() }

    /**
     * The identity is this app's own applicationId, and NoteTag writes no Application Record
     * (O13): an AAR would pin a package into somebody else's bytes for no gain on a tag whose
     * external type already says whose it is.
     */
    @Test fun theIdentityIsThisApplicationIdAndCarriesNoApplicationRecord() {
        assertEquals("com.loosecannon.notetag:tag", identity.externalType)
        assertEquals(identity.externalType.lowercase(), identity.externalType)
        assertEquals(BuildConfig.APPLICATION_ID, BuildConfig.NDEF_EXTERNAL_DOMAIN)
        assertNull("NoteTag writes no AAR", BuildConfig.NDEF_AAR_PACKAGE)
        assertNull("NoteTag writes no AAR", identity.aarPackage)
    }

    /** The manifest must carry no identity literal at all — only the placeholder. */
    @Test fun theManifestFilterPathIsThePlaceholder() {
        // Plain strings with escapes, not raw strings: a raw string that ends in a quote runs
        // straight into its own terminator and is a trap for the next reader.
        assertTrue(
            "the NDEF filter path must be \${ndefTagPath}, never a literal",
            manifest.contains("android:path=\"\${ndefTagPath}\""),
        )
        assertEquals(
            "no identity literal may survive in the manifest", 0,
            Regex(Regex.escape(identity.externalType)).findAll(manifest).count(),
        )
    }

    /**
     * "No second copy anywhere, including the manifest" includes the component names: a
     * fully-qualified `android:name` is the package root written out a second time. Every one of
     * them is namespace-relative, so the merger derives it from the single Gradle value.
     */
    @Test fun theManifestNamesNoComponentByItsPackageRoot() {
        val fullyQualified = Regex("android:name=\"([^\"]*)\"").findAll(manifest)
            .map { it.groupValues[1] }
            .filter { it.startsWith(BuildConfig.APPLICATION_ID) }
            .toList()
        assertEquals(
            "no android:name may repeat the package root; use the namespace-relative form",
            emptyList<String>(), fullyQualified,
        )
    }

    /** Exactly one NDEF-exported filter, and exactly one place that defines the placeholder. */
    @Test fun oneFilterAndOneDefinition() {
        assertEquals(
            "one NDEF_DISCOVERED filter", 1,
            Regex("android.nfc.action.NDEF_DISCOVERED").findAll(manifest).count(),
        )
        assertEquals(
            "one definition of ndefTagPath", 1,
            Regex("""manifestPlaceholders\["ndefTagPath"]""").findAll(buildScript).count(),
        )
        assertTrue(
            "the placeholder is built from the identity vals, not from a literal",
            buildScript.contains("manifestPlaceholders[\"ndefTagPath\"] = \"/\$tagExternalDomain:\$tagTypeName\""),
        )
    }

    /**
     * P4: NoteTag has no custom URL scheme. The tag identity is the only way in from outside, so
     * there is nothing for a `notetag://` link to reach and nothing to claim it with.
     */
    @Test fun thereIsNoNoteTagUrlScheme() {
        assertEquals(
            "P4: no notetag:// scheme may be declared", 0,
            Regex(Regex.escape("android:scheme=\"notetag\"")).findAll(manifest).count(),
        )
    }

    /** The no-AAR decision is one typed Gradle val, so `BuildConfig`'s null is not a happy accident. */
    @Test fun theBuildScriptSaysTheAarIsNull() {
        assertTrue(
            "the AAR package must be a typed null in the build script",
            buildScript.contains("tagAarPackage: String? = null"),
        )
    }
}
