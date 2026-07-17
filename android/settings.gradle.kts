pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("http://repo.boox.com/repository/maven-public/")
            // Onyx publishes over plain HTTP; risk contained by the exact version pin
            // in libs.versions.toml and the group content filter below.
            isAllowInsecureProtocol = true
            content {
                includeGroupByRegex("com\\.onyx.*")
                // Transitives of onyxsdk-base that exist ONLY on repo.boox.com
                // (jcenter-era or Onyx forks): hugo fork, easypermissions 0.2.1,
                // mmkv 1.0.19.
                includeGroup("com.jakewharton.hugo.fix")
                includeModule("pub.devrel", "easypermissions")
                includeModule("com.tencent", "mmkv")
            }
        }
    }
}

rootProject.name = "CipherCodex"
include(":app")
