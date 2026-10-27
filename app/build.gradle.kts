import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

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
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		// ML Kit's OCR and translation libraries are ~28 MB per ABI, ONNX Runtime ~34 MB more. Phones
		// are arm64; x86_64 is for the emulator. The 32-bit ABIs would add tens of MB for devices that
		// no longer exist.
		ndk {
			abiFilters += listOf("arm64-v8a", "x86_64")
		}
	}

	// The release key lives outside git, in /keystore. Without it a release build comes out unsigned.
	val keystoreProperties = rootProject.file("keystore/keystore.properties").takeIf { it.exists() }?.let { file ->
		Properties().apply { file.inputStream().use(::load) }
	}
	signingConfigs {
		if (keystoreProperties != null) {
			create("release") {
				storeFile = rootProject.file("keystore/" + keystoreProperties.getProperty("storeFile"))
				storePassword = keystoreProperties.getProperty("storePassword")
				keyAlias = keystoreProperties.getProperty("keyAlias")
				keyPassword = keystoreProperties.getProperty("keyPassword")
			}
		}
	}

	buildTypes {
		release {
			signingConfig = signingConfigs.findByName("release")
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
		}
		debug {
			applicationIdSuffix = ".debug"
		}
	}
	applicationVariants.all {
		outputs.all {
			(this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
				"jocr-${versionName}-${buildType.name}.apk"
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

	// Runs PaddleOCR and manga-ocr, the other recognisers to choose from. Their models are downloaded
	// on the phone when chosen (see ModelStore).
	implementation(libs.onnxruntime.android)

	testImplementation(libs.junit)
	testImplementation(libs.kotlinx.coroutines.test)
	androidTestImplementation(libs.androidx.test.junit)
	androidTestImplementation(libs.androidx.test.runner)
}
