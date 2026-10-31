plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "io.github.gretchycat.infinistore.store.android"
    compileSdk = 36
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    api("io.github.gretchycat.libinfinistream:stream-core:0.1.0")
    api("androidx.core:core:1.15.0")
    api("org.apache.commons:commons-compress:1.27.1")
    api("org.tukaani:xz:1.10")
    implementation("com.github.luben:zstd-jni:1.5.7-11@aar")
    api("com.github.mwiede:jsch:2.28.8")
    api("commons-net:commons-net:3.11.1")
    api("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.atomashpolskiy:bt-core:1.10") {
        exclude(group = "com.google.inject", module = "guice")
    }
    implementation("com.github.atomashpolskiy:bt-dht:1.10") {
        exclude(group = "com.google.inject", module = "guice")
    }
    implementation("com.github.atomashpolskiy:bt-http-tracker-client:1.10") {
        exclude(group = "com.google.inject", module = "guice")
    }
    // Bt 1.10 requests Guice 5, which calls a reflection API missing on Android.
    // Guice 4's regular artifact uses CGLIB runtime bytecode generation, which ART rejects.
    // The no_aop artifact avoids both Android incompatibilities; Bt does not use AOP here.
    implementation("com.google.inject:guice:4.2.3:no_aop")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
