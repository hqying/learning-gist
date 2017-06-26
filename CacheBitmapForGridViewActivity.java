package com.wordpress.jlvivit.androidtrainingdemo;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.util.LruCache;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.GridLayoutManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class CacheBitmapForGridViewActivity extends AppCompatActivity {

    private static final String LOG_TAG = CacheBitmapForGridViewActivity.class.getSimpleName();

    private GridView imgGridView;
    private int mThumbnailSize;

    // 1. memory cache
    private LruCache<String, BitmapDrawable> mMemoryCache;

    // 2. disk cache
    private DiskLruCache mDiskLruCache;
    private final Object mDiskLruCacheLock = new Object();
    private boolean mDiskLruCacheStarting = true;
    private static final int DISK_CACHE_SIZE = 1024 * 1024 * 10; // 10Mb
    private static final String DISK_CACHE_THUMBS_SUBDIR = "thumbnails";
    private static final int DISK_CACHE_INDEX = 0;

    private static final int HTTP_CACHE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final String HTTP_CACHE_SUBDIR = "http";
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private File mHttpCacheDir;
    private DiskLruCache mHttpDiskLruCache;
    private final Object mHttpDiskCacheLock = new Object();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cache_bitmap_for_grid_view);

        mThumbnailSize = getResources().getDimensionPixelSize(R.dimen.thumbnail_size);
        imgGridView = (GridView) findViewById(R.id.img_gridview);

//        imgGridView.setAdapter(new ImageResAdapter(this));
//        imgGridView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
//            @Override
//            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
//                Toast.makeText(parent.getContext(), "position " + position, Toast.LENGTH_LONG).show();
//            }
//        });

        imgGridView.setAdapter(new ImageThumbnailUrlAdapter(this));

        // init memory cache
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int memoryCacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, BitmapDrawable>(memoryCacheSize) {
            @Override
            protected int sizeOf(String key, BitmapDrawable value) {
                return value.getBitmap().getByteCount() / 1024;  // cache size measured in kb instead of numOfItems
            }
        };
//
//        // init disk cache
//        try {
//            mDiskLruCache = DiskLruCache.open(getDiskCacheDir(this), 1, 1, DISK_CACHE_SIZE);
//        } catch (IOException e) {
//            e.printStackTrace();
//        }

