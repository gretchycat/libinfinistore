package com.infinifolder.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import com.infinifolder.model.*
import com.infinifolder.storage.StorageProvider
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class AppsStorageProvider(
    private val context: Context? = null,
    private val packageManager: PackageManager? = context?.packageManager,
    private val customAppQuery: (() -> List<InstalledAppItem>)? = null
) : StorageProvider {

    override val id: String = "apps"
    override val features = BackendFeatures(Support.UNSUPPORTED, Support.UNSUPPORTED, creation = CreationGuarantee.UNSUPPORTED, rename = Support.UNSUPPORTED, delete = Support.UNSUPPORTED, nativeLinks = Support.UNSUPPORTED, readOnly = true)


    override fun refForUri(uri: Uri): ResourceRef? = if (AppsUri.isAppsUri(uri)) ResourceRef(id, uri.toString()) else null

    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> {
        val uri = AppsUri.parse(ref.documentId)
        val parent = when {
            AppsUri.isRoot(uri) -> null
            AppsUri.isCategoryUri(uri) -> AppsUri.ROOT_URI
            else -> AppsUri.getCategoryName(uri)?.let(AppsUri::categoryUri)
        }
        return ProviderResult.Success(parent?.let { ResourceRef(id, it.toString()) })
    }
    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> = try {
        val uri = AppsUri.parse(ref.documentId)
        if (AppsUri.isAppUri(uri)) {
            val app = getAppItem(AppsUri.getPackageName(uri) ?: "") ?: throw java.io.IOException("App not found")
            ProviderResult.Success(ResourceEntry(ref, ResourceMetadata(app.label, "application/vnd.android.package-archive",
                app.sizeBytes, app.lastUpdateTime, false, canRead = app.canExtract, canWrite = false), uri))
        } else {
            ProviderResult.Success(ResourceEntry(ref, ResourceMetadata(AppsUri.getCategoryName(uri) ?: "Installed applications",
                "vnd.android.document/directory", null, null, true, canWrite = false), uri))
        }
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot query application", e) }

    @Volatile
    private var cachedApps: List<InstalledAppItem>? = null
    @Volatile
    private var cachedAppsByPackage: Map<String, InstalledAppItem> = emptyMap()
    @Volatile
    private var cachedAtMillis: Long = 0L

    private val singleAppCache = ConcurrentHashMap<String, InstalledAppItem>()
    private val iconFetchedAt = ConcurrentHashMap<String, Long>()
    private val iconSourceVersion = ConcurrentHashMap<String, Long>()
    private val labelMemoryCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var diskLabelsLoaded = false

    private val iconMemoryCache: MutableMap<String, Bitmap> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Bitmap>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 512
        }
    )

    private val labelCacheFile: File? by lazy {
        context?.cacheDir?.let { File(it, "app_labels_v1.json") }
    }

    fun invalidateCache() {
        cachedApps = null
        cachedAppsByPackage = emptyMap()
        cachedAtMillis = 0L
        singleAppCache.clear()
        iconFetchedAt.clear()
        iconSourceVersion.clear()
    }

    private fun ensureDiskLabelsLoaded() {
        if (diskLabelsLoaded) return
        diskLabelsLoaded = true
        val file = labelCacheFile ?: return
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "")
                if (value.isNotEmpty()) {
                    labelMemoryCache[key] = value
                }
            }
        } catch (_: Throwable) {}
    }

    private fun persistDiskLabels() {
        val file = labelCacheFile ?: return
        try {
            val json = JSONObject()
            for ((k, v) in labelMemoryCache) {
                json.put(k, v)
            }
            file.writeText(json.toString())
        } catch (_: Throwable) {}
    }

    private fun buildInstalledAppItem(pm: PackageManager, pkg: PackageInfo): InstalledAppItem? {
        val appInfo = pkg.applicationInfo ?: return null
        val packageName = pkg.packageName
        val sourceDir = appInfo.sourceDir ?: appInfo.publicSourceDir ?: ""
        val sourceFile = if (sourceDir.isNotEmpty()) File(sourceDir) else null
        val canExtract = sourceFile != null && sourceFile.exists() && sourceFile.canRead()
        val sizeBytes = if (canExtract && sourceFile != null) sourceFile.length() else 0L
        val lastUpdate = if (pkg.lastUpdateTime > 0) {
            pkg.lastUpdateTime
        } else if (canExtract && sourceFile != null) {
            sourceFile.lastModified()
        } else 0L

        val labelKey = "$packageName:$lastUpdate"
        val label = labelMemoryCache[labelKey] ?: try {
            pm.getApplicationLabel(appInfo).toString().trim().ifEmpty { packageName }.also {
                labelMemoryCache[labelKey] = it
            }
        } catch (_: Exception) {
            packageName
        }

        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isGame = (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        val categoryInt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appInfo.category
        } else {
            ApplicationInfo.CATEGORY_UNDEFINED
        }
        val category = AppCategories.categorize(categoryInt, isGame, isSystem)

        return InstalledAppItem(
            packageName = packageName,
            label = label,
            category = category,
            sourceDir = sourceDir,
            sizeBytes = sizeBytes,
            lastUpdateTime = lastUpdate,
            versionName = pkg.versionName,
            isSystem = isSystem,
            canExtract = canExtract
        )
    }

    private fun queryInstalledApps(forceRefresh: Boolean = false): List<InstalledAppItem> {
        if (customAppQuery != null) {
            val list = customAppQuery.invoke()
            cachedApps = list
            cachedAppsByPackage = list.associateBy { it.packageName }
            return list
        }
        val now = System.currentTimeMillis()
        val existing = cachedApps
        if (!forceRefresh && existing != null && (now - cachedAtMillis) < 300_000L) {
            return existing
        }
        val pm = packageManager ?: return emptyList()
        ensureDiskLabelsLoaded()
        val beforeCount = labelMemoryCache.size
        val queried = try {
            val packages: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
            packages.mapNotNull { pkg -> buildInstalledAppItem(pm, pkg) }
        } catch (_: Exception) {
            emptyList()
        }
        if (labelMemoryCache.size != beforeCount) {
            persistDiskLabels()
        }
        val byPkg = queried.associateBy { it.packageName }
        cachedAppsByPackage = byPkg
        singleAppCache.putAll(byPkg)
        cachedApps = queried
        cachedAtMillis = System.currentTimeMillis()
        return queried
    }

    fun getCategories(): List<String> {
        val apps = queryInstalledApps()
        val presentCategories = apps.map { it.category }.toSet()
        return AppCategories.ALL_CATEGORIES.filter { it in presentCategories }
            .ifEmpty { presentCategories.sorted() }
    }

    fun getCategoryAppCount(category: String): Int {
        return queryInstalledApps().count { it.category.equals(category, ignoreCase = true) }
    }

    fun getCategoryIcon(category: String): String = AppCategories.getCategoryIcon(category)

    fun canExtract(packageName: String): Boolean {
        return getAppItem(packageName)?.canExtract == true
    }

    fun getAppItem(packageName: String): InstalledAppItem? {
        if (customAppQuery != null) {
            return queryInstalledApps().firstOrNull { it.packageName == packageName }
        }
        cachedAppsByPackage[packageName]?.let { return it }
        singleAppCache[packageName]?.let { return it }
        val pm = packageManager ?: return null
        ensureDiskLabelsLoaded()
        return try {
            val pkg = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            buildInstalledAppItem(pm, pkg)?.also { singleAppCache[packageName] = it }
        } catch (_: Exception) {
            null
        }
    }

    fun getAppLabel(packageName: String): String? {
        return getAppItem(packageName)?.label
    }

    fun getCachedAppIconBitmap(packageName: String, sizePx: Int): Bitmap? {
        val targetSize = sizePx.coerceAtLeast(1)
        return iconMemoryCache["$packageName:$targetSize"]
    }

    fun getAppIconBitmap(packageName: String, sizePx: Int, useDiskCache: Boolean = true): Bitmap? {
        val targetSize = sizePx.coerceAtLeast(1)
        val memKey = "$packageName:$targetSize"
        val previous = iconMemoryCache[memKey]
        val now = System.currentTimeMillis()
        // Icon retrieval must not build app metadata or read APK attributes just to get artwork.
        val sourceVersion = cachedAppsByPackage[packageName]?.lastUpdateTime
            ?: singleAppCache[packageName]?.lastUpdateTime ?: 0L
        if (previous != null && iconSourceVersion[memKey] == sourceVersion &&
            now - (iconFetchedAt[memKey] ?: 0L) in 0 until 60 * 60 * 1000L) return previous

        val cache = if (useDiskCache) context?.let { com.infinifolder.storage.DiskCache.get(it) } else null
        val diskKey = "app-icon:$packageName:$targetSize:$sourceVersion:${now / (60 * 60 * 1000L)}"
        try {
            cache?.acquire(diskKey)?.use { lease ->
                val decoded = BitmapFactory.decodeFile(lease.file.absolutePath)
                if (decoded != null) {
                    iconMemoryCache[memKey] = decoded
                    iconFetchedAt[memKey] = lease.file.lastModified()
                    iconSourceVersion[memKey] = sourceVersion
                    return decoded
                }
            }
        } catch (_: Exception) {}

        val pm = packageManager ?: return previous
        return try {
            val drawable = pm.getApplicationIcon(packageName)
            val bitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            iconMemoryCache[memKey] = bitmap
            iconFetchedAt[memKey] = now
            iconSourceVersion[memKey] = sourceVersion
            if (cache != null) {
                try {
                    cache.allocate("app-icon.png").use { lease ->
                        lease.output().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        lease.complete(diskKey)
                    }
                } catch (_: Exception) {} // An optional thumbnail must not consume space needed by active documents.
            }
            bitmap
        } catch (_: Throwable) {
            previous
        }
    }

    fun getApkFileIconBitmap(filePath: String, sizePx: Int): Bitmap? {
        val targetSize = sizePx.coerceAtLeast(1)
        val file = File(filePath)
        if (!file.exists()) return null
        val memKey = "apk:${file.absolutePath}:${file.lastModified()}:$targetSize"
        iconMemoryCache[memKey]?.let { return it }

        val pm = packageManager ?: return null
        return try {
            val pkgInfo = pm.getPackageArchiveInfo(filePath, 0) ?: return null
            val appInfo = pkgInfo.applicationInfo ?: return null
            appInfo.sourceDir = filePath
            appInfo.publicSourceDir = filePath
            val drawable = appInfo.loadIcon(pm)
            val bitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            iconMemoryCache[memKey] = bitmap
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> {
        val uri = try { AppsUri.parse(parent.documentId) } catch (_: Throwable) { null }
        val allApps = queryInstalledApps()

        return if (AppsUri.isRoot(uri) || parent.documentId == "apps" || parent.documentId == "apps://installed" || parent.documentId == "apps://") {
            // Root level: return categories
            val categoriesWithApps = AppCategories.ALL_CATEGORIES.filter { cat ->
                allApps.any { it.category == cat }
            }.ifEmpty {
                allApps.map { it.category }.distinct().sorted()
            }

            val entries = categoriesWithApps.map { category ->
                val catUri = AppsUri.categoryUri(category)
                ResourceEntry(
                    ref = ResourceRef(id, catUri.toString()),
                    metadata = ResourceMetadata(
                        name = category,
                        mimeType = "vnd.android.document/directory",
                        sizeBytes = null,
                        modifiedAtMillis = null,
                        isDirectory = true,
                        canWrite = false
                    ),
                    uri = catUri
                )
            }
            ProviderResult.Success(entries)
        } else if (AppsUri.isCategoryUri(uri)) {
            val category = AppsUri.getCategoryName(uri)
                ?: return ProviderResult.Failure("Unknown category in URI: ${parent.documentId}")
            val categoryApps = allApps.filter { it.category.equals(category, ignoreCase = true) }
                .sortedWith(compareBy<InstalledAppItem> { it.label.lowercase() }.thenBy { it.packageName })

            val entries = categoryApps.map { app ->
                val cleanLabel = app.label.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifEmpty { app.packageName }
                val appUri = AppsUri.appUri(app.packageName, app.category)
                ResourceEntry(
                    ref = ResourceRef(id, appUri.toString()),
                    metadata = ResourceMetadata(
                        name = cleanLabel,
                        mimeType = "application/vnd.android.package-archive",
                        sizeBytes = if (app.canExtract) app.sizeBytes else null,
                        modifiedAtMillis = if (app.lastUpdateTime > 0) app.lastUpdateTime else null,
                        isDirectory = false,
                        canRead = app.canExtract,
                        canWrite = false
                    ),
                    uri = appUri
                )
            }
            ProviderResult.Success(entries)
        } else {
            ProviderResult.Success(emptyList())
        }
    }

    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> {
        val uri = try { AppsUri.parse(ref.documentId) } catch (_: Throwable) { null }
        val packageName = AppsUri.getPackageName(uri)
            ?: return ProviderResult.Failure("Invalid app resource reference: ${ref.documentId}")

        val app = getAppItem(packageName)
            ?: return ProviderResult.Failure("Application $packageName not found")

        if (!app.canExtract) {
            return ProviderResult.Failure("APK for ${app.label} cannot be extracted (restricted access)")
        }

        val file = File(app.sourceDir)
        if (!file.exists() || !file.canRead()) {
            return ProviderResult.Failure("APK file does not exist or cannot be read: ${file.absolutePath}")
        }

        return try {
            ProviderResult.Success(FileInputStream(file))
        } catch (e: Exception) {
            ProviderResult.Failure("Failed to open APK for ${app.label}: ${e.message}", e)
        }
    }

    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> =
        ProviderResult.Unsupported(ResourceCapability.WRITE)

    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> =
        ProviderResult.Unsupported(ResourceCapability.CREATE)

    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> =
        ProviderResult.Unsupported(ResourceCapability.CREATE)

    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> =
        ProviderResult.Unsupported(ResourceCapability.RENAME)

    override fun delete(ref: ResourceRef): ProviderResult<Unit> =
        ProviderResult.Unsupported(ResourceCapability.DELETE)

    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        ProviderResult.Unsupported(ResourceCapability.COPY)

    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        ProviderResult.Unsupported(ResourceCapability.MOVE)
}
