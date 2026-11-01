# Keep Firestore's reflection-based serialization working.
-keepattributes Signature
-keepattributes *Annotation*

# Hilt and Compose are handled by their own consumer rules.
-dontwarn org.jetbrains.annotations.**

# core/health reads record times through their public getters (Health Connect keeps the shared
# instant/interval interfaces internal), so keep those getters' names.
-keepclassmembers class androidx.health.connect.client.records.** {
    public java.time.Instant getTime();
    public java.time.Instant getStartTime();
    public java.time.Instant getEndTime();
}