//        AlertDialog dialog = new AlertDialog.Builder(this)

        // init http cache
        mHttpCacheDir = getDiskCacheDir(this, HTTP_CACHE_SUBDIR);
        try {
            mHttpDiskLruCache = DiskLruCache.open(mHttpCacheDir, 1, 1, HTTP_CACHE_SIZE);
        } catch (IOException e) {
            e.printStackTrace();
        }
        Log.d(LOG_TAG, "onCreate finished");
    }

    private File getDiskCacheDir(Context context, String dirName) {
        final String cacheDirPath = (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED) || !Environment.isExternalStorageRemovable()) ?
                context.getExternalCacheDir().getPath() : context.getCacheDir().getPath();
        return new File(cacheDirPath + File.separator + dirName);
    }

    /*
     * In this example, one eighth of the application memory is allocated for our cache. On a
     * normal/hdpi device this is a minimum of around 4MB (32/8). A full screen GridView filled
     * with images on a device with 800x480 resolution would use around 1.5MB (800*480*4 bytes),
     * so this would cache a minimum of around 2.5 pages of images in memory.
     */
    public void addBitmapToCache(String urlStr, BitmapDrawable bitmapDrawable) {
        if (urlStr == null || bitmapDrawable == null) {
            return;
        }

        if (mMemoryCache != null) {
            mMemoryCache.put(urlStr, bitmapDrawable);
        }

//        synchronized (mDiskLruCacheLock) {
//            if (mDiskLruCache != null) {
//                final String key = hashKeyForDisk(urlStr);
//                OutputStream out = null;
//                try {
//                    DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
//                    if (snapshot == null) {
//                        final DiskLruCache.Editor editor = mDiskLruCache.edit(key);
//                        if (editor != null) {
//                            out = editor.newOutputStream(DISK_CACHE_INDEX);
//                            bitmapDrawable.getBitmap().compress(Bitmap.CompressFormat.JPEG, 70, out);
//                            editor.commit();
//                        }
//                    } else {
//                        snapshot.getInputStream(DISK_CACHE_INDEX).close();
//                    }
//                } catch (IOException e) {
//                    e.printStackTrace();
//                } finally {
//                    if (out != null) {
//                        try {
//                            out.close();
//                        } catch (IOException e) {
//                            e.printStackTrace();
//                        }
//                    }
//                }
//            }
//        }
    }

    private String hashKeyForDisk(String key) {
        String cacheKey;
        try {
            MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(key.getBytes());
            StringBuilder stringBuilder = new StringBuilder();
            byte[] digest = mDigest.digest();
            for (int i = 0; i < digest.length; i++) {
                String hex = Integer.toHexString(digest[i]);
                if (hex.length() == 1) {
                    stringBuilder.append("0");
                }
                stringBuilder.append(hex);
            }
            cacheKey = stringBuilder.toString();
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(key.hashCode());
        }
        return cacheKey;
    }

    class InitDiskCacheTask extends AsyncTask<File, Void, Void> {

        @Override
        protected Void doInBackground(File... params) {
            synchronized (mDiskLruCacheLock) {
                try {
                    mDiskLruCache = DiskLruCache.open(params[0], 1, 1, DISK_CACHE_SIZE);
                    mDiskLruCacheStarting = false;
                    mDiskLruCacheLock.notifyAll();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }
    }

    private void loadImage(String url, ImageView imageView) {
        BitmapDrawable bitmapDrawable = null;
        if (url == null) {
            return;
        }
        if (mMemoryCache != null) {
            bitmapDrawable = mMemoryCache.get(url);
        }
        if (bitmapDrawable != null) {
            imageView.setImageDrawable(bitmapDrawable);
        } else { // TODO: else if
            // task for disk cache or network call
            BitmapWorkerTask task = new BitmapWorkerTask(this, imageView);
            task.execute(url);
        }
    }

    class BitmapWorkerTask extends AsyncTask<String, Void, BitmapDrawable> {
        private static final int IO_BUFFER_SIZE = 8 * 1024;
        private Context mContext;
        private ImageView mImageView;

        public BitmapWorkerTask(Context context, ImageView imageView) {
            mContext = context;
            mImageView = imageView;
        }

        @Override
        protected BitmapDrawable doInBackground(String... params) {
            final String urlStr = params[0];
            final String key = hashKeyForDisk(urlStr);
//            if () { // hit in disk cache
//                // inputStream from disk cache
//            } else {
                // TODO: now, no disk cache even for http. BUT THERE NEED TO BE
                DiskLruCache.Snapshot snapshot;
                FileDescriptor fd = null;
                FileInputStream fileInputStream = null;
                synchronized (mHttpDiskCacheLock) {
                    if (mHttpDiskLruCache != null) {
                        try {
                            snapshot = mHttpDiskLruCache.get(key);
                            if (snapshot == null) {
                                DiskLruCache.Editor editor = mHttpDiskLruCache.edit(key);
                                if (editor != null) {
                                    if (!Utility.checkConnection(mContext)) {
                                        Log.d(LOG_TAG, "No network connection");
                                        return null;
                                    }
                                    if (downloadUrlToOutputStream(urlStr, editor.newOutputStream(DISK_CACHE_INDEX))) {
                                        editor.commit();
                                    } else {
                                        editor.abort();
                                    }
                                }
                                snapshot = mHttpDiskLruCache.get(key);
                            }
                            if (snapshot != null) {
                                fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
                                fd = fileInputStream.getFD();
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        } finally {
                            if (fd == null && fileInputStream != null) {
                                try {
                                    fileInputStream.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        }

                    }
                }
                Bitmap bitmap = null;
                if (fd != null) {
                    bitmap = decodeSampledBitmapFromFd(fd, mThumbnailSize, mThumbnailSize);
                }
                if (fileInputStream != null) {
                    try {
                        fileInputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                BitmapDrawable bitmapDrawable = new BitmapDrawable(mContext.getResources(), bitmap);
                try {
                    mHttpDiskLruCache.remove(key);
                } catch (IOException e) {
                    e.printStackTrace();
                }
//            }
//            if (mMemoryCache != null) {
            if (mMemoryCache != null) {
                addBitmapToCache(urlStr, bitmapDrawable);
            }
            return bitmapDrawable;
        }

        private boolean downloadUrlToOutputStream(String urlStr, OutputStream outputStream) {
            HttpURLConnection urlConnection = null;
            BufferedInputStream in = null;
            BufferedOutputStream out = null;
            try {
                final URL url = new URL(urlStr);
                urlConnection = (HttpURLConnection) url.openConnection();
                in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
                out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);  // TODO: USE PARAM
                int b;
                while ((b = in.read()) != -1) {
                    out.write(b);
                }
                return true;
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            return false;
        }

        private Bitmap decodeSampledBitmapFromFd(FileDescriptor fd, int desWidth, int desHeight) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(fd, null, options);
            int srcWidth = options.outWidth, srcHeight = options.outHeight;

            options.inSampleSize = Utility.calculateInSampleSize(srcWidth, srcHeight, desWidth, desHeight);
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFileDescriptor(fd, null, options);
        }

        @Override
        protected void onPreExecute() {
            mImageView.setImageDrawable(mContext.getResources().getDrawable(R.drawable.empty_drawable));
        }

        @Override
        protected void onPostExecute(BitmapDrawable bitmapDrawable) {
            if (bitmapDrawable != null) {
                mImageView.setImageDrawable(bitmapDrawable);
            }
        }
    }

    private class ImageThumbnailUrlAdapter extends BaseAdapter {

        private Context mContext;

        public ImageThumbnailUrlAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getCount() {
            return Images.imageThumbUrls.length;
        }

        @Override
        public Object getItem(int position) {
            return Images.imageThumbUrls[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView imageView;
            if (convertView == null) {
                imageView = new ImageView(mContext);
            } else {
                imageView = (ImageView) convertView;
            }
            loadImage(Images.imageThumbUrls[position], imageView);
            return imageView;
        }
    }

    private class ImageResAdapter extends BaseAdapter {
        private Context mContext;
        private Integer[] mThumbnailsId = Images.images2ResIds;

        public ImageResAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getCount() {
            return mThumbnailsId.length;
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView imageView;
            if (convertView == null) {
                imageView = new ImageView(mContext);
//                imageView.setLayoutParams(new GridLayoutManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                imageView.setLayoutParams(new GridLayoutManager.LayoutParams(300, 300));
//                imageView.setAdjustViewBounds(true);
            } else {
                imageView = (ImageView) convertView;
            }
//            imageView.setImageResource(mThumbnailsId[position]);
            loadBitmapFromRes(mThumbnailsId[position], imageView);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageView.setPadding(8, 8, 8, 8);
            return imageView;
        }
    }

    private class BitmapResWorkerTask extends AsyncTask<Integer, Void, BitmapDrawable> {

        private final String LOG_TAG = BitmapResWorkerTask.class.getSimpleName();
        private Resources mResources;
        private ImageView mImageView;

        public BitmapResWorkerTask(Context context, ImageView imageView) {
            mResources = context.getResources();
            mImageView = imageView;
        }

        @Override
        protected BitmapDrawable doInBackground(Integer... params) {
            Bitmap bitmap = decodeSampledBitmapFromResource(getResources(), params[0], 100, 100);
            BitmapDrawable drawable = new BitmapDrawable(mResources, bitmap);
            addBitmapToCache(String.valueOf(params[0]), drawable);
            Log.d(LOG_TAG, bitmap.getWidth() + "*" + bitmap.getHeight());
            return drawable;
        }

        private Bitmap decodeSampledBitmapFromResource(Resources resources, int resId, int desWidth, int desHeight) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(getResources(), resId, options);
            int srcWidth = options.outWidth, srcHeight = options.outHeight;

            options.inSampleSize = Utility.calculateInSampleSize(srcWidth, srcHeight, desWidth, desHeight);
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeResource(resources, resId, options);
        }

        @Override
        protected void onPostExecute(BitmapDrawable bitmapDrawable) {
            mImageView.setImageDrawable(bitmapDrawable);
        }
    }

    public void loadBitmapFromRes(int resId, ImageView imageView) {
        final String imageKey = String.valueOf(resId);
        final BitmapDrawable bitmapDrawable = mMemoryCache.get(imageKey);
        if (bitmapDrawable != null) {
            imageView.setImageBitmap(bitmapDrawable.getBitmap());
        } else {
            BitmapResWorkerTask task = new BitmapResWorkerTask(this, imageView);
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, resId);
        }
    }
}
