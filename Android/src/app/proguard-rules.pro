# Project-specific release keep rules live here.
# Keep this file explicit so release minification has a stable local extension point.

# Protobuf lite descriptors refer to generated field names at runtime.
-keep class selfgemma.talk.proto.** { *; }

# Gson needs generic signatures and stable field names for JSON-backed release catalogs.
-keepattributes Signature
-keep class selfgemma.talk.data.ModelAllowlist { *; }
-keep class selfgemma.talk.data.AllowedModel { *; }
-keep class selfgemma.talk.data.DefaultConfig { *; }
-keep class selfgemma.talk.data.SocModelFile { *; }
-keep class selfgemma.talk.data.SkillAllowlist { *; }
-keep class selfgemma.talk.data.AllowedSkill { *; }

# LiteRT LM native code calls Java/Kotlin API methods by name through JNI.
-keep class com.google.ai.edge.litertlm.** { *; }
