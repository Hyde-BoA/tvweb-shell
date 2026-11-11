package com.tvweb.shell;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 FileProvider 替代品：只对外暴露缓存目录下的 update.apk。
 *
 * Android 7.0 起禁止用 file:// 把文件交给别的应用（FileUriExposedException），
 * 系统安装器需要 content:// URI，所以必须有这么一个 provider。
 */
public class UpdateFileProvider extends ContentProvider {

    public static final String NAME = "update.apk";
    private static final String AUTHORITY_SUFFIX = ".fileprovider";

    public static Uri uriFor(Context context) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + AUTHORITY_SUFFIX)
                .appendPath(NAME)
                .build();
    }

    private File target() {
        return new File(getContext().getCacheDir(), NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = target();
        String[] cols = projection != null ? projection : new String[]{"_display_name", "_size"};
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if ("_display_name".equals(cols[i])) {
                row[i] = NAME;
            } else if ("_size".equals(cols[i])) {
                row[i] = f.exists() ? f.length() : 0L;
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = target();
        if (!f.exists()) {
            throw new FileNotFoundException("update.apk not found");
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
