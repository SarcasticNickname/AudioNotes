pluginManagement {
    repositories {
        google() // Репозиторий Google для плагинов Android/Jetpack
        mavenCentral() // Центральный репозиторий Maven
        gradlePluginPortal() // Портал плагинов Gradle (важно для многих плагинов)
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS) // Рекомендуемая практика
    repositories {
        google() // Репозиторий Google для библиотек
        mavenCentral() // Центральный репозиторий Maven для библиотек
    }
}

rootProject.name = "AudioNotes" // Имя твоего проекта
include(":app") // Включаем модуль app в сборку