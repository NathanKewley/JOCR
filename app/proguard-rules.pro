# Kuromoji opens its dictionary with Class.getResourceAsStream relative to its own classes, so a
# renamed or repackaged class looks in a folder that does not exist and the tokenizer fails to load.
-keep class com.atilika.kuromoji.** { *; }

# ONNX Runtime's native code finds its Java classes, fields and methods by name.
-keep class ai.onnxruntime.** { *; }
