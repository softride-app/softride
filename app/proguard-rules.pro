# Pleos SDKs talk to vehicle services over AIDL/Gson/reflection: keep them whole.
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-keep class ai.umos.** { *; }
-keep class com.google.gson.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-dontwarn ai.pleos.**
-dontwarn ai.umos.**
-dontwarn android.car.**
-dontwarn javax.annotation.**
