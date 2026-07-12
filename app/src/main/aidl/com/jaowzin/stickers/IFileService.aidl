package com.jaowzin.stickers;

import android.os.ParcelFileDescriptor;

interface IFileService {
    void destroy() = 16777114;
    int countFiles() = 1;
    String[] listItems(int offset, int limit) = 2;
    ParcelFileDescriptor openContent(String path, long dataOffset) = 3;
    boolean canReadCache() = 4;
    String getCacheRoot() = 5;
}
