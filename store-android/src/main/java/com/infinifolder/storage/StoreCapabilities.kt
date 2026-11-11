package com.infinifolder.storage

import io.github.gretchycat.infinistream.*

class StoreCapabilityProvider : CapabilityProvider {
    override val library="store"
    override fun handlers():List<CapabilityHandler> = listOf(
        BuiltinStorageCapabilityHandler("apps","Installed applications",setOf("apps"),true) { com.infinifolder.apps.AppsStorageProvider(it) },
        BuiltinStorageCapabilityHandler("links","Container links",setOf("container-links"),false) { ContainerLinksProvider(it) },
        BuiltinStorageCapabilityHandler("partial-downloads","Unfinished downloads",setOf("partial-downloads"),true) { PartialDownloadsProvider(it) },
        BuiltinStorageCapabilityHandler("recent-external-links","Recent external links",setOf("recent-external-links"),true) { RecentExternalLinksProvider(it) },
        BuiltinStorageCapabilityHandler("torrent","BitTorrent",setOf("magnet","torrent"),true) { com.infinifolder.torrent.MagnetStorageProvider(it) },
        ProtocolCapabilityHandler(com.infinifolder.sftp.SftpProviderFactory.sourceType,setOf("sftp","ssh","sshfs")) { c,l ->com.infinifolder.sftp.SftpProviderFactory(c,l) },
        ProtocolCapabilityHandler(com.infinifolder.ftp.FtpProviderFactory.sourceType,setOf("ftp","ftps")) { c,l ->com.infinifolder.ftp.FtpProviderFactory(c,l) },
        ProtocolCapabilityHandler(com.infinifolder.smb.SmbProviderFactory.sourceType,setOf("smb","cifs")) { c,l ->com.infinifolder.smb.SmbProviderFactory(c,l) },
        ProtocolCapabilityHandler(com.infinifolder.webdav.WebDavProviderFactory.sourceType,setOf("webdav","dav","davs")) { c,l ->com.infinifolder.webdav.WebDavProviderFactory(c,l) }
    )
}

