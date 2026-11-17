// AGP 9 ma wbudowaną obsługę Kotlina (bez wtyczki kotlin-android); wtyczka Compose ustala wersję kompilatora Kotlina.
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
