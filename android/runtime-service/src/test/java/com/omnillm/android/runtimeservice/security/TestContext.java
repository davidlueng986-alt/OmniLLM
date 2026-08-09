// GENERATED test-only Context stub - android.content.Context abstract methods
// implemented as throwing bodies; the storage methods below return real temp files.
package com.omnillm.android.runtimeservice.security;
import android.content.Context;
import java.io.File;
public class TestContext extends Context {
    public final File filesDir;
    public final File noBackupDir;
    public final File cacheDir;
    public final File dbDir;
    public TestContext(File filesDir, File noBackupDir, File cacheDir, File dbDir) {
        this.filesDir = filesDir;
        this.noBackupDir = noBackupDir;
        this.cacheDir = cacheDir;
        this.dbDir = dbDir;
    }
    @Override public boolean bindService(android.content.Intent p0, android.content.ServiceConnection p1, int p2) { throw new RuntimeException("stub"); }
    @Override public int checkCallingOrSelfPermission(java.lang.String p0) { throw new RuntimeException("stub"); }
    @Override public int checkCallingOrSelfUriPermission(android.net.Uri p0, int p1) { throw new RuntimeException("stub"); }
    @Override public int checkCallingPermission(java.lang.String p0) { throw new RuntimeException("stub"); }
    @Override public int checkCallingUriPermission(android.net.Uri p0, int p1) { throw new RuntimeException("stub"); }
    @Override public int checkPermission(java.lang.String p0, int p1, int p2) { throw new RuntimeException("stub"); }
    @Override public int checkSelfPermission(java.lang.String p0) { throw new RuntimeException("stub"); }
    @Override public int checkUriPermission(android.net.Uri p0, int p1, int p2, int p3) { throw new RuntimeException("stub"); }
    @Override public int checkUriPermission(android.net.Uri p0, java.lang.String p1, java.lang.String p2, int p3, int p4, int p5) { throw new RuntimeException("stub"); }
    @Override public void clearWallpaper() { throw new RuntimeException("stub"); }
    @Override public android.content.Context createConfigurationContext(android.content.res.Configuration p0) { throw new RuntimeException("stub"); }
    @Override public android.content.Context createContextForSplit(java.lang.String p0) { throw new RuntimeException("stub"); }
    @Override public android.content.Context createDeviceProtectedStorageContext() { throw new RuntimeException("stub"); }
    @Override public android.content.Context createDisplayContext(android.view.Display p0) { throw new RuntimeException("stub"); }
    @Override public android.content.Context createPackageContext(java.lang.String p0, int p1) { throw new RuntimeException("stub"); }
    @Override public java.lang.String[] databaseList() { throw new RuntimeException("stub"); }
    @Override public boolean deleteDatabase(java.lang.String p0) { throw new RuntimeException("stub"); }
    @Override public boolean deleteFile(java.lang.String p0) { throw new RuntimeException("stub"); }
    @Override public boolean deleteSharedPreferences(java.lang.String p0) { throw new RuntimeException("stub"); }
    @Override public void enforceCallingOrSelfPermission(java.lang.String p0, java.lang.String p1) { throw new RuntimeException("stub"); }
    @Override public void enforceCallingOrSelfUriPermission(android.net.Uri p0, int p1, java.lang.String p2) { throw new RuntimeException("stub"); }
    @Override public void enforceCallingPermission(java.lang.String p0, java.lang.String p1) { throw new RuntimeException("stub"); }
    @Override public void enforceCallingUriPermission(android.net.Uri p0, int p1, java.lang.String p2) { throw new RuntimeException("stub"); }
    @Override public void enforcePermission(java.lang.String p0, int p1, int p2, java.lang.String p3) { throw new RuntimeException("stub"); }
    @Override public void enforceUriPermission(android.net.Uri p0, int p1, int p2, int p3, java.lang.String p4) { throw new RuntimeException("stub"); }
    @Override public void enforceUriPermission(android.net.Uri p0, java.lang.String p1, java.lang.String p2, int p3, int p4, int p5, java.lang.String p6) { throw new RuntimeException("stub"); }
    @Override public java.lang.String[] fileList() { throw new RuntimeException("stub"); }
    @Override public android.content.Context getApplicationContext() { return this; }
    @Override public android.content.pm.ApplicationInfo getApplicationInfo() { return new android.content.pm.ApplicationInfo(); }
    @Override public android.content.res.AssetManager getAssets() { throw new RuntimeException("stub"); }
    @Override public java.io.File getCacheDir() { return cacheDir; }
    @Override public java.lang.ClassLoader getClassLoader() { throw new RuntimeException("stub"); }
    @Override public java.io.File getCodeCacheDir() { throw new RuntimeException("stub"); }
    @Override public android.content.ContentResolver getContentResolver() { throw new RuntimeException("stub"); }
    @Override public java.io.File getDataDir() { throw new RuntimeException("stub"); }
    @Override public java.io.File getDatabasePath(String p0) { return new File(dbDir, p0); }
    @Override public java.io.File getDir(java.lang.String p0, int p1) { throw new RuntimeException("stub"); }
    @Override public java.io.File getExternalCacheDir() { throw new RuntimeException("stub"); }
    @Override public java.io.File[] getExternalCacheDirs() { throw new RuntimeException("stub"); }
    @Override public java.io.File getExternalFilesDir(java.lang.String p0) { throw new RuntimeException("stub"); }
    @Override public java.io.File[] getExternalFilesDirs(java.lang.String p0) { throw new RuntimeException("stub"); }
    @Override public java.io.File[] getExternalMediaDirs() { throw new RuntimeException("stub"); }
    @Override public java.io.File getFileStreamPath(java.lang.String p0) { throw new RuntimeException("stub"); }
    @Override public java.io.File getFilesDir() { return filesDir; }
    @Override public android.os.Looper getMainLooper() { throw new RuntimeException("stub"); }
    @Override public java.io.File getNoBackupFilesDir() { return noBackupDir; }
    @Override public java.io.File getObbDir() { throw new RuntimeException("stub"); }
    @Override public java.io.File[] getObbDirs() { throw new RuntimeException("stub"); }
    @Override public java.lang.String getPackageCodePath() { throw new RuntimeException("stub"); }
    @Override public android.content.pm.PackageManager getPackageManager() { throw new RuntimeException("stub"); }
    @Override public java.lang.String getPackageName() { return "com.omnillm.test"; }
    @Override public java.lang.String getPackageResourcePath() { throw new RuntimeException("stub"); }
    @Override public android.content.res.Resources getResources() { throw new RuntimeException("stub"); }
    @Override public android.content.SharedPreferences getSharedPreferences(java.lang.String p0, int p1) { throw new RuntimeException("stub"); }
    @Override public java.lang.Object getSystemService(java.lang.String p0) { throw new RuntimeException("stub"); }
    @Override public java.lang.String getSystemServiceName(java.lang.Class<?> p0) { throw new RuntimeException("stub"); }
    @Override public android.content.res.Resources.Theme getTheme() { throw new RuntimeException("stub"); }
    @Override public android.graphics.drawable.Drawable getWallpaper() { throw new RuntimeException("stub"); }
    @Override public int getWallpaperDesiredMinimumHeight() { throw new RuntimeException("stub"); }
    @Override public int getWallpaperDesiredMinimumWidth() { throw new RuntimeException("stub"); }
    @Override public void grantUriPermission(java.lang.String p0, android.net.Uri p1, int p2) { throw new RuntimeException("stub"); }
    @Override public boolean isDeviceProtectedStorage() { throw new RuntimeException("stub"); }
    @Override public boolean moveDatabaseFrom(android.content.Context p0, java.lang.String p1) { throw new RuntimeException("stub"); }
    @Override public boolean moveSharedPreferencesFrom(android.content.Context p0, java.lang.String p1) { throw new RuntimeException("stub"); }
    @Override public java.io.FileInputStream openFileInput(java.lang.String p0) { throw new RuntimeException("stub"); }
    @Override public java.io.FileOutputStream openFileOutput(java.lang.String p0, int p1) { throw new RuntimeException("stub"); }
    @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(java.lang.String p0, int p1, android.database.sqlite.SQLiteDatabase.CursorFactory p2) { throw new RuntimeException("stub"); }
    @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(java.lang.String p0, int p1, android.database.sqlite.SQLiteDatabase.CursorFactory p2, android.database.DatabaseErrorHandler p3) { throw new RuntimeException("stub"); }
    @Override public android.graphics.drawable.Drawable peekWallpaper() { throw new RuntimeException("stub"); }
    @Override public android.content.Intent registerReceiver(android.content.BroadcastReceiver p0, android.content.IntentFilter p1) { throw new RuntimeException("stub"); }
    @Override public android.content.Intent registerReceiver(android.content.BroadcastReceiver p0, android.content.IntentFilter p1, int p2) { throw new RuntimeException("stub"); }
    @Override public android.content.Intent registerReceiver(android.content.BroadcastReceiver p0, android.content.IntentFilter p1, java.lang.String p2, android.os.Handler p3) { throw new RuntimeException("stub"); }
    @Override public android.content.Intent registerReceiver(android.content.BroadcastReceiver p0, android.content.IntentFilter p1, java.lang.String p2, android.os.Handler p3, int p4) { throw new RuntimeException("stub"); }
    @Override public void removeStickyBroadcast(android.content.Intent p0) { throw new RuntimeException("stub"); }
    @Override public void removeStickyBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1) { throw new RuntimeException("stub"); }
    @Override public void revokeUriPermission(android.net.Uri p0, int p1) { throw new RuntimeException("stub"); }
    @Override public void revokeUriPermission(java.lang.String p0, android.net.Uri p1, int p2) { throw new RuntimeException("stub"); }
    @Override public void sendBroadcast(android.content.Intent p0) { throw new RuntimeException("stub"); }
    @Override public void sendBroadcast(android.content.Intent p0, java.lang.String p1) { throw new RuntimeException("stub"); }
    @Override public void sendBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1) { throw new RuntimeException("stub"); }
    @Override public void sendBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1, java.lang.String p2) { throw new RuntimeException("stub"); }
    @Override public void sendOrderedBroadcast(android.content.Intent p0, java.lang.String p1) { throw new RuntimeException("stub"); }
    @Override public void sendOrderedBroadcast(android.content.Intent p0, java.lang.String p1, android.content.BroadcastReceiver p2, android.os.Handler p3, int p4, java.lang.String p5, android.os.Bundle p6) { throw new RuntimeException("stub"); }
    @Override public void sendOrderedBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1, java.lang.String p2, android.content.BroadcastReceiver p3, android.os.Handler p4, int p5, java.lang.String p6, android.os.Bundle p7) { throw new RuntimeException("stub"); }
    @Override public void sendStickyBroadcast(android.content.Intent p0) { throw new RuntimeException("stub"); }
    @Override public void sendStickyBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1) { throw new RuntimeException("stub"); }
    @Override public void sendStickyOrderedBroadcast(android.content.Intent p0, android.content.BroadcastReceiver p1, android.os.Handler p2, int p3, java.lang.String p4, android.os.Bundle p5) { throw new RuntimeException("stub"); }
    @Override public void sendStickyOrderedBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1, android.content.BroadcastReceiver p2, android.os.Handler p3, int p4, java.lang.String p5, android.os.Bundle p6) { throw new RuntimeException("stub"); }
    @Override public void setTheme(int p0) { throw new RuntimeException("stub"); }
    @Override public void setWallpaper(android.graphics.Bitmap p0) { throw new RuntimeException("stub"); }
    @Override public void setWallpaper(java.io.InputStream p0) { throw new RuntimeException("stub"); }
    @Override public void startActivities(android.content.Intent[] p0) { throw new RuntimeException("stub"); }
    @Override public void startActivities(android.content.Intent[] p0, android.os.Bundle p1) { throw new RuntimeException("stub"); }
    @Override public void startActivity(android.content.Intent p0) { throw new RuntimeException("stub"); }
    @Override public void startActivity(android.content.Intent p0, android.os.Bundle p1) { throw new RuntimeException("stub"); }
    @Override public android.content.ComponentName startForegroundService(android.content.Intent p0) { throw new RuntimeException("stub"); }
    @Override public boolean startInstrumentation(android.content.ComponentName p0, java.lang.String p1, android.os.Bundle p2) { throw new RuntimeException("stub"); }
    @Override public void startIntentSender(android.content.IntentSender p0, android.content.Intent p1, int p2, int p3, int p4) { throw new RuntimeException("stub"); }
    @Override public void startIntentSender(android.content.IntentSender p0, android.content.Intent p1, int p2, int p3, int p4, android.os.Bundle p5) { throw new RuntimeException("stub"); }
    @Override public android.content.ComponentName startService(android.content.Intent p0) { throw new RuntimeException("stub"); }
    @Override public boolean stopService(android.content.Intent p0) { throw new RuntimeException("stub"); }
    @Override public void unbindService(android.content.ServiceConnection p0) { throw new RuntimeException("stub"); }
    @Override public void unregisterReceiver(android.content.BroadcastReceiver p0) { throw new RuntimeException("stub"); }
}
