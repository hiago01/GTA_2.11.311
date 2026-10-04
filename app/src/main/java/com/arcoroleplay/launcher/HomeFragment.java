package com.arcoroleplay.launcher;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.arcoroleplay.R;
import com.arcoroleplay.game.SAMP;
import com.arcoroleplay.launcher.util.RemoteConfigManager;

public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {

        Log.i(TAG, "onCreateView()");

        View view = inflater.inflate(
                R.layout.fragment_home,
                container,
                false
        );

        View btnPlay = view.findViewById(R.id.btnPlay);
        View discordBtn = view.findViewById(R.id.discordBtn);
        View webBtn = view.findViewById(R.id.webBtn);
        View youtubeBtn = view.findViewById(R.id.youtubeBtn);

        if (btnPlay == null) {
            Log.e(TAG, "ERRO: btnPlay não encontrado em fragment_home.xml");
        }

        if (discordBtn == null) {
            Log.e(TAG, "ERRO: discordBtn não encontrado em fragment_home.xml");
        }

        if (webBtn == null) {
            Log.e(TAG, "ERRO: webBtn não encontrado em fragment_home.xml");
        }

        if (youtubeBtn == null) {
            Log.e(TAG, "ERRO: youtubeBtn não encontrado em fragment_home.xml");
        }

        // ============================================================
        // DISCORD / TESTE DO UPDATE SERVICE
        // ============================================================

        if (discordBtn != null) {
            discordBtn.setOnClickListener(v -> {

                Log.i(TAG, "========================================");
                Log.i(TAG, "DISCORD BUTTON CLICKED");
                Log.i(TAG, "INICIANDO TESTE DO UPDATE SERVICE");
                Log.i(TAG, "========================================");

                try {

                    if (!isAdded() || getActivity() == null) {
                        Log.e(TAG, "Fragment não está anexado à Activity.");
                        return;
                    }

                    Log.i(TAG, "Activity OK");

                    Log.i(TAG, "Chamando UpdateService.startUpdating()...");

                    UpdateService.startUpdating(
                            requireContext()
                    );

                    Log.i(TAG, "UpdateService.startUpdating() chamado.");

                    Toast.makeText(
                            requireContext(),
                            "Verificando atualizações...",
                            Toast.LENGTH_SHORT
                    ).show();

                } catch (IllegalStateException e) {

                    Log.e(
                            TAG,
                            "Fragment/Contexto inválido ao iniciar UpdateService.",
                            e
                    );

                    showError(
                            "Não foi possível iniciar a atualização."
                    );

                } catch (Throwable e) {

                    Log.e(
                            TAG,
                            "ERRO AO INICIAR UPDATE SERVICE",
                            e
                    );

                    showError(
                            "Erro ao iniciar o UpdateService."
                    );
                }
            });
        }

        // ============================================================
        // WEBSITE
        // ============================================================

        if (webBtn != null) {
            webBtn.setOnClickListener(v -> {

                Log.i(TAG, "Website button clicked");

                try {

                    String link = RemoteConfigManager.getString("website");

                    Log.i(TAG, "Website URL: " + link);

                    openUrl(link);

                } catch (Throwable e) {

                    Log.e(
                            TAG,
                            "Erro ao abrir website",
                            e
                    );

                    showError(
                            "Não foi possível abrir o site."
                    );
                }
            });
        }

        // ============================================================
        // YOUTUBE
        // ============================================================

        if (youtubeBtn != null) {
            youtubeBtn.setOnClickListener(v -> {

                Log.i(TAG, "YouTube button clicked");

                try {

                    String link = RemoteConfigManager.getString("youtube");

                    Log.i(TAG, "YouTube URL: " + link);

                    openUrl(link);

                } catch (Throwable e) {

                    Log.e(
                            TAG,
                            "Erro ao abrir YouTube",
                            e
                    );

                    showError(
                            "Não foi possível abrir o YouTube."
                    );
                }
            });
        }

        // ============================================================
        // PLAY
        // ============================================================

        if (btnPlay != null) {
            btnPlay.setOnClickListener(v -> {

                Log.i(TAG, "========================================");
                Log.i(TAG, "PLAY BUTTON CLICKED");
                Log.i(TAG, "========================================");

                try {

                    Log.i(TAG, "1. Verificando Activity...");

                    if (!isAdded() || getActivity() == null) {

                        Log.e(
                                TAG,
                                "Fragment não está anexado à Activity."
                        );

                        return;
                    }

                    Log.i(TAG, "2. Activity OK");

                    Log.i(
                            TAG,
                            "3. Criando Intent para SAMP.class"
                    );

                    Intent intent = new Intent(
                            requireActivity(),
                            SAMP.class
                    );

                    intent.setFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    );

                    Log.i(TAG, "4. Intent criado");

                    Log.i(TAG, "5. Iniciando SAMP...");

                    startActivity(intent);

                    Log.i(
                            TAG,
                            "6. startActivity() executado"
                    );

                    requireActivity().overridePendingTransition(
                            android.R.anim.fade_in,
                            android.R.anim.fade_out
                    );

                    Log.i(
                            TAG,
                            "7. Transição executada"
                    );

                } catch (ActivityNotFoundException e) {

                    Log.e(
                            TAG,
                            "SAMP Activity não encontrada no Manifest.",
                            e
                    );

                    showError(
                            "Erro: SAMP não foi encontrada no AndroidManifest."
                    );

                } catch (IllegalStateException e) {

                    Log.e(
                            TAG,
                            "Estado inválido ao iniciar SAMP.",
                            e
                    );

                    showError(
                            "Erro ao iniciar o jogo."
                    );

                } catch (Throwable e) {

                    Log.e(
                            TAG,
                            "Erro Java ao iniciar SAMP.",
                            e
                    );

                    showError(
                            "Erro ao iniciar o jogo."
                    );
                }
            });
        }

        return view;
    }

    // ================================================================
    // OPEN URL
    // ================================================================

    private void openUrl(String url) {

        if (url == null || url.trim().isEmpty()) {

            Log.e(
                    TAG,
                    "URL vazia ou nula."
            );

            showError(
                    "Link não configurado."
            );

            return;
        }

        try {

            Uri uri = Uri.parse(url);

            Intent intent = new Intent(
                    Intent.ACTION_VIEW,
                    uri
            );

            startActivity(intent);

        } catch (ActivityNotFoundException e) {

            Log.e(
                    TAG,
                    "Nenhum aplicativo pode abrir: " + url,
                    e
            );

            showError(
                    "Nenhum aplicativo pode abrir este link."
            );

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "Erro ao abrir URL: " + url,
                    e
            );

            showError(
                    "Não foi possível abrir o link."
            );
        }
    }

    // ================================================================
    // ERROR MESSAGE
    // ================================================================

    private void showError(String message) {

        if (!isAdded()) {
            return;
        }

        Toast.makeText(
                requireContext(),
                message,
                Toast.LENGTH_LONG
        ).show();
    }
}
