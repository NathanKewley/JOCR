package io.github.nathankewley.jocr.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class PaddleConfigTest {

	/** The shape of PP-OCRv5's inference.yml, cut down. */
	@Test
	fun charactersAreReadAndUnquoted() {
		val yaml = """
			|PostProcess:
			|  name: CTCLabelDecode
			|  character_dict:
			|  - '!'
			|  - ''''
			|  - あ
			|  - 漢
			|  - 🇯🇵
			|PreProcess:
			|  - x
		""".trimMargin()
		assertEquals(listOf("!", "'", "あ", "漢", "🇯🇵"), PaddleConfig.characters(yaml))
	}
}
