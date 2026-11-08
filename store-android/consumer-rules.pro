-keep class com.github.luben.zstd.** { *; }

# SftpRawProtocol uses JSch's single-channel packet buffers for the SFTP v3
# copy-data extension and the non-overwriting RENAME request. Keep only the
# private members addressed reflectively by that adapter.
-keepclassmembers class com.jcraft.jsch.ChannelSftp {
    private int seq;
    private com.jcraft.jsch.Buffer buf;
    private com.jcraft.jsch.Buffer obuf;
    private com.jcraft.jsch.Packet opacket;
    private java.io.InputStream io_in;
    private void putHEAD(com.jcraft.jsch.Buffer, byte, int);
    private com.jcraft.jsch.ChannelSftp$Header header(com.jcraft.jsch.Buffer, com.jcraft.jsch.ChannelSftp$Header);
    private void fill(com.jcraft.jsch.Buffer, int);
}
-keepclassmembers class com.jcraft.jsch.Packet { void reset(); }
-keepclassmembers class com.jcraft.jsch.Session {
    void write(com.jcraft.jsch.Packet, com.jcraft.jsch.Channel, int);
}
-keep class com.jcraft.jsch.ChannelSftp$Header { *; }
