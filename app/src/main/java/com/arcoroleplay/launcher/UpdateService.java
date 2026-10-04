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
     * CONFIGURAÇÃO
     * ============================================================
     */

    /*
     * MESMO diretório utilizado pelo C++.
     */
    private static final String GAME_ROOT =
            "/storage/emulated/0/Android/media/com.arcoroleplay/";

    /*
     * Servidor HTTP.
     *
     * Se o Python estiver rodando no MESMO celular:
     *
     * http://127.0.0.1:8080/
     *
     * Se estiver em outro computador/VPS, troque pelo IP/domínio.
     */
    private static final String SERVER_URL =
            "http://127.0.0.1:8080/";

    /*
     * JSON principal.
     */
    private static final String CLIENT_JSON_URL =
            SERVER_URL + "client.json";


    /*
     * ============================================================
     * MESSENGER
     * ============================================================
     */

    Messenger mMessenger;
    Messenger mActivityMessenger;
    IncomingHandler mInHandler;


    /*
     * ============================================================
     * STATUS
     * ============================================================
     */

    public UpdateActivity.GameStatus mGameStatus =
            UpdateActivity.GameStatus.Unknown;

    public UpdateActivity.UpdateStatus mUpdateStatus =
            UpdateActivity.UpdateStatus.Undefined;

    public boolean mDownloadingStatus = false;


    /*
     * ============================================================
     * DADOS DO UPDATE
     * ============================================================
     */

    public long mUpdateGameDataSize = 0;
    public long mUpdateGameDataSizeUpdated = 0;

    public String mUpdateGameURL =
            SERVER_URL + "launcher.apk";

    public int mUpdateVersion = 0;


    public ArrayList<String> mUpdateFiles;
    public ArrayList<String> mUpdateFilesName;
    public ArrayList<Long> mUpdateFilesSize;

    /*
     * URL individual de cada arquivo.
     *
     * O seu client.json pode ter:
     *
     * "url": "http://127.0.0.1:8080/SAMP/gta.dat"
     */
    public ArrayList<String> mUpdateFilesURL;


    public int mGpuType = 0;


    /*
     * ============================================================
     * CREATE
     * ============================================================
     */

    @Override
    public void onCreate() {
        super.onCreate();

        createGameRoot();

        HandlerThread handlerThread =
                new HandlerThread(
                        "ServiceStartArguments",
                        10
                );

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
                new IncomingHandler(
                        handlerThread.getLooper()
                );

        mMessenger =
                new Messenger(mInHandler);

        Log.d(
                TAG,
                "UpdateService iniciado"
        );

        Log.d(
                TAG,
                "GAME_ROOT = " +
                        GAME_ROOT
        );

        Log.d(
                TAG,
                "CLIENT_JSON_URL = " +
                        CLIENT_JSON_URL
        );
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

            /*
             * ----------------------------------------------------
             * 0 = iniciar atualização
             * ----------------------------------------------------
             */

            if (msg.what == 0) {

                mGpuType =
                        msg.getData().getInt(
                                "gputype"
                        );

                if (mGpuType == 0) {

                    Log.e(
                            TAG,
                            "GPU not found"
                    );

                    return;
                }

                startUpdating();

            /*
             * ----------------------------------------------------
             * 1 = verificar atualização dos arquivos
             * ----------------------------------------------------
             */

            } else if (msg.what == 1) {

                startGameUpdateChecking();

            /*
             * ----------------------------------------------------
             * 2 = atualizar jogo/APK
             * ----------------------------------------------------
             */

            } else if (msg.what == 2) {

                updateGame();

            /*
             * ----------------------------------------------------
             * 4 = enviar status do update
             * ----------------------------------------------------
             */

            } else if (msg.what == 4) {

                obtain =
                        Message.obtain(
                                mInHandler,
                                4
                        );

                obtain.getData().putString(
                        "status",
                        mUpdateStatus.name()
                );

                obtain.replyTo =
                        mMessenger;

                messenger =
                        mActivityMessenger;

                if (messenger != null) {

                    try {

                        messenger.send(
                                obtain
                        );

                    } catch (RemoteException e) {

                        e.printStackTrace();
                    }
                }

            /*
             * ----------------------------------------------------
             * 5 = enviar status do jogo
             * ----------------------------------------------------
             */

            } else if (msg.what == 5) {

                obtain =
                        Message.obtain(
                                mInHandler,
                                5
                        );

                obtain.getData().putString(
                        "status",
                        mGameStatus.name()
                );

                obtain.replyTo =
                        mMessenger;

                if (mActivityMessenger != null) {

                    try {

                        mActivityMessenger.send(
                                obtain
                        );

                    } catch (RemoteException e) {

                        e.printStackTrace();
                    }
                }

            /*
             * ----------------------------------------------------
             * 7 = atualizar
             * ----------------------------------------------------
             */

            } else if (msg.what == 7) {

                mGpuType =
                        msg.getData().getInt(
                                "gputype"
                        );

                if (mGpuType == 0) {

                    Log.e(
                            TAG,
                            "GPU not found"
                    );

                    return;
                }

                startUpdating();

            /*
             * ----------------------------------------------------
             * 8 = reinstalar/reverificar cache
             * ----------------------------------------------------
             */

            } else if (msg.what == 8) {

                mGpuType =
                        msg.getData().getInt(
                                "gputype"
                        );

                if (mGpuType == 0) {

                    Log.e(
                            TAG,
                            "GPU not found"
                    );

                    return;
                }

                startUpdating();
            }
        }
    }


    /*
     * ============================================================
     * GAME ROOT
     * ============================================================
     */

    private File getGameRoot() {

        File root =
                new File(
                        GAME_ROOT
                );

        if (!root.exists()) {

            boolean created =
                    root.mkdirs();

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

        File root =
                getGameRoot();

        Log.d(
                TAG,
                "GAME_ROOT = " +
                        root.getAbsolutePath()
        );
    }


    /*
     * ============================================================
     * SAFE FILE
     * ============================================================
     */

    private File getSafeGameFile(
            String relativePath
    ) {

        if (relativePath == null ||
                relativePath.trim().isEmpty()) {

            return null;
        }

        relativePath =
                relativePath.replace(
                        "\\",
                        "/"
                );

        while (
                relativePath.startsWith("/")
        ) {

            relativePath =
                    relativePath.substring(1);
        }

        if (relativePath.equals("..") ||
                relativePath.startsWith("../") ||
                relativePath.contains("/../")) {

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
                    rootPath + File.separator
            )) {

                Log.e(
                        TAG,
                        "Arquivo fora do GAME_ROOT: " +
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
     * START UPDATING
     * ============================================================
     */

    void startUpdating() {

        setUpdateStatus(
                UpdateActivity.UpdateStatus.CheckUpdate
        );

        Log.d(
                TAG,
                "Consultando client.json: " +
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
                                            String response
                                    ) {

                                        try {

                                            /*
                                             * Reset completo.
                                             */

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
                                             * Aceita:
                                             *
                                             * {
                                             *   "version_code": 130,
                                             *   "files": []
                                             * }
                                             *
                                             * ou:
                                             *
                                             * {
                                             *   "client_config": {
                                             *      ...
                                             *   }
                                             * }
                                             */

                                            JSONObject config;

                                            if (
                                                    root.has(
                                                            "client_config"
                                                    )
                                            ) {

                                                config =
                                                        root.getJSONObject(
                                                                "client_config"
                                                        );

                                            } else {

                                                config =
                                                        root;
                                            }


                                            /*
                                             * Versão do APK.
                                             */

                                            mUpdateVersion =
                                                    config.optInt(
                                                            "version_code",
                                                            0
                                                    );


                                            /*
                                             * URL do launcher.
                                             */

                                            mUpdateGameURL =
                                                    config.optString(
                                                            "url_launcher",
                                                            SERVER_URL +
                                                                    "launcher.apk"
                                                    );


                                            if (
                                                    mUpdateGameURL
                                                            .trim()
                                                            .isEmpty()
                                            ) {

                                                mUpdateGameURL =
                                                        SERVER_URL +
                                                                "launcher.apk";
                                            }


                                            /*
                                             * ------------------------------------------------
                                             * FORMATO NOVO
                                             * ------------------------------------------------
                                             *
                                             * O client.json já contém files[].
                                             */

                                            if (
                                                    config.has(
                                                            "files"
                                                    )
                                            ) {

                                                parseFilesJSON(
                                                        config
                                                );

                                            } else {

                                                /*
                                                 * ------------------------------------------------
                                                 * FORMATO ANTIGO
                                                 * ------------------------------------------------
                                                 */

                                                String filesURL =
                                                        config.optString(
                                                                "url_cache_files",
                                                                CLIENT_JSON_URL
                                                        );

                                                getFilesInfo(
                                                        filesURL
                                                );
                                            }


                                            /*
                                             * Verifica APK.
                                             */

                                            boolean gameUpdate =
                                                    isGameUpdateExists();


                                            if (!gameUpdate) {

                                                if (
                                                        mUpdateFiles == null ||
                                                        mUpdateFiles.isEmpty()
                                                ) {

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
                                                    "Verificação concluída"
                                            );

                                            Log.d(
                                                    TAG,
                                                    "Arquivos pendentes: " +
                                                            (
                                                                    mUpdateFiles == null
                                                                            ? 0
                                                                            : mUpdateFiles.size()
                                                            )
                                            );

                                            Log.d(
                                                    TAG,
                                                    "Tamanho total: " +
                                                            mUpdateGameDataSize
                                            );


                                        } catch (
                                                JSONException e
                                        ) {

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
                                            VolleyError error
                                    ) {

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
     * PARSE FILES DO CLIENT.JSON
     * ============================================================
     */

    private void parseFilesJSON(
            JSONObject jsonObject
    ) throws JSONException {

        if (mUpdateFiles == null) {
            mUpdateFiles =
                    new ArrayList<>();
        }

        if (mUpdateFilesName == null) {
            mUpdateFilesName =
                    new ArrayList<>();
        }

        if (mUpdateFilesSize == null) {
            mUpdateFilesSize =
                    new ArrayList<>();
        }

        if (mUpdateFilesURL == null) {
            mUpdateFilesURL =
                    new ArrayList<>();
        }

        JSONArray jsonArray =
                jsonObject.getJSONArray(
                        "files"
                );

        Log.d(
                TAG,
                "Arquivos no JSON: " +
                        jsonArray.length()
        );


        for (
                int i = 0;
                i < jsonArray.length();
                i++
        ) {

            JSONObject object =
                    jsonArray.getJSONObject(i);


            String name =
                    object.optString(
                            "name",
                            ""
                    );

            long size =
                    object.optLong(
                            "size",
                            0
                    );

            String path =
                    object.optString(
                            "path",
                            ""
                    );

            String url =
                    object.optString(
                            "url",
                            ""
                    );


            /*
             * Se o JSON não tiver URL individual,
             * monta automaticamente.
             */

            if (
                    url.trim().isEmpty()
            ) {

                url =
                        buildFileURL(
                                path
                        );
            }


            if (
                    path.trim().isEmpty()
            ) {

                Log.w(
                        TAG,
                        "Arquivo ignorado: path vazio"
                );

                continue;
            }


            /*
             * Arquivos que não devem ser baixados.
             */

            if (isIgnoredFile(name)) {
                continue;
            }


            /*
             * Verifica GPU.
             */

            if (!isGpuFileAllowed(path)) {
                continue;
            }


            File localFile =
                    getSafeGameFile(path);


            if (localFile == null) {
                continue;
            }


            /*
             * Se existe e possui o tamanho correto,
             * não baixa novamente.
             */

            if (
                    localFile.exists() &&
                    localFile.length() == size
            ) {

                Log.d(
                        TAG,
                        "OK: " +
                                path +
                                " (" +
                                size +
                                " bytes)"
                );

                continue;
            }


            /*
             * Arquivo precisa ser baixado.
             */

            mUpdateFiles.add(
                    path
            );

            mUpdateFilesName.add(
                    name
            );

            mUpdateFilesSize.add(
                    size
            );

            mUpdateFilesURL.add(
                    url
            );

            mUpdateGameDataSize +=
                    size;


            Log.d(
                    TAG,
                    "Pendente: " +
                            name
            );

            Log.d(
                    TAG,
                    "Path: " +
                            path
            );

            Log.d(
                    TAG,
                    "Size: " +
                            size
            );

            Log.d(
                    TAG,
                    "URL: " +
                            url
            );
        }
    }


    /*
     * ============================================================
     * FORMATO ANTIGO
     * ============================================================
     */

    public void getFilesInfo(
            String response
    ) throws JSONException {

        /*
         * Reseta estado.
         */

        Util.responseFilesInt = 0;
        Util.responseFiles = "";


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
                                    new URL(
                                            response
                                    );

                            connection =
                                    (HttpURLConnection)
                                            url.openConnection();

                            connection.setConnectTimeout(
                                    30000
                            );

                            connection.setReadTimeout(
                                    30000
                            );

                            connection.connect();


                            InputStream stream =
                                    connection.getInputStream();

                            reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    stream
                                            )
                                    );


                            StringBuilder buffer =
                                    new StringBuilder();

                            String line;


                            while (
                                    (
                                            line =
                                                    reader.readLine()
                                    ) != null
                            ) {

                                buffer.append(
                                        line
                                );

                                buffer.append(
                                        "\n"
                                );
                            }


                            Util.responseFiles =
                                    buffer.toString();

                            Util.responseFilesInt =
                                    1;


                        } catch (
                                MalformedURLException e
                        ) {

                            Log.e(
                                    TAG,
                                    "URL inválida",
                                    e
                            );

                            Util.responseFilesInt =
                                    2;


                        } catch (
                                IOException e
                        ) {

                            Log.e(
                                    TAG,
                                    "Erro baixando JSON de arquivos",
                                    e
                            );

                            Util.responseFilesInt =
                                    2;

                        } finally {

                            if (reader != null) {

                                try {
                                    reader.close();
                                } catch (IOException ignored) {
                                }
                            }

                            if (connection != null) {
                                connection.disconnect();
                            }
                        }
                    }
                }
        ).start();


        /*
         * Mantém compatibilidade com a implementação original.
         */

        int result;

        while (true) {

            result =
                    Util.responseFilesInt;

            if (result != 0) {
                break;
            }

            try {

                Thread.sleep(
                        30
                );

            } catch (
                    InterruptedException e
            ) {

                Thread.currentThread()
                        .interrupt();

                return;
            }
        }


        if (result == 2) {

            Log.e(
                    TAG,
                    "Não foi possível obter files.json"
            );

            return;
        }


        try {

            JSONObject jsonObject =
                    new JSONObject(
                            Util.responseFiles
                    );

            /*
             * Pode ser:
             *
             * { "files": [] }
             */

            if (
                    jsonObject.has(
                            "files"
                    )
            ) {

                parseFilesJSON(
                        jsonObject
                );

                return;
            }


            /*
             * Ou pode conter:
             *
             * { "client_config": { "files": [] } }
             */

            if (
                    jsonObject.has(
                            "client_config"
                    )
            ) {

                JSONObject config =
                        jsonObject.getJSONObject(
                                "client_config"
                        );

                if (
                        config.has(
                                "files"
                        )
                ) {

                    parseFilesJSON(
                            config
                    );
                }
            }

        } catch (
                JSONException e
        ) {

            Log.e(
                    TAG,
                    "JSON de arquivos inválido",
                    e
            );
        }
    }


    /*
     * ============================================================
     * URL DOS ARQUIVOS
     * ============================================================
     */

    private String buildFileURL(
            String path
    ) {

        if (path == null) {
            return SERVER_URL;
        }

        path =
                path.replace(
                        "\\",
                        "/"
                );

        while (
                path.startsWith("/")
        ) {

            path =
                    path.substring(1);
        }


        return SERVER_URL + path;
    }


    /*
     * ============================================================
     * ARQUIVOS IGNORADOS
     * ============================================================
     */

    private boolean isIgnoredFile(
            String name
    ) {

        if (name == null) {
            return true;
        }

        return
                name.equals("samp_log.txt") ||
                name.equals("svlog.txt") ||
                name.equals("gtasatelem.set") ||
                name.equals("GTASAMP10.b") ||
                name.equals(".htaccess") ||
                name.equals("gta_sa.set") ||
                name.equals("settings.ini") ||
                name.equals("client.json") ||
                name.equals("launcher.apk");
    }


    /*
     * ============================================================
     * GPU
     * ============================================================
     */

    private boolean isGpuFileAllowed(
            String path
    ) {

        if (path == null) {
            return false;
        }

        String lower =
                path.toLowerCase();


        /*
         * Arquivos comuns sempre permitidos.
         */

        if (
                !lower.contains(".dxt.") &&
                !lower.contains(".etc.") &&
                !lower.contains(".pvr.")
        ) {

            return true;
        }


        /*
         * DXT
         */

        if (
                lower.contains(".dxt.")
        ) {

            return mGpuType == 1;
        }


        /*
         * ETC
         */

        if (
                lower.contains(".etc.")
        ) {

            return mGpuType == 2;
        }


        /*
         * PVR
         */

        if (
                lower.contains(".pvr.")
        ) {

            return mGpuType == 3;
        }


        return true;
    }


    /*
     * ============================================================
     * UPDATE GAME
     * ============================================================
     */

    public void updateGame() {

        if (isGameUpdateExists()) {

            Log.d(
                    TAG,
                    "updateGame: APK precisa ser atualizado"
            );

            downloadGame();

            return;
        }


        Log.d(
                TAG,
                "updateGame: APK já está atualizado"
        );


        File file =
                new File(
                        getExternalFilesDir(null),
                        "download/update.apk"
                );


        Message obtain =
                Message.obtain(
                        mInHandler,
                        2
                );

        obtain.getData().putBoolean(
                "status",
                true
        );

        obtain.getData().putString(
                "apkPath",
                file.getAbsolutePath()
        );

        obtain.replyTo =
                mMessenger;


        if (
                mActivityMessenger != null
        ) {

            try {

                mActivityMessenger.send(
                        obtain
                );

            } catch (
                    RemoteException e
            ) {

                e.printStackTrace();
            }
        }
    }


    /*
     * ============================================================
     * VERIFICAÇÃO DOS ARQUIVOS
     * ============================================================
     */

    public void startGameUpdateChecking() {

        if (
                mUpdateFiles != null &&
                !mUpdateFiles.isEmpty()
        ) {

            setUpdateStatus(
                    UpdateActivity.UpdateStatus.DownloadGameData
            );

            startDataUpdating();

            return;
        }


        Log.d(
                TAG,
                "Nenhum arquivo precisa ser atualizado"
        );


        Message obtain =
                Message.obtain(
                        mInHandler,
                        1
                );

        obtain.getData().putBoolean(
                "status",
                true
        );

        obtain.replyTo =
                mMessenger;


        if (
                mActivityMessenger != null
        ) {

            try {

                mActivityMessenger.send(
                        obtain
                );

            } catch (
                    RemoteException e
            ) {

                e.printStackTrace();
            }
        }
    }


    /*
     * ============================================================
     * UPDATE STATUS
     * ============================================================
     */

    public void setUpdateStatus(
            UpdateActivity.UpdateStatus updateStatus
    ) {

        if (
                updateStatus == null
        ) {
            return;
        }


        if (
                updateStatus.name().length() != 0 &&
                mUpdateStatus != updateStatus
        ) {

            mUpdateStatus =
                    updateStatus;


            Message obtain =
                    Message.obtain(
                            mInHandler,
                            4
                    );

            obtain.getData().putString(
                    "status",
                    mUpdateStatus.name()
            );

            obtain.replyTo =
                    mMessenger;


            Messenger messenger =
                    mActivityMessenger;


            if (
                    messenger != null
            ) {

                try {

                    messenger.send(
                            obtain
                    );

                } catch (
                        RemoteException e
                ) {

                    e.printStackTrace();
                }
            }
        }
    }


    /*
     * ============================================================
     * DOWNLOAD DOS ARQUIVOS DO CACHE
     * ============================================================
     */

    public void startDataUpdating() {

        /*
         * Copiamos as listas para evitar alteração
         * durante os callbacks do PRDownloader.
         */

        ArrayList<String> files =
                new ArrayList<>(
                        mUpdateFiles
                );

        ArrayList<String> names =
                new ArrayList<>(
                        mUpdateFilesName
                );

        ArrayList<Long> sizes =
                new ArrayList<>(
                        mUpdateFilesSize
                );

        ArrayList<String> urls =
                new ArrayList<>(
                        mUpdateFilesURL
                );


        mUpdateFiles.clear();


        mUpdateGameDataSizeUpdated =
                0;


        for (
                int index = 0;
                index < files.size();
                index++
        ) {

            final int currentIndex =
                    index;


            final String relativePath =
                    files.get(
                            currentIndex
                    );

            final String filename =
                    names.get(
                            currentIndex
                    );

            final long expectedSize =
                    sizes.get(
                            currentIndex
                    );


            String downloadURL = null;

            if (
                    currentIndex <
                            urls.size()
            ) {

                downloadURL =
                        urls.get(
                                currentIndex
                        );
            }


            if (
                    downloadURL == null ||
                    downloadURL.trim().isEmpty()
            ) {

                downloadURL =
                        buildFileURL(
                                relativePath
                        );
            }


            /*
             * Proteção do caminho.
             */

            File target =
                    getSafeGameFile(
                            relativePath
                    );


            if (
                    target == null
            ) {

                Log.e(
                        TAG,
                        "Download bloqueado: " +
                                relativePath
                );

                mUpdateFiles.add(
                        relativePath
                );

                continue;
            }


            File parent =
                    target.getParentFile();


            if (
                    parent != null &&
                    !parent.exists()
            ) {

                parent.mkdirs();
            }


            if (
                    target.exists()
            ) {

                target.delete();
            }


            final File finalTarget =
                    target;


            mDownloadingStatus =
                    true;


            final long startTime =
                    System.currentTimeMillis();


            Log.d(
                    TAG,
                    "Baixando: " +
                            downloadURL
            );

            Log.d(
                    TAG,
                    "Destino: " +
                            finalTarget.getAbsolutePath()
            );


            PRDownloader
                    .download(
                            downloadURL,
                            finalTarget.getParent(),
                            finalTarget.getName()
                    )
                    .build()

                    .setOnStartOrResumeListener(
                            null
                    )

                    .setOnPauseListener(
                            null
                    )

                    .setOnCancelListener(
                            null
                    )

                    .setOnProgressListener(
                            new OnProgressListener() {

                                @Override
                                public void onProgress(
                                        Progress progress
                                ) {

                                    mDownloadingStatus =
                                            true;


                                    if (
                                            System.currentTimeMillis()
                                                    - startTime
                                                    > 100
                                    ) {

                                        sendDownloadProgress(
                                                filename,
                                                currentIndex,
                                                files.size(),
                                                mUpdateGameDataSizeUpdated +
                                                        progress.currentBytes,
                                                mUpdateGameDataSize
                                        );
                                    }
                                }
                            }
                    )

                    .start(
                            new OnDownloadListener() {

                                @Override
                                public void onDownloadComplete() {

                                    mDownloadingStatus =
                                            false;


                                    /*
                                     * Confere o tamanho final.
                                     */

                                    if (
                                            finalTarget.exists() &&
                                            expectedSize > 0 &&
                                            finalTarget.length()
                                                    != expectedSize
                                    ) {

                                        Log.e(
                                                TAG,
                                                "Tamanho incorreto: " +
                                                        relativePath +
                                                        " esperado=" +
                                                        expectedSize +
                                                        " atual=" +
                                                        finalTarget.length()
                                        );


                                        if (
                                                finalTarget.exists()
                                        ) {

                                            finalTarget.delete();
                                        }


                                        mUpdateFiles.add(
                                                relativePath
                                        );

                                    } else {

                                        mUpdateGameDataSizeUpdated +=
                                                expectedSize;

                                        Log.d(
                                                TAG,
                                                "Download concluído: " +
                                                        relativePath
                                        );
                                    }
                                }


                                @Override
                                public void onError(
                                        Error error
                                ) {

                                    mDownloadingStatus =
                                            false;


                                    mUpdateFiles.add(
                                            relativePath
                                    );


                                    Log.e(
                                            TAG,
                                            "Erro baixando: " +
                                                    relativePath +
                                                    " error=" +
                                                    error
                                    );
                                }
                            }
                    );


            /*
             * Mantém o comportamento síncrono do original:
             * espera o arquivo terminar antes do próximo.
             */

            while (
                    mDownloadingStatus
            ) {

                try {

                    Thread.sleep(
                            30
                    );

                } catch (
                        InterruptedException e
                ) {

                    Thread.currentThread()
                            .interrupt();

                    break;
                }
            }
        }


        mDownloadingStatus =
                false;


        Log.d(
                TAG,
                "Download do cache concluído"
        );


        Message obtain =
                Message.obtain(
                        mInHandler,
                        1
                );

        obtain.getData().putBoolean(
                "status",
                true
        );

        obtain.replyTo =
                mMessenger;


        if (
                mActivityMessenger != null
        ) {

            try {

                mActivityMessenger.send(
                        obtain
                );

            } catch (
                    RemoteException e
            ) {

                e.printStackTrace();
            }
        }
    }


    /*
     * ============================================================
     * PROGRESSO DOS ARQUIVOS
     * ============================================================
     */

    private void sendDownloadProgress(
            String filename,
            int currentFile,
            int totalFiles,
            long current,
            long total
    ) {

        Message obtain =
                Message.obtain(
                        mInHandler,
                        4
                );


        obtain.getData().putString(
                "status",
                UpdateActivity.UpdateStatus.DownloadGameData.name()
        );

        obtain.getData().putBoolean(
                "withProgress",
                true
        );

        obtain.getData().putLong(
                "current",
                current
        );

        obtain.getData().putLong(
                "total",
                total
        );

        obtain.getData().putString(
                "filename",
                filename
        );

        obtain.getData().putLong(
                "totalfiles",
                totalFiles
        );

        obtain.getData().putLong(
                "currentfile",
                currentFile
        );


        if (
                mActivityMessenger != null
        ) {

            try {

                mActivityMessenger.send(
                        obtain
                );

            } catch (
                    RemoteException e
            ) {

                e.printStackTrace();
            }
        }
    }


    /*
     * ============================================================
     * DOWNLOAD DO APK
     * ============================================================
     */

    public void downloadGame() {

        Log.d(
                TAG,
                "downloadGame: " +
                        mUpdateGameURL
        );


        mDownloadingStatus =
                true;


        File downloadDir =
                new File(
                        getExternalFilesDir(null),
                        "download"
                );


        if (
                !downloadDir.exists()
        ) {

            downloadDir.mkdirs();
        }


        File file =
                new File(
                        downloadDir,
                        "update.apk"
                );


        if (
                file.exists()
        ) {

            file.delete();
        }


        final long startTime =
                System.currentTimeMillis();


        PRDownloader
                .download(
                        mUpdateGameURL,
                        downloadDir.getAbsolutePath(),
                        "update.apk"
                )
                .build()

                .setOnStartOrResumeListener(
                        null
                )

                .setOnPauseListener(
                        null
                )

                .setOnCancelListener(
                        null
                )

                .setOnProgressListener(
                        new OnProgressListener() {

                            @Override
                            public void onProgress(
                                    Progress progress
                            ) {

                                mDownloadingStatus =
                                        true;


                                if (
                                        System.currentTimeMillis()
                                                - startTime
                                                > 100
                                ) {

                                    Message obtain =
                                            Message.obtain(
                                                    mInHandler,
                                                    4
                                            );

                                    obtain.getData().putString(
                                            "status",
                                            UpdateActivity.UpdateStatus.DownloadGame.name()
                                    );

                                    obtain.getData().putBoolean(
                                            "withProgress",
                                            true
                                    );

                                    obtain.getData().putLong(
                                            "current",
                                            progress.currentBytes
                                    );

                                    obtain.getData().putLong(
                                            "total",
                                            progress.totalBytes
                                    );

                                    obtain.getData().putString(
                                            "filename",
                                            "update.apk"
                                    );

                                    obtain.getData().putLong(
                                            "totalfiles",
                                            1
                                    );

                                    obtain.getData().putLong(
                                            "currentfile",
                                            1
                                    );


                                    if (
                                            mActivityMessenger != null
                                    ) {

                                        try {

                                            mActivityMessenger.send(
                                                    obtain
                                            );

                                        } catch (
                                                RemoteException e
                                        ) {

                                            e.printStackTrace();
                                        }
                                    }
                                }
                            }
                        }
                )

                .start(
                        new OnDownloadListener() {

                            @Override
                            public void onDownloadComplete() {

                                mDownloadingStatus =
                                        false;


                                Log.d(
                                        TAG,
                                        "APK concluído: " +
                                                file.getAbsolutePath()
                                );


                                Message obtain =
                                        Message.obtain(
                                                mInHandler,
                                                2
                                        );

                                obtain.getData().putBoolean(
                                        "status",
                                        true
                                );

                                obtain.getData().putString(
                                        "apkPath",
                                        file.getAbsolutePath()
                                );

                                obtain.replyTo =
                                        mMessenger;


                                if (
                                        mActivityMessenger != null
                                ) {

                                    try {

                                        mActivityMessenger.send(
                                                obtain
                                        );

                                    } catch (
                                            RemoteException e
                                    ) {

                                        e.printStackTrace();
                                    }
                                }


                                setUpdateStatus(
                                        UpdateActivity.UpdateStatus.Undefined
                                );
                            }


                            @Override
                            public void onError(
                                    Error error
                            ) {

                                mDownloadingStatus =
                                        false;


                                Log.e(
                                        TAG,
                                        "Erro baixando APK: " +
                                                error
                                );


                                /*
                                 * Não chama downloadGame()
                                 * recursivamente para evitar
                                 * loop infinito.
                                 */

                                mGameStatus =
                                        UpdateActivity.GameStatus.Unknown;

                                sendGameStatus();
                            }
                        }
                );


        while (
                mDownloadingStatus
        ) {

            try {

                Thread.sleep(
                        30
                );

            } catch (
                    InterruptedException e
            ) {

                Thread.currentThread()
                        .interrupt();

                break;
            }
        }


        mDownloadingStatus =
                false;
    }


    /*
     * ============================================================
     * VERIFICAR VERSÃO DO APK
     * ============================================================
     */

    public boolean isGameUpdateExists() {

        PackageInfo packageInfo;


        try {

            packageInfo =
                    getPackageManager()
                            .getPackageInfo(
                                    "com.arcoroleplay",
                                    PackageManager.GET_ACTIVITIES
                            );

        } catch (
                PackageManager.NameNotFoundException e
        ) {

            /*
             * Se o APK ainda não estiver instalado,
             * precisamos baixar o launcher.
             */

            Log.d(
                    TAG,
                    "Pacote com.arcoroleplay não encontrado."
            );

            return true;
        }


        int currentVersion =
                packageInfo.versionCode;


        Log.d(
                TAG,
                "isGameUpdateExists -> currentVersion=" +
                        currentVersion +
                        " | mUpdateVersion=" +
                        mUpdateVersion
        );


        return currentVersion !=
                mUpdateVersion;
    }


    /*
     * ============================================================
     * ENVIAR STATUS DO JOGO
     * ============================================================
     */

    private void sendGameStatus() {

        Message obtain =
                Message.obtain(
                        mInHandler,
                        5
                );


        obtain.getData().putString(
                "status",
                mGameStatus.name()
        );


        obtain.replyTo =
                mMessenger;


        if (
                mActivityMessenger != null
        ) {

            try {

                mActivityMessenger.send(
                        obtain
                );

            } catch (
                    RemoteException e
            ) {

                e.printStackTrace();
            }
        }
    }


    /*
     * ============================================================
     * LOADING SCREEN
     * ============================================================
     */

    private void sendLoadingScreen(
            boolean unpacking,
            String filename,
            long current,
            long total
    ) {

        new Thread(
                new Runnable() {

                    @Override
                    public void run() {

                        Message obtain =
                                Message.obtain(
                                        UpdateService.this.mInHandler,
                                        4
                                );


                        obtain.getData().putString(
                                "status",
                                UpdateActivity.UpdateStatus.CheckUpdate.name()
                        );

                        obtain.getData().putBoolean(
                                "withProgress",
                                true
                        );

                        obtain.getData().putString(
                                "filename",
                                filename
                        );

                        obtain.getData().putBoolean(
                                "unpacking",
                                unpacking
                        );

                        obtain.getData().putLong(
                                "current",
                                current
                        );

                        obtain.getData().putLong(
                                "total",
                                total
                        );


                        obtain.replyTo =
                                mMessenger;


                        if (
                                mActivityMessenger != null
                        ) {

                            try {

                                mActivityMessenger.send(
                                        obtain
                                );

                            } catch (
                                    RemoteException e
                            ) {

                                e.printStackTrace();
                            }
                        }
                    }
                }
        ).start();
    }
}
