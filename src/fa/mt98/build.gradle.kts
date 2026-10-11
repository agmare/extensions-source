import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    id("io.github.keiyoushi.extension")
}

keiyoushi {
    name = "MT98"
    libVersion = "1.6"
    versionCode = 1

    source {
        name = "MT98"
        baseUrl = "https://mt98.ir"
        lang = "fa"
        contentWarning = ContentWarning.SAFE
    }
}