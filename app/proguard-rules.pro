# smbj / bouncycastle / nfs-client use reflection and optional deps
-dontwarn org.slf4j.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn org.bouncycastle.**
-dontwarn net.engio.mbassy.**
-dontwarn org.jboss.netty.**
-dontwarn io.netty.**
-dontwarn org.apache.log4j.**
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.emc.ecs.nfsclient.** { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keepclassmembers class * { @kotlinx.serialization.Serializable *; }
# whisper.cpp JNI: 네이티브 메서드와 JNI가 이름으로 부르는 onProgress를 지키지 않으면 릴리스 빌드에서 죽는다
-keep class com.lumen.player.subtitle.ai.WhisperNative { *; }
-keep interface com.lumen.player.subtitle.ai.WhisperNative$ProgressListener { *; }
-keep class * implements com.lumen.player.subtitle.ai.WhisperNative$ProgressListener { void onProgress(int); }
