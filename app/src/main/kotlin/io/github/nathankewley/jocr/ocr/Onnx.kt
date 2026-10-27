package io.github.nathankewley.jocr.ocr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/** ONNX Runtime, shared by the recognisers that run on it. */
internal object Onnx {

	val env: OrtEnvironment by lazy {
		// No usage reports to Microsoft; see also the manifest.
		OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
	}

	/** A session for the model in [file], which ONNX Runtime reads itself, outside the Java heap. */
	fun session(file: File): OrtSession = env.createSession(file.path, OrtSession.SessionOptions())
}
