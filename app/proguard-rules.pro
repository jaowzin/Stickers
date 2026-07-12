# The Shizuku UserService is instantiated reflectively in a privileged process.
-keep class com.jaowzin.stickers.service.FileService { public <init>(...); *; }
-keep class com.jaowzin.stickers.IFileService$Stub { *; }
