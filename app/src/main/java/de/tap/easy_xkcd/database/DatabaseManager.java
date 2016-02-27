package de.tap.easy_xkcd.database;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.util.Log;

import com.tap.xkcd_reader.R;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

import de.tap.easy_xkcd.Activities.MainActivity;
import de.tap.easy_xkcd.Activities.SearchResultsActivity;
import de.tap.easy_xkcd.fragments.OverviewBaseFragment;
import de.tap.easy_xkcd.fragments.WhatIfFragment;
import de.tap.easy_xkcd.utils.Comic;
import de.tap.easy_xkcd.utils.PrefHelper;
import io.realm.Realm;
import io.realm.RealmResults;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;

public class DatabaseManager {

    private Context context;
    public Realm realm;
    private static final String REALM_DATABASE_LOADED = "pref_realm_database_loaded";
    private static final String HIGHEST_DATABASE = "highest_database";
    private static final String WHATIF_DATABASE_LOADED = "whatif_database_loaded";
    private static final String HIGHEST_WHATIF_DATABASE = "highest_whatif_database";
    private static final String OFFLINE_WHATIF_OVERVIEW_PATH = "/easy xkcd/what if/overview";
    private static final String OFFLINE_WHATIF_PATH = "/easy xkcd/what if/";

    private static final String COMIC_READ = "comic_read";
    private static final String FAVORITES_MOVED = "fav_moved";
    private static final String FAVORITES = "favorites";


    public DatabaseManager(Context context) {
        this.context = context;
        realm = Realm.getInstance(context);
    }

    private SharedPreferences getSharedPrefs() {
        return context.getSharedPreferences("MainActivity", Activity.MODE_PRIVATE);
    }

    /////////////////////// FAVORITES /////////////////////////////////////////

    public boolean noFavorites() {
        return getSharedPrefs().getString(FAVORITES, null) == null;
    }

    public int[] getFavComics() {
        String fs = getSharedPrefs().getString(FAVORITES, null);
        if (fs == null)
            return null;
        String[] f = fs.split(",");
        int[] fav = new int[f.length];
        for (int i = 0; i < f.length; i++)
            fav[i] = Integer.parseInt(f[i]);
        Arrays.sort(fav);
        return fav;
    }

    public boolean checkFavorite(int fav) {
        return getFavComics() != null && Arrays.binarySearch(getFavComics(), fav) >= 0;
    }

    public void setFavorite(int fav, boolean isFav) {
        if (fav <= getHighestInDatabase()) {
            realm.beginTransaction();
            RealmComic comic = realm.where(RealmComic.class).equalTo("comicNumber", fav).findFirst();
            comic.setFavorite(isFav);
            realm.copyToRealmOrUpdate(comic);
            realm.commitTransaction();
        }
        if (isFav)
            addFavorite(fav);
        else
            removeFavorite(fav);
    }

    private void addFavorite(int fav) {
        String favorites = getSharedPrefs().getString(FAVORITES, null);
        if (favorites == null)
            favorites = String.valueOf(fav);
        else
            favorites += "," + String.valueOf(fav);
        getSharedPrefs().edit().putString(FAVORITES, favorites).apply();
    }

    private void removeFavorite(int favToRemove) {
        int[] old = getFavComics();

        int a = Arrays.binarySearch(old, favToRemove);
        int[] out = new int[old.length - 1];
        if (out.length != 0 && a >= 0) {
            System.arraycopy(old, 0, out, 0, a);
            System.arraycopy(old, a + 1, out, a, out.length - a);
            StringBuilder sb = new StringBuilder();
            sb.append(out[0]);
            for (int i = 1; i < out.length; i++) {
                sb.append(",");
                sb.append(out[i]);
            }
            getSharedPrefs().edit().putString(FAVORITES, sb.toString()).apply();
        } else {
            getSharedPrefs().edit().putString(FAVORITES, null).apply();
        }
    }

    public void removeAllFavorites() {
        getSharedPrefs().edit().putString(FAVORITES, null).apply();
    }

    public void moveFavorites(Activity activity) {
        //Move the favorites list so that it can be accessed outside of MainActivity
        if (!getSharedPrefs().getBoolean(FAVORITES_MOVED, false)) {
            String fav = activity.getPreferences(Activity.MODE_PRIVATE).getString("favorites", null);
            getSharedPrefs().edit().putString(FAVORITES, fav).putBoolean(FAVORITES_MOVED, true).apply();
            Log.d("prefHelper", "moved favorites");
        }
    }

    ///////////////// READ COMICS //////////////////////////////////////////

    public void setRead(int number, boolean isRead) {
        if (number <= getHighestInDatabase()) {
            realm.beginTransaction();
            RealmComic comic = realm.where(RealmComic.class).equalTo("comicNumber", number).findFirst();
            comic.setRead(isRead);
            realm.copyToRealmOrUpdate(comic);
            realm.commitTransaction();
        } else {
            String read = getSharedPrefs().getString(COMIC_READ, "");
            if (!read.equals("")) {
                read = read + "," + String.valueOf(number);
            } else {
                read = String.valueOf(number);
            }
            getSharedPrefs().edit().putString(COMIC_READ, read).apply();
        }
    }

    public int[] getReadComics() {
        String[] r = getSharedPrefs().getString(COMIC_READ, "").split(",");
        int[] read = new int[r.length];
        for (int i = 0; i < r.length; i++)
            read[i] = Integer.parseInt(r[i]);
        Arrays.sort(read);
        return read;
    }

