# JOCR

A minimal Android app for reading Japanese off a photo, without fuss.

1. **Take a photo** with the camera, **pick one** from the gallery, or **share an image** to JOCR from any app.
2. JOCR reads the Japanese text on the device and lists it block by block, numbered to match boxes drawn on the photo. The photo stays in the top half while the text scrolls below it; tapping a block or one of its words zooms the photo in on that block, and the photo can be pinched and dragged.
3. Each block is split into words, with furigana over kanji and an on-device English translation, hidden until you tap it if you want to read first.
4. Tap a word to look it up in [renshuu](https://www.renshuu.org/): definitions, a kanji breakdown, and **Add to List**, which adds it to one of your vocabulary schedules or lists.

The OCR and word-splitting pipeline comes from the Japanese-learning sidebar in Kotatsu-Redo-OCR.

## How it works

| Step | What | Where |
|---|---|---|
| Decode | `ImageDecoder`: applies EXIF rotation, reads HEIC, caps size at 3200 px | `ocr/PhotoDecoder.kt` |
| OCR | ML Kit Japanese text recognition (bundled model, works without Play Services) | `ocr/PhotoTextRecognizer.kt` |
| Vertical text | ML Kit can't read vertical columns, so each region is also re-laid out as horizontal lines and re-read; the more confident reading wins | `ocr/VerticalTextLayout.kt` |
| Grouping | Fragments are clustered into blocks (speech bubbles, paragraphs) | `ocr/TextRegions.kt` |
| Order | Rows top to bottom; right to left when most text is vertical, left to right otherwise | `ocr/ReadingOrder.kt` |
| Words | Kuromoji (IPADIC), with conjugation tails re-attached (食べ/て/い/た → 食べていた → 食べる) | `nlp/` |
| Translation | ML Kit on-device translation (about 30 MB model, downloaded once) | `translate/` |
| Dictionary | renshuu API: word search, kanji, schedules/lists, add word | `renshuu/` |

## Setup

- Get your renshuu API key from renshuu's settings panel and paste it into **Settings → renshuu API key**. The key is checked straight away, and the setting shows whose account it belongs to.
- The renshuu API allows 2000 calls a day. Lookups happen only when you tap a word and are cached for the session. "Show which lists a word is in" costs one extra call per lookup; turn it off to halve usage.

## Building

Requires JDK 17 and an Android SDK with platform 37.

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug    # app/build/outputs/apk/debug/
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest
```

Min SDK 28 (Android 9).
