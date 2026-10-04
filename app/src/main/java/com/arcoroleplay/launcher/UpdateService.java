package com.arcoroleplay.launcher;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.downloader.Error;
import com.downloader.OnDownloadListener;
import com.downloader.OnProgressListener;
import com.downloader.PRDownloader;
import com.downloader.PRDownloaderConfig;
import com.downloader.Progress;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.joom.paranoid.Obfuscate;
import com.arcoroleplay.launcher.util.Util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;

@Obfuscate
public class UpdateService extends Service {

    private static final String TAG = "UpdateService";

    /*
     * ============================================================
     * CONFIGURAÇÃO DO CACHE DO JOGO
     * ============================================================
     *
     * Este é o mesmo caminho utilizado pelo C++:
     *
     * /storage/emulated/0/Android/media/com.arcoroleplay/
     *
     */

    private static final String GAME_ROOT =
            "/storage/emulated/0/Android/media/com.arcoroleplay/";

    /*
     * URL principal do servidor.
     *
     * Se estiver rodando o servidor Python no próprio celular:
     * http://127.0.0.1:8080/
     *
     * Se estiver em outro servidor, altere aqui.
     */
    private static final String SERVER_URL =
            "http://127.0.0.1:8080/";

    /*
     * JSON principal.
     */
    private static final String CLIENT_JSON_URL =
            SERVER_URL + "client.json";


    Messenger mMessenger;
    Messenger mActivityMessenger;
    IncomingHandler mInHandler;

    public UpdateActivity.GameStatus mGameStatus =
            UpdateActivity.GameStatus.Unknown;

    public UpdateActivity.UpdateStatus mUpdateStatus =
            UpdateActivity.UpdateStatus.Undefined;

    public boolean mDownloadingStatus = false;

    public long mUpdateGameDataSize = 0;
    public long mUpdateGameDataSizeUpdated = 0;

    public String mUpdateGameURL = SERVER_URL;

    public int mUpdateVersion;

    public ArrayList<String> mUpdateFiles;
    public ArrayList<String> mUpdateFilesName;
    public ArrayList<Long> mUpdateFilesSize;
    public ArrayList<String> mUpdateFilesURL;

    public int mGpuType = 0;


