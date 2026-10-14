# Code de l'application gardé en entier (données JSON, écrans, tâches en arrière-plan) : R8 n'optimise
# que les bibliothèques. Noms conservés pour que les rapports de plantage restent lisibles.
-keep class com.dakyub.crunchymal.** { *; }
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable

# kotlinx.serialization : sérialiseurs générés (déjà couverts par les règles de la bibliothèque, par sûreté).
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Dépendances facultatives d'OkHttp et de bibliothèques absentes de l'app.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
