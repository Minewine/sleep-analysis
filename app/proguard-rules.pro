# Keep Room-generated implementations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Hilt / dagger work
-keep class dagger.hilt.** { *; }

# No networking, no obfuscation-sensitive reflection beyond the above.