    public int getNextUnread(int number, RealmResults<RealmComic> comics) {
        RealmComic comic;
        try {
            comic = comics.where().greaterThan("comicNumber", number).equalTo("isRead", false).findAll().last();
        } catch (IndexOutOfBoundsException e) {
            Log.e("top of list reached", e.getMessage());
            try {
                comic = comics.where().lessThan("comicNumber", number).equalTo("isRead", false).findAll().first();
            } catch (IndexOutOfBoundsException e2) {
                Log.e("all comics read", e2.getMessage());
                return 1;
            }
        }
        return comic.getComicNumber();
    }

    //////////////// COMIC DATABASE //////////////////////////////////////////

    public class updateComicDatabase extends AsyncTask<Void, Integer, Void> {
        private ProgressDialog progress;
        private SearchResultsActivity activity;
        private OverviewBaseFragment fragment;
        private PrefHelper prefHelper;

        public updateComicDatabase(SearchResultsActivity activity, OverviewBaseFragment fragment, PrefHelper prefHelper) {
            this.activity = activity;
            this.fragment = fragment;
            this.prefHelper = prefHelper;
        }

        @Override
        protected void onPreExecute() {
            progress = new ProgressDialog(context);
            progress.setTitle(context.getResources().getString(R.string.update_database));
            progress.setIndeterminate(false);
            progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progress.setCancelable(false);
            progress.show();
        }

        @Override
        protected Void doInBackground(Void... params) {
            Realm realm = Realm.getInstance(context);
            int[] read = getReadComics();
            int[] fav = getFavComics();
            if (!databaseLoaded()) {
                String[] titles = getFile(R.raw.comic_titles).split("&&");
                String[] trans = getFile(R.raw.comic_trans).split("&&");
                String[] urls = getFile(R.raw.comic_urls).split("&&");
                realm.beginTransaction();
                for (int i = 0; i < 1645; i++) {
                    RealmComic comic = realm.createObject(RealmComic.class);
                    comic.setComicNumber(i + 1);
                    comic.setTitle(titles[i]);
                    comic.setTranscript(trans[i]);
                    comic.setUrl(urls[i]);
                    comic.setRead(read != null && Arrays.binarySearch(read, i + 1) >= 0);
                    comic.setFavorite(fav != null && Arrays.binarySearch(fav, i + 1) >= 0);
                }
                realm.commitTransaction();
                setHighestInDatabase(1645);
            }
            if (prefHelper.isOnline(context)) {
                int newest;
                try {
                    Comic comic = new Comic(0);
                    newest = comic.getComicNumber();
                } catch (IOException e) {
                    newest = prefHelper.getNewest();
                }
                realm.beginTransaction();
                int highest = getHighestInDatabase() + 1;
                for (int i = highest; i <= newest; i++) {
                    try {
                        Comic comic = new Comic(i);
                        RealmComic realmComic = realm.createObject(RealmComic.class);
                        realmComic.setComicNumber(i);
                        realmComic.setTitle(comic.getComicData()[0]);
                        realmComic.setTranscript(comic.getTranscript());
                        realmComic.setUrl(comic.getComicData()[2]);
                        realmComic.setRead(read != null && Arrays.binarySearch(read, i) >= 0);
                        realmComic.setFavorite(fav != null && Arrays.binarySearch(fav, i + 1) >= 0);
                        float x = newest - highest;
                        int y = i - highest;
                        int p = (int) ((y / x) * 100);
                        publishProgress(p);
                    } catch (IOException e) {
                        Log.d("error at " + i, e.getMessage());
                    }
                }
                realm.commitTransaction();
                setHighestInDatabase(newest);
            }
            setDatabaseLoaded(true);
            realm.close();
            return null;
        }

        private String getFile(int rawId) {
            InputStream is = context.getResources().openRawResource(rawId);
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            try {
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            } catch (IOException e) {
                Log.e("error:", e.getMessage());
            }
            return sb.toString();
        }

        protected void onProgressUpdate(Integer... pro) {
            progress.setProgress(pro[0]);
        }

        @Override
        protected void onPostExecute(Void dummy) {
            progress.dismiss();
            if (activity != null)
                activity.updateDatabasePostExecute();
            else
                fragment.updateDatabasePostExecute();
        }
    }

    public int getRandomUnread() {
        RealmResults<RealmComic> unread = realm.where(RealmComic.class).equalTo("isRead", false).findAll();
        int n = new Random().nextInt(unread.size())+1;
        return unread.get(n).getComicNumber();
    }

    public int getHighestInDatabase() {
        return getSharedPrefs().getInt(HIGHEST_DATABASE, 1);
    }

    public void setHighestInDatabase(int i) {
        getSharedPrefs().edit().putInt(HIGHEST_DATABASE, i).apply();
    }

    public boolean databaseLoaded() {
        return getSharedPrefs().getBoolean(REALM_DATABASE_LOADED, false);
    }

    public void setDatabaseLoaded(boolean loaded) {
        getSharedPrefs().edit().putBoolean(REALM_DATABASE_LOADED, loaded).apply();
    }

    ////////////////// WHAT IF DATABASE /////////////////////////

    public int getWhatIfMissingThumbnailId(String title) {
        switch (title) {
            case "Jupiter Descending":
                return R.mipmap.jupiter_descending;
            case "Jupiter Submarine":
                return R.mipmap.jupiter_submarine;
            case "New Horizons":
                return R.mipmap.new_horizons;
            case "Proton Earth, Electron Moon":
                return R.mipmap.proton_earth;
            case "Sunbeam":
                return R.mipmap.sun_beam;
            case "Space Jetta":
                return R.mipmap.jetta;
            case "Europa Water Siphon":
                return R.mipmap.straw;
            case "Saliva Pool":
                return R.mipmap.question;
            case "Fire From Moonlight":
                return R.mipmap.rabbit;
            case "Stop Jupiter":
                return R.mipmap.burlap;
            default:
                return 0;
        }
    }
}