    @Override
    public void onCreate() {
        super.onCreate();

        try {
            FirebaseRemoteConfig.getInstance().fetchAndActivate();
        } catch (Exception e) {
            Log.e(TAG, "Firebase RemoteConfig error", e);
        }

        /*
         * Garante que a pasta principal do jogo exista.
         */
        createGameRoot();

        HandlerThread handlerThread =
                new HandlerThread("ServiceStartArguments", 10);

        handlerThread.start();

        PRDownloader.initialize(
                getApplicationContext(),
                PRDownloaderConfig
                        .newBuilder()
                        .setDatabaseEnabled(true)
                        .setReadTimeout(30000)
                        .setConnectTimeout(30000)
                        .build()
        );

        mInHandler =
                new IncomingHandler(handlerThread.getLooper());

        mMessenger =
                new Messenger(mInHandler);
    }


    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }


    /*
     * ============================================================
     * HANDLER
     * ============================================================
     */

    class IncomingHandler extends Handler {

        public IncomingHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {

            if (msg.replyTo != null) {
                mActivityMessenger = msg.replyTo;
            }

            Message obtain;
            Messenger messenger;

            if (msg.what == 0) {

                mGpuType =
                        msg.getData().getInt("gputype");

                if (mGpuType == 0) {
                    Log.e(TAG, "GPU not found");
                    return;
                }

                startUpdating();

            } else if (msg.what == 1) {

                startGameUpdateChecking();

            } else if (msg.what == 2) {

                updateGame();

            } else if (msg.what == 4) {

                obtain =
                        Message.obtain(mInHandler, 4);

                obtain.getData().putString(
                        "status",
                        mUpdateStatus.name()
                );

                obtain.replyTo = mMessenger;

                messenger = mActivityMessenger;

                if (messenger != null) {
                    try {
                        messenger.send(obtain);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }

            } else if (msg.what == 5) {

                obtain =
                        Message.obtain(mInHandler, 5);

                obtain.getData().putString(
                        "status",
                        mGameStatus.name()
                );

                obtain.replyTo = mMessenger;

                if (mActivityMessenger != null) {
                    try {
                        mActivityMessenger.send(obtain);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }

            } else if (msg.what == 7) {

                mGpuType =
                        msg.getData().getInt("gputype");

                if (mGpuType == 0) {
                    Log.e(TAG, "GPU not found");
                    return;
                }

                startUpdating();

            } else if (msg.what == 8) {

                /*
                 * REINSTALAR / REVERIFICAR CACHE
                 */
                mGpuType =
                        msg.getData().getInt("gputype");

                if (mGpuType == 0) {
                    Log.e(TAG, "GPU not found");
                    return;
                }

                startUpdating();
            }
        }
    }


    /*
     * ============================================================
     * CAMINHO DO CACHE
     * ============================================================
     */

    private File getGameRoot() {

        File root =
                new File(GAME_ROOT);

        if (!root.exists()) {
            boolean created = root.mkdirs();

            Log.d(
                    TAG,
                    "Criando GAME_ROOT: " +
                            root.getAbsolutePath() +
                            " criado=" +
                            created
            );
        }

        return root;
    }


    private void createGameRoot() {

        File root = getGameRoot();

        Log.d(
                TAG,
                "GAME_ROOT = " +
                        root.getAbsolutePath()
        );
    }


    /*
     * ============================================================
     * PROTEÇÃO CONTRA PATH TRAVERSAL
     * ============================================================
     */

    private File getSafeGameFile(String relativePath) {

        if (relativePath == null ||
                relativePath.trim().isEmpty()) {
            return null;
        }

        relativePath =
                relativePath.replace("\\", "/");

        while (relativePath.startsWith("/")) {
            relativePath =
                    relativePath.substring(1);
        }

        /*
         * Não permite:
         *
         * ../arquivo
         * ../../arquivo
         */
        if (relativePath.contains("../") ||
                relativePath.equals("..") ||
                relativePath.startsWith("../")) {

            Log.e(
                    TAG,
                    "Caminho bloqueado: " +
                            relativePath
            );

            return null;
        }

        File root =
                getGameRoot();

        File file =
                new File(
                        root,
                        relativePath
                );

        try {

            String rootPath =
                    root.getCanonicalPath();

            String filePath =
                    file.getCanonicalPath();

            if (!filePath.startsWith(
                    rootPath + File.separator)) {

                Log.e(
                        TAG,
                        "Caminho fora do GAME_ROOT: " +
                                filePath
                );

                return null;
            }

            return file;

        } catch (IOException e) {

            Log.e(
                    TAG,
                    "Erro verificando caminho",
                    e
            );

            return null;
        }
    }


    /*
     * ============================================================
     * VERIFICAÇÃO DO CLIENT.JSON
     * ============================================================
     */

    void startUpdating() {

        setUpdateStatus(
                UpdateActivity.UpdateStatus.CheckUpdate
        );

        Log.d(
                TAG,
                "Consultando: " +
                        CLIENT_JSON_URL
        );

        Volley
                .newRequestQueue(
                        getApplicationContext()
                )
                .add(
                        new StringRequest(
                                CLIENT_JSON_URL,

                                new Response.Listener<String>() {

                                    @Override
                                    public void onResponse(
                                            String response) {

                                        try {

                                            mUpdateFiles =
                                                    new ArrayList<>();

                                            mUpdateFilesName =
                                                    new ArrayList<>();

                                            mUpdateFilesSize =
                                                    new ArrayList<>();

                                            mUpdateFilesURL =
                                                    new ArrayList<>();

                                            mUpdateGameDataSize =
                                                    0;

                                            mUpdateGameDataSizeUpdated =
                                                    0;

                                            JSONObject root =
                                                    new JSONObject(
                                                            response
                                                    );

                                            /*
                                             * Aceita os dois formatos:
                                             *
                                             * FORMATO NOVO:
                                             * {
                                             *   "version_code": 130,
                                             *   "files": []
                                             * }
                                             *
                                             * FORMATO ANTIGO:
                                             * {
                                             *   "client_config": {
                                             *      ...
                                             *   }
                                             * }
                                             */

                                            JSONObject config;

                                            if (root.has(
                                                    "client_config")) {

                                                config =
                                                        root.getJSONObject(
                                                                "client_config"
                                                        );

                                            } else {

                                                config =
                                                        root;
                                            }


                                            mUpdateVersion =
                                                    config.optInt(
                                                            "version_code",
                                                            0
                                                    );


                                            /*
                                             * URL do APK.
                                             */
                                            mUpdateGameURL =
                                                    config.optString(
                                                            "url_launcher",
                                                            SERVER_URL +
                                                                    "launcher.apk"
                                                    );


                                            String filesURL =
                                                    config.optString(
                                                            "url_cache_files",
                                                            CLIENT_JSON_URL
                                                    );


                                            /*
                                             * Se "files" já estiver
                                             * dentro do JSON principal,
                                             * não precisamos fazer outro
                                             * download.
                                             */
                                            if (config.has("files")) {

                                                parseFilesJSON(
                                                        root
                                                );

                                            } else {

                                                getFilesInfo(
                                                        filesURL
                                                );
                                            }


                                            if (!isGameUpdateExists()) {

                                                if (mUpdateFiles == null ||
                                                        mUpdateFiles.isEmpty()) {

                                                    mGameStatus =
                                                            UpdateActivity.GameStatus.Updated;

                                                } else {

                                                    mGameStatus =
                                                            UpdateActivity.GameStatus.UpdateRequired;
                                                }

                                            } else {

                                                mGameStatus =
                                                        UpdateActivity.GameStatus.GameUpdateRequired;
                                            }


                                            setUpdateStatus(
                                                    UpdateActivity.UpdateStatus.Undefined
                                            );


                                            Log.d(
                                                    TAG,
                                                    "Verificação concluída."
                                            );

                                            Log.d(
                                                    TAG,
                                                    "Arquivos para baixar: " +
                                                            mUpdateFiles.size()
                                            );

                                            Log.d(
                                                    TAG,
                                                    "Tamanho total: " +
                                                            mUpdateGameDataSize
                                            );


                                        } catch (JSONException e) {

                                            Log.e(
                                                    TAG,
                                                    "JSON inválido",
                                                    e
                                            );

                                            mGameStatus =
                                                    UpdateActivity.GameStatus.Unknown;

                                            sendGameStatus();
                                        }
                                    }
                                },

                                new Response.ErrorListener() {

                                    @Override
                                    public void onErrorResponse(
                                            VolleyError error) {

                                        Log.e(
                                                TAG,
                                                "Erro acessando client.json: " +
                                                        error
                                        );

                                        mGameStatus =
                                                UpdateActivity.GameStatus.Unknown;

                                        sendGameStatus();
                                    }
                                }
                        )
                );
    }


    /*
     * ============================================================
     * LER FILES[]
     * ============================================================
     */

    public void getFilesInfo(String response)
            throws JSONException {

        Util.responseFilesInt = 0;

        new Thread(
                new Runnable() {

                    @Override
                    public void run() {

                        HttpURLConnection connection =
                                null;

                        BufferedReader reader =
                                null;

                        try {

                            URL url =
                                    new URL(response);

                            connection =
                                    (HttpURLConnection)
                                            url.openConnection();

                            connection.setConnectTimeout(
                                    30000
                            );

                            connection.setReadTimeout(
                                    30000
         
