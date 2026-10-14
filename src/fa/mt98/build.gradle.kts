import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manhwa Tower"
    libVersion = "1.6"
    versionCode = 2

    source {
        name = "Manhwa Tower"
        baseUrl = "https://mt98.ir"
        lang = "fa"
        contentWarning = ContentWarning.SAFE
    }
}