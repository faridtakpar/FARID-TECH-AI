
package com.faridtech.ai

import android.content.Context
import java.io.File

/**
 * Standard Android project template generator.
 *
 * Creates a conventional Android Gradle project structure.
 * This class does not execute Gradle or generate an APK.
 */
object AndroidProjectTemplate {

    private const val AGP_VERSION = "8.7.3"
    private const val KOTLIN_VERSION = "2.0.21"
    private const val GRADLE_VERSION = "8.9"

    fun create(
        context: Context,
        projectName: String,
        packageName: String,
        language: String
    ): File {

        require(projectName.isNotBlank()) {
            "نام پروژه نمی‌تواند خالی باشد."
        }

        require(
            Regex(
                "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"
            ).matches(packageName)
        ) {
            "شناسه بسته معتبر نیست."
        }

        require(language == "Kotlin" || language == "Java") {
            "زبان برنامه‌نویسی پشتیبانی نمی‌شود."
        }

        val safeName = projectName.trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")

        require(safeName.isNotBlank()) {
            "نام پروژه معتبر نیست."
        }

        val projectsDirectory = File(
            context.filesDir,
            "projects"
        )

        if (!projectsDirectory.exists()) {
            check(projectsDirectory.mkdirs()) {
                "ساخت پوشه پروژه‌ها ناموفق بود."
            }
        }

        val project = File(projectsDirectory, safeName)

        check(!project.exists()) {
            "پروژه‌ای با این نام از قبل وجود دارد."
        }

        check(project.mkdirs()) {
            "ساخت پوشه پروژه ناموفق بود."
        }

        try {
            createProjectFiles(
                project = project,
                projectName = safeName,
                packageName = packageName,
                language = language
            )

            return project

        } catch (exception: Exception) {

            project.deleteRecursively()

            throw IllegalStateException(
                "ساخت ساختار پروژه ناموفق بود: ${exception.message}",
                exception
            )
        }
    }

    private fun createProjectFiles(
        project: File,
        projectName: String,
        packageName: String,
        language: String
    ) {

        val sourceRoot = File(
            project,
            "app/src/main"
        )

        val packageDirectory = File(
            sourceRoot,
            "java/${packageName.replace(".", "/")}"
        )

        val valuesDirectory = File(
            sourceRoot,
            "res/values"
        )

        val wrapperDirectory = File(
            project,
            "gradle/wrapper"
        )

        listOf(
            packageDirectory,
            valuesDirectory,
            wrapperDirectory
        ).forEach { directory ->

            check(directory.mkdirs() || directory.isDirectory) {
                "ساخت پوشه ${directory.path} ناموفق بود."
            }
        }

        createSettingsFile(project, projectName)

        createRootGradleFile(project)

        createPropertiesFile(project)

        createWrapperProperties(wrapperDirectory)

        createAppGradleFile(
            project = project,
            packageName = packageName,
            language = language
        )

        createManifest(
            sourceRoot = sourceRoot,
            packageName = packageName
        )

        createStringsFile(
            valuesDirectory = valuesDirectory,
            projectName = projectName
        )

        createMainActivity(
            packageDirectory = packageDirectory,
            packageName = packageName,
            language = language
        )
    }

    private fun createSettingsFile(
        project: File,
        projectName: String
    ) {

        File(
            project,
            "settings.gradle.kts"
        ).writeText(
            """
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(
        RepositoriesMode.FAIL_ON_PROJECT_REPOS
    )

    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "$projectName"

include(":app")
            """.trimIndent()
        )
    }

    private fun createRootGradleFile(
        project: File
    ) {

        File(
            project,
            "build.gradle.kts"
        ).writeText(
            """
plugins {
    id("com.android.application") version "$AGP_VERSION" apply false
    id("org.jetbrains.kotlin.android") version "$KOTLIN_VERSION" apply false
}
            """.trimIndent()
        )
    }

    private fun createPropertiesFile(
        project: File
    ) {

        File(
            project,
            "gradle.properties"
        ).writeText(
            """
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8

android.useAndroidX=true

android.nonTransitiveRClass=true
            """.trimIndent()
        )
    }

    private fun createWrapperProperties(
        wrapperDirectory: File
    ) {

        File(
            wrapperDirectory,
            "gradle-wrapper.properties"
        ).writeText(
            """
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
            """.trimIndent()
        )
    }

    private fun createAppGradleFile(
        project: File,
        packageName: String,
        language: String
    ) {

        val kotlinPlugin = if (language == "Kotlin") {
            """
    id("org.jetbrains.kotlin.android")
            """.trimIndent()
        } else {
            ""
        }

        val kotlinOptions = if (language == "Kotlin") {
            """
    kotlinOptions {
        jvmTarget = "17"
    }
            """.trimIndent()
        } else {
            ""
        }

        File(
            project,
            "app/build.gradle.kts"
        ).apply {

            parentFile?.mkdirs()

            writeText(
                """
plugins {
    id("com.android.application")
$kotlinPlugin
}

android {
    namespace = "$packageName"

    compileSdk = 35

    defaultConfig {
        applicationId = "$packageName"

        minSdk = 26
        targetSdk = 35

        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

$kotlinOptions
}
                """.trimIndent()
            )
        }
    }

    private fun createManifest(
        sourceRoot: File,
        packageName: String
    ) {

        File(
            sourceRoot,
            "AndroidManifest.xml"
        ).writeText(
            """
<?xml version="1.0" encoding="utf-8"?>

<manifest
    xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name="$packageName.MainActivity"
            android:exported="true">

            <intent-filter>

                <action
                    android:name="android.intent.action.MAIN" />

                <category
                    android:name="android.intent.category.LAUNCHER" />

            </intent-filter>

        </activity>

    </application>

</manifest>
            """.trimIndent()
        )
    }

    private fun createStringsFile(
        valuesDirectory: File,
        projectName: String
    ) {

        File(
            valuesDirectory,
            "strings.xml"
        ).writeText(
            """
<?xml version="1.0" encoding="utf-8"?>

<resources>
    <string name="app_name">$projectName</string>
</resources>
            """.trimIndent()
        )
    }

    private fun createMainActivity(
        packageDirectory: File,
        packageName: String,
        language: String
    ) {

        if (language == "Kotlin") {

            File(
                packageDirectory,
                "MainActivity.kt"
            ).writeText(
                """
package $packageName

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setPadding(32, 32, 32, 32)
        }

        setContentView(title)
    }
}
                """.trimIndent()
            )

        } else {

            File(
                packageDirectory,
                "MainActivity.java"
            ).writeText(
                """
package $packageName;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView title = new TextView(this);

        title.setText(R.string.app_name);
        title.setTextSize(24);
        title.setPadding(32, 32, 32, 32);

        setContentView(title);
    }
}
                """.trimIndent()
            )
        }
    }
}
