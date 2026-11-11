package com.infinifolder.apps

import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceCapability
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceMetadata
import com.infinifolder.model.ResourceRef
import com.infinifolder.ops.FileOperations
import com.infinifolder.preview.DirectoryIconResolver
import com.infinifolder.shortcut.ShortcutInfo
import com.infinifolder.storage.RoutingStorageProvider
import com.infinifolder.storage.StorageProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class AppsStorageProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @org.junit.Before
    fun setUp() {
        AppsUri.uriFactory = { android.net.TestUri(it) }
    }

    @Test
    fun testCategoryMapping() {
        assertEquals("Games", AppCategories.categorize(0, isGameFlag = false, isSystemFlag = false))
        assertEquals("Audio", AppCategories.categorize(1, isGameFlag = false, isSystemFlag = false))
        assertEquals("Video", AppCategories.categorize(2, isGameFlag = false, isSystemFlag = false))
        assertEquals("Photography", AppCategories.categorize(3, isGameFlag = false, isSystemFlag = false))
        assertEquals("Social", AppCategories.categorize(4, isGameFlag = false, isSystemFlag = false))
        assertEquals("News", AppCategories.categorize(5, isGameFlag = false, isSystemFlag = false))
        assertEquals("Maps", AppCategories.categorize(6, isGameFlag = false, isSystemFlag = false))
        assertEquals("Productivity", AppCategories.categorize(7, isGameFlag = false, isSystemFlag = false))
        assertEquals("Accessibility", AppCategories.categorize(8, isGameFlag = false, isSystemFlag = false))

        // Undefined category (-1) with flags
        assertEquals("Games", AppCategories.categorize(-1, isGameFlag = true, isSystemFlag = false))
        assertEquals("System", AppCategories.categorize(-1, isGameFlag = false, isSystemFlag = true))
        assertEquals("Other", AppCategories.categorize(-1, isGameFlag = false, isSystemFlag = false))
    }

    @Test
    fun testCategoryIcons() {
        assertEquals("🎮", AppCategories.getCategoryIcon("Games"))
        assertEquals("🎵", AppCategories.getCategoryIcon("Audio"))
        assertEquals("🎬", AppCategories.getCategoryIcon("Video"))
        assertEquals("📷", AppCategories.getCategoryIcon("Photography"))
        assertEquals("💬", AppCategories.getCategoryIcon("Social"))
        assertEquals("📰", AppCategories.getCategoryIcon("News"))
        assertEquals("🗺️", AppCategories.getCategoryIcon("Maps"))
        assertEquals("💼", AppCategories.getCategoryIcon("Productivity"))
        assertEquals("♿", AppCategories.getCategoryIcon("Accessibility"))
        assertEquals("⚙️", AppCategories.getCategoryIcon("System"))
        assertEquals("📦", AppCategories.getCategoryIcon("Other"))
    }

    @Test
    fun testAppsUriParsing() {
        val rootUri = AppsUri.ROOT_URI
        assertTrue(AppsUri.isAppsUri(rootUri))
        assertTrue(AppsUri.isRoot(rootUri))
        assertFalse(AppsUri.isCategoryUri(rootUri))
        assertFalse(AppsUri.isAppUri(rootUri))

        val catUri = AppsUri.categoryUri("Games")
        assertTrue(AppsUri.isAppsUri(catUri))
        assertFalse(AppsUri.isRoot(catUri))
        assertTrue(AppsUri.isCategoryUri(catUri))
        assertFalse(AppsUri.isAppUri(catUri))
        assertEquals("Games", AppsUri.getCategoryName(catUri))

        val appUri = AppsUri.appUri("com.example.game", "Games")
        assertTrue(AppsUri.isAppsUri(appUri))
        assertFalse(AppsUri.isRoot(appUri))
        assertFalse(AppsUri.isCategoryUri(appUri))
        assertTrue(AppsUri.isAppUri(appUri))
        assertEquals("com.example.game", AppsUri.getPackageName(appUri))
        assertEquals("Games", AppsUri.getCategoryName(appUri))
    }

    @Test
    fun testAppsStorageProviderListRootAndCategories() {
        val fakeApk1 = tempFolder.newFile("calculator.apk").apply { writeText("fake-apk-1") }
        val fakeApk2 = tempFolder.newFile("game.apk").apply { writeText("fake-apk-2") }

        val apps = listOf(
            InstalledAppItem(
                packageName = "com.android.calculator",
                label = "Calculator",
                category = "Productivity",
                sourceDir = fakeApk1.absolutePath,
                sizeBytes = fakeApk1.length(),
                lastUpdateTime = 1000L,
                versionName = "1.0",
                isSystem = true,
                canExtract = true
            ),
            InstalledAppItem(
                packageName = "com.example.racer",
                label = "Super Racer",
                category = "Games",
                sourceDir = fakeApk2.absolutePath,
                sizeBytes = fakeApk2.length(),
                lastUpdateTime = 2000L,
                versionName = "2.1",
                isSystem = false,
                canExtract = true
            )
        )

        val provider = AppsStorageProvider(customAppQuery = { apps })

        // 1. List Root: returns categories that contain apps
        val rootRef = ResourceRef("apps", AppsUri.ROOT_URI.toString())
        val rootList = provider.list(rootRef)
        assertTrue(rootList is ProviderResult.Success)
        val categories = (rootList as ProviderResult.Success).value
        assertEquals(2, categories.size)
        val catNames = categories.map { it.metadata.name }
        assertTrue(catNames.contains("Games"))
        assertTrue(catNames.contains("Productivity"))
        assertTrue(categories.all { it.metadata.isDirectory })

        // 2. List Category "Games"
        val gamesRef = ResourceRef("apps", AppsUri.categoryUri("Games").toString())
        val gamesList = provider.list(gamesRef)
        assertTrue(gamesList is ProviderResult.Success)
        val gamesEntries = (gamesList as ProviderResult.Success).value
        assertEquals(1, gamesEntries.size)
        val gameEntry = gamesEntries[0]
        assertEquals("Super Racer", gameEntry.metadata.name)
        assertEquals("application/vnd.android.package-archive", gameEntry.metadata.mimeType)
        assertFalse(gameEntry.metadata.isDirectory)
        assertEquals(fakeApk2.length(), gameEntry.metadata.sizeBytes)

        // 3. List Category "Productivity"
        val prodRef = ResourceRef("apps", AppsUri.categoryUri("Productivity").toString())
        val prodList = provider.list(prodRef)
        assertTrue(prodList is ProviderResult.Success)
        val prodEntries = (prodList as ProviderResult.Success).value
        assertEquals(1, prodEntries.size)
        val prodEntry = prodEntries[0]
        assertEquals("Calculator", prodEntry.metadata.name)
        assertEquals(fakeApk1.length(), prodEntry.metadata.sizeBytes)
    }

    @Test
    fun testExtractableVsUnextractableApk() {
        val readableApk = tempFolder.newFile("extractable.apk").apply { writeText("readable-content") }
        val nonExistentApkPath = File(tempFolder.root, "non_existent.apk").absolutePath

        val apps = listOf(
            InstalledAppItem(
                packageName = "com.example.extractable",
                label = "Extractable App",
                category = "Other",
                sourceDir = readableApk.absolutePath,
                sizeBytes = readableApk.length(),
                lastUpdateTime = 1000L,
                canExtract = true
            ),
            InstalledAppItem(
                packageName = "com.example.restricted",
                label = "Restricted System App",
                category = "System",
                sourceDir = nonExistentApkPath,
                sizeBytes = 0L,
                lastUpdateTime = 1000L,
                canExtract = false
            )
        )

        val provider = AppsStorageProvider(customAppQuery = { apps })

        assertTrue(provider.canExtract("com.example.extractable"))
        assertFalse(provider.canExtract("com.example.restricted"))

        // Read extractable APK
        val extractableRef = ResourceRef("apps", AppsUri.appUri("com.example.extractable", "Other").toString())
        val readResult = provider.openRead(extractableRef)
        assertTrue(readResult is ProviderResult.Success)
        val content = (readResult as ProviderResult.Success).value.use { it.reader().readText() }
        assertEquals("readable-content", content)

        // Read restricted APK
        val restrictedRef = ResourceRef("apps", AppsUri.appUri("com.example.restricted", "System").toString())
        val restrictedResult = provider.openRead(restrictedRef)
        assertTrue(restrictedResult is ProviderResult.Failure)
    }

    @Test
    fun testVirtualDirectoryIsReadOnly() {
        val provider = AppsStorageProvider(customAppQuery = { emptyList() })
        val ref = ResourceRef("apps", AppsUri.ROOT_URI.toString())

        assertTrue(provider.createFile(ref, "test.txt") is ProviderResult.Unsupported)
        assertEquals(ResourceCapability.CREATE, (provider.createFile(ref, "test.txt") as ProviderResult.Unsupported).capability)

        assertTrue(provider.createDirectory(ref, "new_folder") is ProviderResult.Unsupported)
        assertEquals(ResourceCapability.CREATE, (provider.createDirectory(ref, "new_folder") as ProviderResult.Unsupported).capability)

        assertTrue(provider.openWrite(ref) is ProviderResult.Unsupported)
        assertTrue(provider.rename(ref, "renamed") is ProviderResult.Unsupported)
        assertTrue(provider.delete(ref) is ProviderResult.Unsupported)
        assertTrue(provider.copy(ref, ref) is ProviderResult.Unsupported)
        assertTrue(provider.move(ref, ref) is ProviderResult.Unsupported)
    }

    @Test
    fun testCopyCategoryCreatesDirectoryWithIconAndAppLinks() {
        val destRoot = tempFolder.newFolder("dest")
        val apps = listOf(
            InstalledAppItem(
                packageName = "com.example.chess",
                label = "Chess Master",
                category = "Games",
                sourceDir = "/data/app/chess.apk",
                sizeBytes = 1024L,
                lastUpdateTime = 1000L,
                canExtract = false
            ),
            InstalledAppItem(
                packageName = "com.example.sudoku",
                label = "Sudoku Pro",
                category = "Games",
                sourceDir = "/data/app/sudoku.apk",
                sizeBytes = 2048L,
                lastUpdateTime = 2000L,
                canExtract = true
            )
        )

        val appsProvider = AppsStorageProvider(customAppQuery = { apps })
        val fsProvider = object : StorageProvider {
            override val id = "local"
            private fun file(ref: ResourceRef) = File(ref.documentId)
            private fun entry(f: File) = ResourceEntry(
                ResourceRef(id, f.absolutePath),
                ResourceMetadata(
                    name = f.name,
                    mimeType = if (f.isDirectory) "vnd.android.document/directory"
                    else if (f.name.endsWith(".png")) "image/png"
                    else if ((f.name.endsWith(".lnk") || f.name.endsWith(ShortcutInfo.SHORTCUT_EXTENSION))) ShortcutInfo.SHORTCUT_MIME
                    else "application/octet-stream",
                    sizeBytes = if (f.isDirectory) null else f.length(),
                    modifiedAtMillis = f.lastModified(),
                    isDirectory = f.isDirectory
                ),
                android.net.TestUri(f.absolutePath)
            )
            override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> =
                ProviderResult.Success(entry(file(ref)))
            override fun list(parent: ResourceRef) = ProviderResult.Success(
                file(parent).listFiles().orEmpty().map { entry(it) }
            )
            override fun openRead(ref: ResourceRef): ProviderResult<InputStream> =
                ProviderResult.Success(file(ref).inputStream())
            override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> =
                ProviderResult.Success(file(ref).outputStream())
            override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> {
                val dir = File(file(parent), name).apply { mkdirs() }
                return ProviderResult.Success(entry(dir))
            }
            override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> {
                val f = File(file(parent), name).apply { createNewFile() }
                return ProviderResult.Success(entry(f))
            }
            override fun rename(ref: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.RENAME)
            override fun delete(ref: ResourceRef): ProviderResult<Unit> {
                file(ref).deleteRecursively()
                return ProviderResult.Success(Unit)
            }
            override fun copy(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.COPY)
            override fun move(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.MOVE)
        }

        val router = RoutingStorageProvider(appsProvider, fsProvider)
        val rootEntries = (appsProvider.list(ResourceRef("apps", AppsUri.ROOT_URI.toString())) as ProviderResult.Success).value
        val gamesCategoryEntry = rootEntries.first { it.metadata.name == "Games" }

        val destUri = android.net.TestUri(destRoot.absolutePath)
        FileOperations.batchTransfer(
            entries = listOf(gamesCategoryEntry),
            destinationFolderUri = destUri,
            move = false,
            provider = router,
            resourceRef = { uri ->
                val s = uri.toString()
                if (s.startsWith("apps://")) ResourceRef("apps", s) else ResourceRef("local", s)
            },
            update = {},
            isCancelled = { false }
        )

        val copiedCategoryDir = File(destRoot, "Games")
        assertTrue("Category directory 'Games' should be created", copiedCategoryDir.exists() && copiedCategoryDir.isDirectory)

        val iconFile = File(copiedCategoryDir, ".icon.png")
        assertTrue("Category icon '.icon.png' should exist inside 'Games'", iconFile.exists() && iconFile.length() > 0)

        val dirChildren = (fsProvider.list(ResourceRef("local", copiedCategoryDir.absolutePath)) as ProviderResult.Success).value
        val resolvedIcon = DirectoryIconResolver.findBestIcon(dirChildren)
        assertNotNull("DirectoryIconResolver should resolve .icon.png for copied category", resolvedIcon)
        assertEquals(".icon.png", resolvedIcon?.metadata?.name)

        val chessLinkFile = File(copiedCategoryDir, "Chess Master${ShortcutInfo.SHORTCUT_EXTENSION}")
        val sudokuLinkFile = File(copiedCategoryDir, "Sudoku Pro${ShortcutInfo.SHORTCUT_EXTENSION}")
        assertTrue("Chess Master.lnk should be created", chessLinkFile.exists())
        assertTrue("Sudoku Pro.lnk should be created", sudokuLinkFile.exists())

        val chessShortcut = ShortcutInfo.fromJson(chessLinkFile.readText())
        assertNotNull(chessShortcut)
        assertEquals(AppsUri.appUri("com.example.chess", "Games").toString(), chessShortcut?.targetUri)
        assertEquals("Chess Master", chessShortcut?.displayNameOverride)

        val sudokuShortcut = ShortcutInfo.fromJson(sudokuLinkFile.readText())
        assertNotNull(sudokuShortcut)
        assertEquals(AppsUri.appUri("com.example.sudoku", "Games").toString(), sudokuShortcut?.targetUri)
        assertEquals("Sudoku Pro", sudokuShortcut?.displayNameOverride)
    }
}
