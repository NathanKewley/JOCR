package io.github.nathankewley.jocr.ocr

/** A model file, fetched from Hugging Face: pinned to a revision and checked against its hash. */
class ModelFile(val name: String, val url: String, val sha256: String, val size: Long)

/**
 * The text recognisers to choose from, and the model files each needs on the phone. ML Kit's model
 * ships inside its library; the others are downloaded when chosen (see [ModelStore]).
 */
enum class OcrEngine(val id: String, val files: List<ModelFile>) {

	PADDLE_MANGA("paddle_manga", Models.PADDLE + Models.MANGA_OCR),
	PADDLE("paddle", Models.PADDLE),
	MLKIT("mlkit", emptyList()),
	;

	companion object {

		fun fromId(id: String): OcrEngine? = entries.find { it.id == id }
	}
}

/**
 * PaddleOCR's PP-OCRv5 mobile models, as exported to ONNX by PaddlePaddle, and manga-ocr, as
 * exported by onnx-community. All Apache-2.0.
 */
internal object Models {

	private const val PADDLE_DET = "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/e6f4fa85f00e168c862bc462aebca69eef9b3d3d"
	private const val PADDLE_REC = "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/resolve/ed152b8b495f84de93cda5709d768548a9127622"
	private const val MANGA_ONNX = "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/f9023406bb2f6b17df67bc4a327c56ecd20611f0"
	private const val MANGA = "https://huggingface.co/kha-white/manga-ocr-base/resolve/aa6573bd10b0d446cbf622e29c3e084914df9741"

	val PADDLE_DETECTOR = ModelFile(
		"paddle-det.onnx", "$PADDLE_DET/inference.onnx",
		"a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d", 4_826_518,
	)
	val PADDLE_READER = ModelFile(
		"paddle-rec.onnx", "$PADDLE_REC/inference.onnx",
		"da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092", 16_534_782,
	)

	/** The reader's config, which holds the characters it knows (see [PaddleConfig]). */
	val PADDLE_READER_CONFIG = ModelFile(
		"paddle-rec.yml", "$PADDLE_REC/inference.yml",
		"5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e", 148_345,
	)
	val MANGA_ENCODER = ModelFile(
		"manga-ocr-encoder.onnx", "$MANGA_ONNX/onnx/encoder_model.onnx",
		"df35f64c2400ea860c70a2d06f2a1f99892374a78c89fcf35308076557a3863f", 343_377_067,
	)
	val MANGA_DECODER = ModelFile(
		"manga-ocr-decoder.onnx", "$MANGA_ONNX/onnx/decoder_model.onnx",
		"31ca14d6dee6b3966144e128d0481d5a91f5083cbced81fe7a9571713fa50cd4", 117_445_718,
	)

	/** The ONNX export leaves out the tokenizer; the vocabulary is the original model's. */
	val MANGA_VOCAB = ModelFile(
		"manga-ocr-vocab.txt", "$MANGA/vocab.txt",
		"344fbb6b8bf18c57839e924e2c9365434697e0227fac00b88bb4899b78aa594d", 24_072,
	)

	val PADDLE = listOf(PADDLE_DETECTOR, PADDLE_READER, PADDLE_READER_CONFIG)
	val MANGA_OCR = listOf(MANGA_ENCODER, MANGA_DECODER, MANGA_VOCAB)
}
