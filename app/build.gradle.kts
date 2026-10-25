import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
}

android {
	namespace = "io.github.nathankewley.jocr"
	compileSdk = 37

	defaultConfig {
		applicationId = "io.github.nathankewley.jocr"
		// ImageDecoder: applies a camera photo's EXIF rotation and decodes HEIC from the gallery.
		minSdk = 28
		targetSdk = 37
		versionCode = 1
		versionName = "0.1.0"
		// ML Kit's OCR and translation libraries are ~28 MB per ABI. Phones are arm64; x86_64 is
		// for the emulator. The 32-bit ABIs would add almost 50 MB for devices that no longer exist.
		ndk {
			abiFilters += listOf("arm64-v8a", "x86_64")
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
		}
		debug {
			applicationIdSuffix = ".debug"
		}
	}
	buildFeatures {
		viewBinding = true
		buildConfig = false
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
	packaging {
		resources {
			// Kuromoji's core and dictionary jars ship identical copies of these; keep one, including
			// the licence text.
			pickFirsts += listOf("META-INF/LICENSE.md", "META-INF/CONTRIBUTORS.md")
		}
	}
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_17
	}
}

dependencies {
	implementation(libs.androidx.activity)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.collection)
	implementation(libs.androidx.core)
	implementation(libs.androidx.lifecycle.runtime)
	implementation(libs.androidx.lifecycle.viewmodel)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.recyclerview)
	implementation(libs.material)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.kotlinx.coroutines.play.services)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.okhttp)

	// On-device OCR and translation (ML Kit), and a morphological analyser to split recognised
	// sentences into dictionary words (Kuromoji, IPADIC dictionary).
	implementation(libs.mlkit.text.recognition.japanese)
	implementation(libs.mlkit.translate)
	implementation(libs.kuromoji.ipadic)

	testImplementation(libs.junit)
	testImplementation(libs.kotlinx.coroutines.test)
}
