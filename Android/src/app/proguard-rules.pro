# Project-specific release keep rules live here.
# Keep this file explicit so release minification has a stable local extension point.

# Protobuf lite descriptors refer to generated field names at runtime.
-keep class selfgemma.talk.proto.** { *; }
